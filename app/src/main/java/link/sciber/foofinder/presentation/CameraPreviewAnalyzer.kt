package link.sciber.foofinder.presentation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.core.graphics.scale
import link.sciber.foofinder.data.detection.FooDetector
import link.sciber.foofinder.domain.Detection
import link.sciber.foofinder.domain.DetectionArea
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class CameraPreviewAnalyzer(
    private val detector: FooDetector, private val onDetectionResult: (Detection) -> Unit
) : ImageAnalysis.Analyzer {

    private var tileIndex: Int = 0
    private val pendingTileCapture = AtomicReference<TileCaptureCallback?>(null)

    /** Scanning strategies for object detection. */
    enum class ScanStrategy {
        SCALED_SINGLE, SINGLE_CENTER, ROWS, COLUMNS, RANDOM
    }

    data class TileCaptureResult(
        val bitmap: Bitmap, val area: DetectionArea, val rotationDegrees: Int, val timestampMs: Long
    )

    fun interface TileCaptureCallback {
        fun onTileCaptured(result: TileCaptureResult?)
    }

    fun requestTileCapture(callback: TileCaptureCallback): Boolean {
        return pendingTileCapture.compareAndSet(null, callback)
    }

    private var strategy = ScanStrategy.RANDOM

    companion object {
        private const val TAG = "CameraPreviewAnalyzer"
    }

    // Rolling FPS window
    private val timestampWindowMs = 1500L
    private val timestamps = ArrayDeque<Long>()

    init {
        Log.d(TAG, "Analyzer created with detector")
    }

    /** Update scanning strategy at runtime. */
    fun setScanStrategy(newStrategy: ScanStrategy) {
        strategy = newStrategy
        Log.d(TAG, "Scan strategy set to $newStrategy")
    }

    override fun analyze(imageProxy: ImageProxy) {
        fun emptyDetectionResult() = Detection(
            boundingBoxes = emptyList(),
            area = DetectionArea(0f, 0f, 0f, 0f),
            inferenceMs = -1,
            fps = 0f,
            rawDetections = 0,
            afterNmsDetections = 0
        )

        try {
            val width = imageProxy.width
            val height = imageProxy.height
            val format = imageProxy.format
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees

            val (displayWidth, displayHeight) = if (rotationDegrees == 90 || rotationDegrees == 270) height to width
            else width to height

            Log.d(
                TAG,
                "Sensor frame: ${width}x${height}, Display frame: ${displayWidth}x${displayHeight}, " + "format: $format, rotation: ${rotationDegrees}°"
            )

            val analyzeStart = System.nanoTime()
            val convertStart = System.nanoTime()
            val bitmap = imageProxyToBitmap(imageProxy)
            val convertMs = (System.nanoTime() - convertStart) / 1_000_000L
            Log.d(TAG, "Timing(analyze): convert=${convertMs}ms")

            if (bitmap != null) {
                val detectStart = System.nanoTime()
                val baseSide = min(bitmap.width, bitmap.height)
                val modelInputSize = detector.getModelInputSize().coerceAtLeast(1)
                var detectionAreaUsed: DetectionArea? = null

                val detection = when (strategy) {
                    ScanStrategy.SCALED_SINGLE -> detector.detect(bitmap).also {
                        detectionAreaUsed = DetectionArea(
                            0f, 0f, baseSide.toFloat(), baseSide.toFloat()
                        )
                    }

                    ScanStrategy.SINGLE_CENTER -> {
                        val tileSize = detector.getModelInputSize().coerceAtMost(baseSide)
                        val area = computeTileArea(
                            baseStartX = 0,
                            baseStartY = 0,
                            baseSide = baseSide,
                            tileSize = tileSize,
                            index = 0,
                            strategy = ScanStrategy.SINGLE_CENTER,
                            rotationDegrees = rotationDegrees
                        )

                        detector.detectInArea(bitmap, area).also { detectionAreaUsed = area }
                    }

                    ScanStrategy.ROWS, ScanStrategy.COLUMNS -> {
                        val tileSize = detector.getModelInputSize().coerceAtMost(baseSide)
                        val area = computeTileArea(
                            baseStartX = 0,
                            baseStartY = 0,
                            baseSide = baseSide,
                            tileSize = tileSize,
                            index = tileIndex++,
                            strategy = strategy,
                            rotationDegrees = rotationDegrees
                        )

                        detector.detectInArea(bitmap, area).also { detectionAreaUsed = area }
                    }

                    ScanStrategy.RANDOM -> {
                        val tileSize = detector.getModelInputSize().coerceAtMost(baseSide)
                        val area = computeTileArea(
                            baseStartX = 0,
                            baseStartY = 0,
                            baseSide = baseSide,
                            tileSize = tileSize,
                            index = tileIndex++,
                            strategy = ScanStrategy.RANDOM,
                            rotationDegrees = rotationDegrees
                        )

                        detector.detectInArea(bitmap, area).also { detectionAreaUsed = area }
                    }
                }

                val detectMs = (System.nanoTime() - detectStart) / 1_000_000L
                val transformStart = System.nanoTime()
                val transformedDetection = transformDetectionCoordinates(
                    detection,
                    bitmap.width,
                    bitmap.height,
                    displayWidth,
                    displayHeight,
                    rotationDegrees
                )
                val transformMs = (System.nanoTime() - transformStart) / 1_000_000L

                val now = System.currentTimeMillis()
                timestamps.addLast(now)
                while (timestamps.isNotEmpty() && now - timestamps.first() > timestampWindowMs) {
                    timestamps.removeFirst()
                }
                val elapsed = (timestamps.last() - timestamps.first()).coerceAtLeast(1)
                val fps = if (timestamps.size >= 2) (timestamps.size - 1) * 1000f / elapsed
                else 0f

                onDetectionResult(
                    transformedDetection.copy(
                        fps = fps, afterNmsDetections = transformedDetection.boundingBoxes.size
                    )
                )

                Log.d(
                    TAG,
                    "Detection completed: ${transformedDetection.boundingBoxes.size} objects detected"
                )

                handlePendingTileCapture(
                    frameBitmap = bitmap,
                    area = detectionAreaUsed,
                    rotationDegrees = rotationDegrees,
                    modelInputSize = modelInputSize
                )

                val analyzeMs = (System.nanoTime() - analyzeStart) / 1_000_000L
                Log.d(
                    TAG,
                    "Timing(analyze): detect=${detectMs}ms, transform=${transformMs}ms, total=${analyzeMs}ms"
                )

                bitmap.recycle()
            } else {
                Log.w(TAG, "Failed to convert ImageProxy to Bitmap")
                onDetectionResult(emptyDetectionResult())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing image", e)
            onDetectionResult(emptyDetectionResult())
        } finally {
            imageProxy.close()
        }
    }

    private fun handlePendingTileCapture(
        frameBitmap: Bitmap, area: DetectionArea?, rotationDegrees: Int, modelInputSize: Int
    ) {
        val callback = pendingTileCapture.getAndSet(null) ?: return
        if (area == null || area.width <= 0f || area.height <= 0f) {
            Log.w(TAG, "Tile capture requested but area invalid: $area")
            callback.onTileCaptured(null)
            return
        }
        try {
            val startX = area.startX.toInt().coerceIn(0, frameBitmap.width - 1)
            val startY = area.startY.toInt().coerceIn(0, frameBitmap.height - 1)
            val maxWidth = frameBitmap.width - startX
            val maxHeight = frameBitmap.height - startY
            val desiredWidth = area.width.toInt().coerceAtLeast(1)
            val desiredHeight = area.height.toInt().coerceAtLeast(1)
            val cropWidth = desiredWidth.coerceAtMost(maxWidth)
            val cropHeight = desiredHeight.coerceAtMost(maxHeight)

            if (cropWidth <= 0 || cropHeight <= 0) {
                Log.w(
                    TAG, "Tile capture failed due to invalid crop size: ${cropWidth}x${cropHeight}"
                )
                callback.onTileCaptured(null)
                return
            }

            var tile = Bitmap.createBitmap(frameBitmap, startX, startY, cropWidth, cropHeight)

            if (tile.width != modelInputSize || tile.height != modelInputSize) {
                val scaled = tile.scale(modelInputSize, modelInputSize)
                if (scaled !== tile) {
                    tile.recycle()
                }
                tile = scaled
            }

            if (rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                val rotated = Bitmap.createBitmap(
                    tile, 0, 0, tile.width, tile.height, matrix, true
                )
                if (rotated !== tile) {
                    tile.recycle()
                    tile = rotated
                }
            }

            callback.onTileCaptured(
                TileCaptureResult(
                    bitmap = tile,
                    area = area,
                    rotationDegrees = rotationDegrees,
                    timestampMs = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Tile capture failed", e)
            callback.onTileCaptured(null)
        }
    }

    private fun computeTileArea(
        baseStartX: Int,
        baseStartY: Int,
        baseSide: Int,
        tileSize: Int,
        index: Int,
        strategy: ScanStrategy,
        rotationDegrees: Int
    ): DetectionArea {
        val stepsX = max(1, ceil(baseSide.toFloat() / tileSize).toInt())
        val stepsY = max(1, ceil(baseSide.toFloat() / tileSize).toInt())

        if (strategy == ScanStrategy.RANDOM) {
            val maxOffset = (baseSide - tileSize).coerceAtLeast(0)
            val offsetX = if (maxOffset > 0) kotlin.random.Random.nextInt(maxOffset + 1)
            else 0
            val offsetY = if (maxOffset > 0) kotlin.random.Random.nextInt(maxOffset + 1)
            else 0
            return DetectionArea(
                startX = (baseStartX + offsetX).toFloat(),
                startY = (baseStartY + offsetY).toFloat(),
                width = tileSize.toFloat(),
                height = tileSize.toFloat()
            )
        }

        if (strategy == ScanStrategy.SINGLE_CENTER) {
            val offset = ((baseSide - tileSize) / 2f).coerceAtLeast(0f)
            return DetectionArea(
                startX = baseStartX + offset,
                startY = baseStartY + offset,
                width = tileSize.toFloat(),
                height = tileSize.toFloat()
            )
        }

        val totalTiles = stepsX * stepsY
        val clampedIndex = if (totalTiles > 0) index % totalTiles else 0

        val isRotated = rotationDegrees == 90 || rotationDegrees == 270
        val displayCols = if (isRotated) stepsY else stepsX
        val displayRows = if (isRotated) stepsX else stepsY

        val (col, row) = when (strategy) {
            ScanStrategy.ROWS -> {
                var displayCol = clampedIndex % displayCols
                val displayRow = clampedIndex / displayCols
                if (isRotated) displayCol = displayCols - 1 - displayCol
                if (isRotated) displayRow to displayCol
                else displayCol to displayRow
            }

            ScanStrategy.COLUMNS -> {
                val displayRow = clampedIndex % displayRows
                var displayCol = clampedIndex / displayRows
                if (isRotated) displayCol = displayCols - 1 - displayCol
                if (isRotated) displayRow to displayCol
                else displayCol to displayRow
            }

            else -> 0 to 0
        }

        val strideX = if (stepsX > 1) (baseSide - tileSize).toFloat() / (stepsX - 1) else 0f
        val strideY = if (stepsY > 1) (baseSide - tileSize).toFloat() / (stepsY - 1) else 0f

        val startX = baseStartX + min((col * strideX).toInt(), baseSide - tileSize)
        val startY = baseStartY + min((row * strideY).toInt(), baseSide - tileSize)

        return DetectionArea(
            startX = startX.toFloat(),
            startY = startY.toFloat(),
            width = tileSize.toFloat(),
            height = tileSize.toFloat()
        )
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            when (imageProxy.format) {
                ImageFormat.YUV_420_888 -> {
                    // Convert YUV_420_888 to Bitmap with timing
                    val tYuvStart = System.nanoTime()
                    val bmp = yuvToBitmap(imageProxy)
                    val tYuvEnd = System.nanoTime()
                    val yuvMs = ((tYuvEnd - tYuvStart) / 1_000_000L)
                    Log.d(TAG, "Timing(convert): YUV->Bitmap=${yuvMs}ms")
                    bmp
                }

                ImageFormat.JPEG -> {
                    // Convert JPEG to Bitmap with timing
                    val tJpegStart = System.nanoTime()
                    val buffer = imageProxy.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    val tJpegEnd = System.nanoTime()
                    val jpegMs = ((tJpegEnd - tJpegStart) / 1_000_000L)
                    Log.d(TAG, "Timing(convert): JPEG->Bitmap=${jpegMs}ms")
                    bmp
                }

                else -> {
                    Log.w(TAG, "Unsupported image format: ${imageProxy.format}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error converting ImageProxy to Bitmap", e)
            null
        }
    }

    private fun yuvToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val tStart = System.nanoTime()

            val yPlane = imageProxy.planes[0]
            val uPlane = imageProxy.planes[1]
            val vPlane = imageProxy.planes[2]

            val width = imageProxy.width
            val height = imageProxy.height

            // NV21 format: Y plane followed by interleaved VU
            val ySize = width * height
            val uvSize = width * height / 2
            val nv21 = ByteArray(ySize + uvSize)

            val tCopyStart = System.nanoTime()

            // Copy Y plane with proper stride handling
            val yBuffer = yPlane.buffer
            val yRowStride = yPlane.rowStride
            val yPixelStride = yPlane.pixelStride

            if (yPixelStride == 1 && yRowStride == width) {
                // Contiguous Y plane - fast path
                yBuffer.get(nv21, 0, ySize)
            } else {
                // Non-contiguous Y plane - copy row by row
                var pos = 0
                for (row in 0 until height) {
                    yBuffer.position(row * yRowStride)
                    yBuffer.get(nv21, pos, width)
                    pos += width
                }
            }

            // Copy UV planes to NV21 format (interleaved VU)
            val vBuffer = vPlane.buffer
            val uBuffer = uPlane.buffer
            val uvRowStride = vPlane.rowStride
            val uvPixelStride = vPlane.pixelStride
            val uvWidth = width / 2
            val uvHeight = height / 2

            // Interleave V and U into NV21 format
            var uvPos = ySize
            for (row in 0 until uvHeight) {
                for (col in 0 until uvWidth) {
                    val uvIndex = row * uvRowStride + col * uvPixelStride
                    nv21[uvPos++] = vBuffer.get(uvIndex)
                    nv21[uvPos++] = uBuffer.get(uvIndex)
                }
            }

            val tCopyEnd = System.nanoTime()

            val yuvImage = YuvImage(
                nv21, ImageFormat.NV21, width, height, null
            )
            val out = ByteArrayOutputStream()
            val tJpegStart = System.nanoTime()
            yuvImage.compressToJpeg(
                Rect(0, 0, width, height), 100, out
            )
            val imageBytes = out.toByteArray()
            val tJpegEnd = System.nanoTime()

            val tDecodeStart = System.nanoTime()
            val bmp = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            val tDecodeEnd = System.nanoTime()

            val tEnd = System.nanoTime()

            Log.d(
                TAG,
                "Timing(YUV): copy=${((tCopyEnd - tCopyStart) / 1_000_000L)}ms, compress=${((tJpegEnd - tJpegStart) / 1_000_000L)}ms, decode=${((tDecodeEnd - tDecodeStart) / 1_000_000L)}ms, total=${((tEnd - tStart) / 1_000_000L)}ms, strides=[Y:${yRowStride}x${yPixelStride}, UV:${uvRowStride}x${uvPixelStride}]"
            )

            bmp
        } catch (e: Exception) {
            Log.e(TAG, "Error converting YUV to Bitmap", e)
            null
        }
    }

    private fun transformDetectionCoordinates(
        detection: Detection,
        bitmapWidth: Int,
        bitmapHeight: Int,
        displayWidth: Int,
        displayHeight: Int,
        rotationDegrees: Int
    ): Detection {
        // Portrait-only handling: analyzer receives sensor-native landscape frames.
        // We rotate detections to portrait display (rotationDegrees typically 90), then
        // scale.
        val norm = ((rotationDegrees % 360) + 360) % 360

        // Base size in display orientation after rotation
        val (baseW, baseH) = when (norm) {
            90, 270 -> bitmapHeight to bitmapWidth
            else -> bitmapWidth to bitmapHeight
        }

        fun rotateBox90(
            x: Float, y: Float, w: Float, h: Float
        ): link.sciber.foofinder.domain.BoundingBox {
            // 90° CW rotation into portrait display space
            val newX = baseW.toFloat() - (y + h)
            val newY = x
            return link.sciber.foofinder.domain.BoundingBox(
                startX = newX,
                startY = newY,
                width = h,
                height = w,
                confidence = 0f,
                classId = 0,
                className = "poo"
            )
        }

        fun rotateArea90(
            area: DetectionArea
        ): DetectionArea {
            val newX = baseW.toFloat() - (area.startY + area.height)
            val newY = area.startX
            return DetectionArea(
                startX = newX, startY = newY, width = area.height, height = area.width
            )
        }

        val rotatedBoxes = when (norm) {
            90 -> detection.boundingBoxes.map { b ->
                val rb = rotateBox90(
                    b.startX, b.startY, b.width, b.height
                )
                b.copy(
                    startX = rb.startX, startY = rb.startY, width = rb.width, height = rb.height
                )
            }

            0 -> detection.boundingBoxes
            180 -> detection.boundingBoxes.map { b ->
                val newX = baseW.toFloat() - (b.startX + b.width)
                val newY = baseH.toFloat() - (b.startY + b.height)
                b.copy(startX = newX, startY = newY)
            }

            270 -> detection.boundingBoxes.map { b ->
                val newX = b.startY
                val newY = baseH.toFloat() - (b.startX + b.width)
                b.copy(
                    startX = newX, startY = newY, width = b.height, height = b.width
                )
            }

            else -> detection.boundingBoxes
        }

        val rotatedArea = when (norm) {
            90 -> rotateArea90(detection.area)
            0 -> detection.area
            180 -> {
                val a = detection.area
                DetectionArea(
                    startX = baseW.toFloat() - (a.startX + a.width),
                    startY = baseH.toFloat() - (a.startY + a.height),
                    width = a.width,
                    height = a.height
                )
            }

            270 -> {
                val a = detection.area
                DetectionArea(
                    startX = a.startY,
                    startY = baseH.toFloat() - (a.startX + a.width),
                    width = a.height,
                    height = a.width
                )
            }

            else -> detection.area
        }

        val scaleX = displayWidth.toFloat() / baseW.toFloat()
        val scaleY = displayHeight.toFloat() / baseH.toFloat()

        Log.d(
            TAG,
            "transformDetectionCoordinates: rotationDegrees=$rotationDegrees, bitmap=${bitmapWidth}x${bitmapHeight}, base=${baseW}x${baseH}, display=${displayWidth}x${displayHeight}, scale=($scaleX,$scaleY)"
        )

        val scaledBoxes = rotatedBoxes.map { box ->
            box.copy(
                startX = box.startX * scaleX,
                startY = box.startY * scaleY,
                width = box.width * scaleX,
                height = box.height * scaleY
            )
        }

        val scaledArea = DetectionArea(
            startX = rotatedArea.startX * scaleX,
            startY = rotatedArea.startY * scaleY,
            width = rotatedArea.width * scaleX,
            height = rotatedArea.height * scaleY
        )

        // Preserve existing metrics (inferenceMs, fps, raw/afterNms counts) by copying
        return detection.copy(boundingBoxes = scaledBoxes, area = scaledArea)
    }
}

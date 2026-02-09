package link.sciber.foofinder.presentation

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.core.graphics.scale
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import link.sciber.foofinder.data.detection.DeePooDetector
import link.sciber.foofinder.domain.Detection
import link.sciber.foofinder.domain.DetectionArea

class CameraAnalyzer(
        private val detector: DeePooDetector,
        private val onDetectionResult: (Detection) -> Unit
) : ImageAnalysis.Analyzer {

    private val pendingTileCapture = AtomicReference<TileCaptureCallback?>(null)

    /** Scanning strategies for object detection. */
    enum class ScanStrategy {
        SCALED,
        CENTERED
    }

    data class TileCaptureResult(
            val bitmap: Bitmap,
            val area: DetectionArea,
            val rotationDegrees: Int,
            val timestampMs: Long
    )

    fun interface TileCaptureCallback {
        fun onTileCaptured(result: TileCaptureResult?)
    }

    fun requestTileCapture(callback: TileCaptureCallback): Boolean {
        return pendingTileCapture.compareAndSet(null, callback)
    }

    private var strategy = ScanStrategy.CENTERED

    companion object {
        private const val TAG = "CameraAnalyzer"
        /** Log detailed timing every N frames to cut logging overhead. */
        private const val LOG_EVERY_N = 30
    }

    // Rolling FPS window
    private val timestampWindowMs = 1500L
    private val timestamps = ArrayDeque<Long>()
    private var frameCount = 0L

    init {
        Log.d(TAG, "Analyzer created with detector")
    }

    /** Update scanning strategy at runtime. */
    fun setScanStrategy(newStrategy: ScanStrategy) {
        strategy = newStrategy
        Log.d(TAG, "Scan strategy set to $newStrategy")
    }

    override fun analyze(imageProxy: ImageProxy) {
        fun emptyDetectionResult() =
                Detection(
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

            val (displayWidth, displayHeight) =
                    if (rotationDegrees == 90 || rotationDegrees == 270) height to width
                    else width to height

            frameCount++
            val verbose = frameCount % LOG_EVERY_N == 0L

            if (verbose) {
                Log.d(
                        TAG,
                        "Sensor frame: ${width}x${height}, Display frame: ${displayWidth}x${displayHeight}, " +
                                "format: $format, rotation: ${rotationDegrees}°"
                )
            }

            val analyzeStart = System.nanoTime()
            val bitmap = imageProxy.toBitmap()

            val detectStart = System.nanoTime()
            val baseSide = min(bitmap.width, bitmap.height)
            val modelInputSize =
                    try {
                        detector.getModelInputSize().coerceAtLeast(1)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to get model input size, detector may be closed", e)
                        onDetectionResult(emptyDetectionResult())
                        bitmap.recycle()
                        return
                    }
            var detectionAreaUsed: DetectionArea? = null

            val detection =
                    try {
                        when (strategy) {
                            ScanStrategy.SCALED ->
                                    detector.detect(bitmap).also {
                                        detectionAreaUsed =
                                                DetectionArea(
                                                        0f,
                                                        0f,
                                                        baseSide.toFloat(),
                                                        baseSide.toFloat()
                                                )
                                    }
                            ScanStrategy.CENTERED -> {
                                val tileSize =
                                        detector.getModelInputSize().coerceAtMost(baseSide)
                                val area =
                                        computeCenterTileArea(
                                                baseStartX = 0,
                                                baseStartY = 0,
                                                baseSide = baseSide,
                                                tileSize = tileSize
                                        )

                                detector.detectInArea(bitmap, area).also {
                                    detectionAreaUsed = area
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Detection failed, detector may be closed", e)
                        onDetectionResult(emptyDetectionResult())
                        bitmap.recycle()
                        return
                    }

            val detectMs = (System.nanoTime() - detectStart) / 1_000_000L
            val transformedDetection =
                    transformDetectionCoordinates(
                            detection,
                            bitmap.width,
                            bitmap.height,
                            displayWidth,
                            displayHeight,
                            rotationDegrees
                    )

            val now = System.currentTimeMillis()
            timestamps.addLast(now)
            while (timestamps.isNotEmpty() && now - timestamps.first() > timestampWindowMs) {
                timestamps.removeFirst()
            }
            val elapsed = (timestamps.last() - timestamps.first()).coerceAtLeast(1)
            val fps = if (timestamps.size >= 2) (timestamps.size - 1) * 1000f / elapsed else 0f

            onDetectionResult(
                    transformedDetection.copy(
                            fps = fps,
                            afterNmsDetections = transformedDetection.boundingBoxes.size
                    )
            )

            if (verbose) {
                Log.d(
                        TAG,
                        "Detection completed: ${transformedDetection.boundingBoxes.size} objects detected"
                )
            }

            handlePendingTileCapture(
                    frameBitmap = bitmap,
                    area = detectionAreaUsed,
                    rotationDegrees = rotationDegrees,
                    modelInputSize = modelInputSize
            )

            val analyzeMs = (System.nanoTime() - analyzeStart) / 1_000_000L
            if (verbose) {
                Log.d(
                        TAG,
                        "Timing(analyze): detect=${detectMs}ms, total=${analyzeMs}ms"
                )
            }

            bitmap.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing image", e)
            onDetectionResult(emptyDetectionResult())
        } finally {
            imageProxy.close()
        }
    }

    private fun handlePendingTileCapture(
            frameBitmap: Bitmap,
            area: DetectionArea?,
            rotationDegrees: Int,
            modelInputSize: Int
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
                        TAG,
                        "Tile capture failed due to invalid crop size: ${cropWidth}x${cropHeight}"
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
                val rotated = Bitmap.createBitmap(tile, 0, 0, tile.width, tile.height, matrix, true)
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

    private fun computeCenterTileArea(
            baseStartX: Int,
            baseStartY: Int,
            baseSide: Int,
            tileSize: Int
    ): DetectionArea {
        val offset = ((baseSide - tileSize) / 2f).coerceAtLeast(0f)
        return DetectionArea(
                startX = baseStartX + offset,
                startY = baseStartY + offset,
                width = tileSize.toFloat(),
                height = tileSize.toFloat()
        )
    }

    // imageProxyToBitmap / yuvToBitmap removed — replaced by CameraX 1.4
    // built-in ImageProxy.toBitmap() which uses an optimised native path
    // (no JPEG round-trip).

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
        val (baseW, baseH) =
                when (norm) {
                    90, 270 -> bitmapHeight to bitmapWidth
                    else -> bitmapWidth to bitmapHeight
                }

        fun rotateBox90(
                x: Float,
                y: Float,
                w: Float,
                h: Float
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

        fun rotateArea90(area: DetectionArea): DetectionArea {
            val newX = baseW.toFloat() - (area.startY + area.height)
            val newY = area.startX
            return DetectionArea(
                    startX = newX,
                    startY = newY,
                    width = area.height,
                    height = area.width
            )
        }

        val rotatedBoxes =
                when (norm) {
                    90 ->
                            detection.boundingBoxes.map { b ->
                                val rb = rotateBox90(b.startX, b.startY, b.width, b.height)
                                b.copy(
                                        startX = rb.startX,
                                        startY = rb.startY,
                                        width = rb.width,
                                        height = rb.height
                                )
                            }
                    0 -> detection.boundingBoxes
                    180 ->
                            detection.boundingBoxes.map { b ->
                                val newX = baseW.toFloat() - (b.startX + b.width)
                                val newY = baseH.toFloat() - (b.startY + b.height)
                                b.copy(startX = newX, startY = newY)
                            }
                    270 ->
                            detection.boundingBoxes.map { b ->
                                val newX = b.startY
                                val newY = baseH.toFloat() - (b.startX + b.width)
                                b.copy(
                                        startX = newX,
                                        startY = newY,
                                        width = b.height,
                                        height = b.width
                                )
                            }
                    else -> detection.boundingBoxes
                }

        val rotatedArea =
                when (norm) {
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

        // Logging removed from hot path — use verbose flag in analyze() instead

        val scaledBoxes =
                rotatedBoxes.map { box ->
                    box.copy(
                            startX = box.startX * scaleX,
                            startY = box.startY * scaleY,
                            width = box.width * scaleX,
                            height = box.height * scaleY
                    )
                }

        val scaledArea =
                DetectionArea(
                        startX = rotatedArea.startX * scaleX,
                        startY = rotatedArea.startY * scaleY,
                        width = rotatedArea.width * scaleX,
                        height = rotatedArea.height * scaleY
                )

        // Preserve existing metrics (inferenceMs, fps, raw/afterNms counts) by copying
        return detection.copy(boundingBoxes = scaledBoxes, area = scaledArea)
    }
}

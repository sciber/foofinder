package link.sciber.foofinder.presentation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import link.sciber.foofinder.data.detection.FooDetector
import link.sciber.foofinder.domain.Detection
import link.sciber.foofinder.domain.DetectionArea

class CameraPreviewAnalyzer(
        private val detector: FooDetector,
        private val onDetectionResult: (Detection) -> Unit
) : ImageAnalysis.Analyzer {

        private var tileIndex: Int = 0

        private enum class ScanStrategy {
                SCALED_SINGLE,
                ROWS,
                COLUMNS,
                RANDOM
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

        override fun analyze(imageProxy: ImageProxy) {
                try {
                        // Get image properties
                        val width = imageProxy.width
                        val height = imageProxy.height
                        val format = imageProxy.format
                        val rotationDegrees = imageProxy.imageInfo.rotationDegrees

                        // Calculate display-oriented dimensions (what user sees in preview)
                        val displayWidth: Int
                        val displayHeight: Int

                        when (rotationDegrees) {
                                90, 270 -> {
                                        // Portrait mode: swap dimensions
                                        displayWidth = height
                                        displayHeight = width
                                }
                                else -> {
                                        // Landscape mode: keep dimensions
                                        displayWidth = width
                                        displayHeight = height
                                }
                        }

                        Log.d(
                                TAG,
                                "Sensor frame: ${width}x${height}, Display frame: ${displayWidth}x${displayHeight}, format: $format, rotation: ${rotationDegrees}°"
                        )

                        // Overall analyze timer
                        val tAnalyzeStart = System.nanoTime()

                        // Convert ImageProxy to Bitmap
                        val tConvStart = System.nanoTime()
                        val bitmap = imageProxyToBitmap(imageProxy)
                        val tConvEnd = System.nanoTime()
                        val convertMs = ((tConvEnd - tConvStart) / 1_000_000L)
                        Log.d(TAG, "Timing(analyze): convert=${convertMs}ms")

                        if (bitmap != null) {
                                // Run object detection
                                val tDetStart = System.nanoTime()

                                // Base square region anchored at top-left (0,0). If you want
                                // center, see variant
                                // below.
                                val baseSide = minOf(bitmap.width, bitmap.height)
                                val baseArea =
                                        link.sciber.foofinder.domain.DetectionArea(
                                                startX = 0f,
                                                startY = 0f,
                                                width = baseSide.toFloat(),
                                                height = baseSide.toFloat()
                                        )

                                // Run detection in the chosen tile
                                val detection =
                                        when (strategy) {
                                                ScanStrategy.SCALED_SINGLE -> {
                                                        // Current behavior preserved
                                                        detector.detect(bitmap)
                                                }
                                                ScanStrategy.ROWS, ScanStrategy.COLUMNS -> {
                                                        val baseSide =
                                                                min(bitmap.width, bitmap.height)
                                                        val baseStartX = 0
                                                        val baseStartY = 0

                                                        val tileSize =
                                                                detector.getModelInputSize()
                                                                        .coerceAtMost(baseSide)

                                                        val stepsX =
                                                                max(
                                                                        1,
                                                                        ceil(
                                                                                        baseSide.toFloat() /
                                                                                                tileSize
                                                                                )
                                                                                .toInt()
                                                                )
                                                        val stepsY =
                                                                max(
                                                                        1,
                                                                        ceil(
                                                                                        baseSide.toFloat() /
                                                                                                tileSize
                                                                                )
                                                                                .toInt()
                                                                )
                                                        val totalTiles = stepsX * stepsY

                                                        val area =
                                                                computeTileArea(
                                                                        baseStartX = baseStartX,
                                                                        baseStartY = baseStartY,
                                                                        baseSide = baseSide,
                                                                        tileSize = tileSize,
                                                                        index = tileIndex,
                                                                        strategy = strategy,
                                                                        rotationDegrees =
                                                                                rotationDegrees
                                                                )

                                                        // Advance tile for next frame
                                                        tileIndex =
                                                                if (totalTiles > 0)
                                                                        (tileIndex + 1) % totalTiles
                                                                else 0

                                                        detector.detectInArea(bitmap, area)
                                                }
                                                ScanStrategy.RANDOM -> {
                                                        val baseSide =
                                                                min(bitmap.width, bitmap.height)
                                                        val tileSize =
                                                                detector.getModelInputSize()
                                                                        .coerceAtMost(baseSide)
                                                        val stepsX =
                                                                max(
                                                                        1,
                                                                        ceil(
                                                                                        baseSide.toFloat() /
                                                                                                tileSize
                                                                                )
                                                                                .toInt()
                                                                )
                                                        val stepsY =
                                                                max(
                                                                        1,
                                                                        ceil(
                                                                                        baseSide.toFloat() /
                                                                                                tileSize
                                                                                )
                                                                                .toInt()
                                                                )
                                                        val totalTiles = stepsX * stepsY
                                                        val randomIndex =
                                                                if (totalTiles > 0)
                                                                        kotlin.random.Random
                                                                                .nextInt(totalTiles)
                                                                else 0

                                                        val area =
                                                                computeTileArea(
                                                                        baseStartX = 0,
                                                                        baseStartY = 0,
                                                                        baseSide = baseSide,
                                                                        tileSize = tileSize,
                                                                        index = randomIndex,
                                                                        strategy =
                                                                                ScanStrategy.RANDOM,
                                                                        rotationDegrees =
                                                                                rotationDegrees
                                                                )
                                                        detector.detectInArea(bitmap, area)
                                                }
                                        }

                                val tDetEnd = System.nanoTime()
                                val detectMs = ((tDetEnd - tDetStart) / 1_000_000L)

                                // Transform coordinates if needed based on rotation
                                val tXformStart = System.nanoTime()
                                val transformedDetection =
                                        transformDetectionCoordinates(
                                                detection,
                                                bitmap.width,
                                                bitmap.height,
                                                displayWidth,
                                                displayHeight,
                                                rotationDegrees
                                        )
                                val tXformEnd = System.nanoTime()
                                val transformMs = ((tXformEnd - tXformStart) / 1_000_000L)

                                // Compute FPS over a rolling window
                                val now = System.currentTimeMillis()
                                timestamps.addLast(now)
                                while (timestamps.isNotEmpty() &&
                                        now - timestamps.first() > timestampWindowMs) {
                                        timestamps.removeFirst()
                                }
                                val elapsed =
                                        (timestamps.last() - timestamps.first()).coerceAtLeast(1)
                                val fps =
                                        if (timestamps.size >= 2)
                                                (timestamps.size - 1) * 1000f / elapsed
                                        else 0f

                                // Callback with detection results
                                onDetectionResult(
                                        transformedDetection.copy(
                                                fps = fps,
                                                afterNmsDetections =
                                                        transformedDetection.boundingBoxes.size
                                        )
                                )

                                Log.d(
                                        TAG,
                                        "Detection completed: ${transformedDetection.boundingBoxes.size} objects detected"
                                )

                                val tAnalyzeEnd = System.nanoTime()
                                val totalAnalyzeMs = ((tAnalyzeEnd - tAnalyzeStart) / 1_000_000L)
                                Log.d(
                                        TAG,
                                        "Timing(analyze): detect=${detectMs}ms, transform=${transformMs}ms, total=${totalAnalyzeMs}ms"
                                )

                                // Clean up bitmap
                                bitmap.recycle()
                        } else {
                                Log.w(TAG, "Failed to convert ImageProxy to Bitmap")
                                onDetectionResult(
                                        Detection(
                                                boundingBoxes = emptyList(),
                                                area =
                                                        link.sciber.foofinder.domain.DetectionArea(
                                                                0f,
                                                                0f,
                                                                0f,
                                                                0f
                                                        ),
                                                inferenceMs = -1,
                                                fps = 0f,
                                                rawDetections = 0,
                                                afterNmsDetections = 0
                                        )
                                )
                        }
                } catch (e: Exception) {
                        Log.e(TAG, "Error analyzing image", e)
                        onDetectionResult(
                                Detection(
                                        boundingBoxes = emptyList(),
                                        area =
                                                link.sciber.foofinder.domain.DetectionArea(
                                                        0f,
                                                        0f,
                                                        0f,
                                                        0f
                                                ),
                                        inferenceMs = -1,
                                        fps = 0f,
                                        rawDetections = 0,
                                        afterNmsDetections = 0
                                )
                        )
                } finally {
                        // Always close the image to prevent memory leaks
                        imageProxy.close()
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
        ): link.sciber.foofinder.domain.DetectionArea {
                val stepsX = max(1, ceil(baseSide.toFloat() / tileSize).toInt())
                val stepsY = max(1, ceil(baseSide.toFloat() / tileSize).toInt())

                // Minimal uniform overlap to cover the base square fully
                val strideX = if (stepsX > 1) (baseSide - tileSize).toFloat() / (stepsX - 1) else 0f
                val strideY = if (stepsY > 1) (baseSide - tileSize).toFloat() / (stepsY - 1) else 0f

                if (strategy == ScanStrategy.RANDOM) {
                        val maxOffset = (baseSide - tileSize).coerceAtLeast(0)
                        val randomOffsetX =
                                if (maxOffset > 0) kotlin.random.Random.nextInt(maxOffset + 1)
                                else 0
                        val randomOffsetY =
                                if (maxOffset > 0) kotlin.random.Random.nextInt(maxOffset + 1)
                                else 0

                        val startX = baseStartX + randomOffsetX
                        val startY = baseStartY + randomOffsetY

                        return DetectionArea(
                                startX = startX.toFloat(),
                                startY = startY.toFloat(),
                                width = tileSize.toFloat(),
                                height = tileSize.toFloat()
                        )
                }

                val totalTiles = stepsX * stepsY
                val clampedIndex = if (totalTiles > 0) index % totalTiles else 0

                val isRotated = rotationDegrees == 90 || rotationDegrees == 270
                val displayCols = if (!isRotated) stepsX else stepsY
                val displayRows = if (!isRotated) stepsY else stepsX

                val (sensorColIndex, sensorRowIndex) =
                        when (strategy) {
                                ScanStrategy.ROWS -> {
                                        var displayCol = clampedIndex % displayCols
                                        val displayRow = clampedIndex / displayCols
                                        if (isRotated) {
                                                displayCol = displayCols - 1 - displayCol
                                        }
                                        if (!isRotated) {
                                                displayCol to displayRow
                                        } else {
                                                displayRow to displayCol
                                        }
                                }
                                ScanStrategy.COLUMNS -> {
                                        val displayRow = clampedIndex % displayRows
                                        var displayCol = clampedIndex / displayRows
                                        if (isRotated) {
                                                displayCol = displayCols - 1 - displayCol
                                        }
                                        if (!isRotated) {
                                                displayCol to displayRow
                                        } else {
                                                displayRow to displayCol
                                        }
                                }
                                ScanStrategy.SCALED_SINGLE -> 0 to 0
                                ScanStrategy.RANDOM ->
                                        0 to 0 // unreachable but keeps `when` exhaustive
                        }

                val x =
                        baseStartX +
                                min((sensorColIndex * strideX).roundToInt(), baseSide - tileSize)
                val y =
                        baseStartY +
                                min((sensorRowIndex * strideY).roundToInt(), baseSide - tileSize)

                return link.sciber.foofinder.domain.DetectionArea(
                        startX = x.toFloat(),
                        startY = y.toFloat(),
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
                                        val bmp =
                                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
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

                        val yBuffer = imageProxy.planes[0].buffer // Y
                        val uBuffer = imageProxy.planes[1].buffer // U
                        val vBuffer = imageProxy.planes[2].buffer // V

                        val ySize = yBuffer.remaining()
                        val uSize = uBuffer.remaining()
                        val vSize = vBuffer.remaining()

                        val nv21 = ByteArray(ySize + uSize + vSize)

                        val tCopyStart = System.nanoTime()
                        // U and V are swapped
                        yBuffer.get(nv21, 0, ySize)
                        vBuffer.get(nv21, ySize, vSize)
                        uBuffer.get(nv21, ySize + vSize, uSize)
                        val tCopyEnd = System.nanoTime()

                        val yuvImage =
                                YuvImage(
                                        nv21,
                                        ImageFormat.NV21,
                                        imageProxy.width,
                                        imageProxy.height,
                                        null
                                )
                        val out = ByteArrayOutputStream()
                        val tJpegStart = System.nanoTime()
                        yuvImage.compressToJpeg(
                                Rect(0, 0, imageProxy.width, imageProxy.height),
                                100,
                                out
                        )
                        val imageBytes = out.toByteArray()
                        val tJpegEnd = System.nanoTime()

                        val tDecodeStart = System.nanoTime()
                        val bmp = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                        val tDecodeEnd = System.nanoTime()

                        val tEnd = System.nanoTime()

                        Log.d(
                                TAG,
                                "Timing(YUV): copy=${((tCopyEnd - tCopyStart) / 1_000_000L)}ms, compress=${((tJpegEnd - tJpegStart) / 1_000_000L)}ms, decode=${((tDecodeEnd - tDecodeStart) / 1_000_000L)}ms, total=${((tEnd - tStart) / 1_000_000L)}ms"
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

                fun rotateArea90(
                        area: link.sciber.foofinder.domain.DetectionArea
                ): link.sciber.foofinder.domain.DetectionArea {
                        val newX = baseW.toFloat() - (area.startY + area.height)
                        val newY = area.startX
                        return link.sciber.foofinder.domain.DetectionArea(
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
                                                val rb =
                                                        rotateBox90(
                                                                b.startX,
                                                                b.startY,
                                                                b.width,
                                                                b.height
                                                        )
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
                                        link.sciber.foofinder.domain.DetectionArea(
                                                startX = baseW.toFloat() - (a.startX + a.width),
                                                startY = baseH.toFloat() - (a.startY + a.height),
                                                width = a.width,
                                                height = a.height
                                        )
                                }
                                270 -> {
                                        val a = detection.area
                                        link.sciber.foofinder.domain.DetectionArea(
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
                        link.sciber.foofinder.domain.DetectionArea(
                                startX = rotatedArea.startX * scaleX,
                                startY = rotatedArea.startY * scaleY,
                                width = rotatedArea.width * scaleX,
                                height = rotatedArea.height * scaleY
                        )

                // Preserve existing metrics (inferenceMs, fps, raw/afterNms counts) by copying
                return detection.copy(boundingBoxes = scaledBoxes, area = scaledArea)
        }
}

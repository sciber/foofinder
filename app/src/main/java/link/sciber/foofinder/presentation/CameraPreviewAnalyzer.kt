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
import link.sciber.foofinder.data.detection.FooDetector
import link.sciber.foofinder.domain.Detection

class CameraPreviewAnalyzer(
        private val detector: FooDetector,
        private val onDetectionResult: (Detection) -> Unit
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "CameraPreviewAnalyzer"
    }

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

            // Convert ImageProxy to Bitmap
            val bitmap = imageProxyToBitmap(imageProxy)

            if (bitmap != null) {
                // Run object detection
                val detection = detector.detect(bitmap)

                // Transform coordinates if needed based on rotation
                val transformedDetection =
                        transformDetectionCoordinates(
                                detection,
                                bitmap.width,
                                bitmap.height,
                                displayWidth,
                                displayHeight,
                                rotationDegrees
                        )

                // Callback with detection results
                onDetectionResult(transformedDetection)

                Log.d(
                        TAG,
                        "Detection completed: ${transformedDetection.boundingBoxes.size} objects detected"
                )

                // Clean up bitmap
                bitmap.recycle()
            } else {
                Log.w(TAG, "Failed to convert ImageProxy to Bitmap")
                onDetectionResult(
                        Detection(
                                emptyList(),
                                link.sciber.foofinder.domain.DetectionArea(0f, 0f, 0f, 0f)
                        )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing image", e)
            onDetectionResult(
                    Detection(
                            emptyList(),
                            link.sciber.foofinder.domain.DetectionArea(0f, 0f, 0f, 0f)
                    )
            )
        } finally {
            // Always close the image to prevent memory leaks
            imageProxy.close()
        }
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            when (imageProxy.format) {
                ImageFormat.YUV_420_888 -> {
                    // Convert YUV_420_888 to Bitmap
                    yuvToBitmap(imageProxy)
                }
                ImageFormat.JPEG -> {
                    // Convert JPEG to Bitmap
                    val buffer = imageProxy.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
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
            val yBuffer = imageProxy.planes[0].buffer // Y
            val uBuffer = imageProxy.planes[1].buffer // U
            val vBuffer = imageProxy.planes[2].buffer // V

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)

            // U and V are swapped
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage =
                    YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 100, out)
            val imageBytes = out.toByteArray()
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
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
        // We rotate detections to portrait display (rotationDegrees typically 90), then scale.
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

        return Detection(scaledBoxes, scaledArea)
    }
}

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
        // For now, return detection as-is since we're working with sensor coordinates
        // In the future, you might want to transform coordinates based on rotation
        // and coordinate system differences between analyzer and preview

        return when (rotationDegrees) {
            90, 270 -> {
                // If rotation is needed, transform coordinates here
                // For now, keeping original coordinates
                detection
            }
            else -> detection
        }
    }
}

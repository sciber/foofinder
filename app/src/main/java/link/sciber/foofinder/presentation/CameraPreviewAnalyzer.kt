package link.sciber.foofinder.presentation

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

class CameraPreviewAnalyzer : ImageAnalysis.Analyzer {

    init {
        Log.d("CameraPreviewAnalyzer", "Analyzer created")
    }

    override fun analyze(imageProxy: ImageProxy) {
        Log.d("CameraPreviewAnalyzer", "Analyzer called")
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
                    "CameraPreviewAnalyzer",
                    "Sensor frame: ${width}x${height}, Display frame: ${displayWidth}x${displayHeight}, format: $format, rotation: ${rotationDegrees}°"
            )

            // TODO: Add your image analysis logic here
            // Note: Use displayWidth/displayHeight if you want coordinates matching the preview
            // Use width/height if you want to work with raw sensor coordinates
            // For example:
            // - Object detection
            // - QR code scanning
            // - Image processing
            // - ML model inference

        } catch (e: Exception) {
            Log.e("CameraPreviewAnalyzer", "Error analyzing image", e)
        } finally {
            // Always close the image to prevent memory leaks
            imageProxy.close()
        }
    }
}

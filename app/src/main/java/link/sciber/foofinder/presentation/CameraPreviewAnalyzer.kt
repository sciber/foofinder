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

            Log.d(
                    "CameraPreviewAnalyzer",
                    "Frame: ${width}x${height}, format: $format, rotation: ${rotationDegrees}°"
            )

            // TODO: Add your image analysis logic here
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

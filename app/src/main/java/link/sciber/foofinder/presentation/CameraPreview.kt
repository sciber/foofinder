package link.sciber.foofinder.presentation

import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import link.sciber.foofinder.data.detection.FooDetector
import link.sciber.foofinder.domain.Detection

@Composable
fun CameraPreview(
        controller: LifecycleCameraController,
        currentResolution: Size?,
        onResolutionChange: (Size) -> Unit,
        currentDetection: Detection?,
        onDetectionResult: (Detection) -> Unit,
        modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var detector by remember { mutableStateOf<FooDetector?>(null) }

    // Initialize detector
    LaunchedEffect(Unit) {
        try {
            // Use the actual model file in assets
            detector = FooDetector(context, "deepoo-YOLO11n-quant.tflite")
            Log.d("CameraPreview", "FooDetector initialized successfully")
        } catch (e: Exception) {
            Log.e("CameraPreview", "Failed to initialize FooDetector", e)
        }
    }

    // Function to apply resolution using ProcessCameraProvider
    fun applyResolution(resolution: Size) {
        previewView?.let { preview ->
            detector?.let { det ->
                try {
                    Log.d(
                            "CameraPreview",
                            "Applying resolution: ${resolution.width}x${resolution.height}"
                    )

                    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                    cameraProviderFuture.addListener(
                            {
                                val cameraProvider = cameraProviderFuture.get()

                                // Unbind all use cases first
                                cameraProvider.unbindAll()

                                // Create preview use case with target resolution
                                val previewBuilder =
                                        Preview.Builder()
                                                .setTargetResolution(resolution)
                                                .setTargetRotation(
                                                        Surface.ROTATION_0
                                                ) // Portrait orientation

                                val previewUseCase = previewBuilder.build()

                                // Create image analysis use case with same target resolution
                                val imageAnalysisBuilder =
                                        ImageAnalysis.Builder()
                                                .setTargetResolution(resolution)
                                                .setTargetRotation(Surface.ROTATION_0)
                                                .setBackpressureStrategy(
                                                        ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                                                )

                                val imageAnalysisUseCase = imageAnalysisBuilder.build()

                                // Set up the analyzer with detector
                                val analyzer = CameraPreviewAnalyzer(det, onDetectionResult)
                                imageAnalysisUseCase.setAnalyzer(
                                        Executors.newSingleThreadExecutor(),
                                        analyzer
                                )

                                // Set surface provider for preview
                                previewUseCase.setSurfaceProvider(preview.surfaceProvider)

                                // Bind both use cases to lifecycle with back camera
                                val camera =
                                        cameraProvider.bindToLifecycle(
                                                lifecycleOwner,
                                                CameraSelector.DEFAULT_BACK_CAMERA,
                                                previewUseCase,
                                                imageAnalysisUseCase
                                        )

                                // Log actual resolution info
                                camera.cameraInfo.let { info ->
                                    Log.d(
                                            "CameraPreview",
                                            "Camera bound successfully with preview and analysis"
                                    )
                                }

                                onResolutionChange(resolution)
                            },
                            ContextCompat.getMainExecutor(context)
                    )
                } catch (e: Exception) {
                    Log.e("CameraPreview", "Error applying resolution", e)
                    e.printStackTrace()
                }
            }
        }
    }

    // Apply resolution when it changes from parent
    LaunchedEffect(currentResolution) {
        currentResolution?.let { resolution -> applyResolution(resolution) }
    }

    // Camera Preview with proper aspect ratio handling and detection overlay
    Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        currentResolution?.let { resolution ->
            val aspectRatio = resolution.width.toFloat() / resolution.height.toFloat()

            // Force recomposition when resolution changes
            key(resolution) {
                AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).apply {
                                // Use FIT_CENTER to preserve aspect ratio
                                scaleType = PreviewView.ScaleType.FIT_CENTER
                                previewView = this
                                Log.d(
                                        "CameraPreview",
                                        "PreviewView created with aspect ratio: $aspectRatio for resolution: ${resolution.width}x${resolution.height}"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxSize().aspectRatio(aspectRatio)
                )
            }

            // Detection Overlay
            currentDetection?.let { detection ->
                DetectionOverlay(
                        detection = detection,
                        sourceWidth = resolution.width,
                        sourceHeight = resolution.height,
                        modifier = Modifier.fillMaxSize().aspectRatio(aspectRatio)
                )
            }
        }
                ?: run {
                    // Fallback when no resolution is set
                    AndroidView(
                            factory = { ctx ->
                                PreviewView(ctx).apply {
                                    scaleType = PreviewView.ScaleType.FIT_CENTER
                                    previewView = this
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                    )
                }
    }
}

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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import link.sciber.foofinder.data.detection.Accelerator
import link.sciber.foofinder.data.detection.FooDetector
import link.sciber.foofinder.domain.Detection
import link.sciber.foofinder.domain.DetectionArea
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

@Composable
fun CameraPreview(
        controller: LifecycleCameraController,
        currentResolution: Size?,
        onResolutionChange: (Size) -> Unit,
        currentDetection: Detection?,
        onDetectionResult: (Detection) -> Unit,
        currentConfidenceThreshold: Float,
        currentMaxBoxes: Int,
        currentNmsEnabled: Boolean,
        modifier: Modifier = Modifier
) {
        val lifecycleOwner = LocalLifecycleOwner.current
        val context = LocalContext.current

        var previewView by remember { mutableStateOf<PreviewView?>(null) }
        var detector by remember { mutableStateOf<FooDetector?>(null) }

        // Initialize detector
        LaunchedEffect(Unit) {
                try {
                        detector =
                                FooDetector(
                                        context,
                                        "models/best_plain_float16.tflite",
                                        accelerator = Accelerator.GPU,
                                        numThreads = 4
                                )
                        detector?.setConfidenceThreshold(currentConfidenceThreshold)
                        detector?.setNmsEnabled(currentNmsEnabled)
                        Log.d("CameraPreview", "FooDetector initialized successfully")
                } catch (e: Exception) {
                        Log.e("CameraPreview", "Failed to initialize FooDetector", e)
                }
        }

        LaunchedEffect(currentConfidenceThreshold) {
                detector?.setConfidenceThreshold(currentConfidenceThreshold)
                Log.d("CameraPreview", "Applied confidence threshold: $currentConfidenceThreshold")
        }

        LaunchedEffect(currentNmsEnabled) {
                detector?.setNmsEnabled(currentNmsEnabled)
                Log.d("CameraPreview", "Applied NMS enabled: $currentNmsEnabled")
        }

        fun applyResolution(resolution: Size) {
                previewView?.let { preview ->
                        detector?.let { det ->
                                try {
                                        Log.d(
                                                "CameraPreview",
                                                "Applying resolution: ${resolution.width}x${resolution.height}"
                                        )

                                        val cameraProviderFuture =
                                                ProcessCameraProvider.getInstance(context)
                                        cameraProviderFuture.addListener(
                                                {
                                                        val cameraProvider =
                                                                cameraProviderFuture.get()

                                                        cameraProvider.unbindAll()

                                                        val previewBuilder =
                                                                Preview.Builder()
                                                                        .setTargetResolution(resolution)
                                                                        .setTargetRotation(Surface.ROTATION_0)

                                                        val previewUseCase = previewBuilder.build()

                                                        val imageAnalysisBuilder =
                                                                ImageAnalysis.Builder()
                                                                        .setTargetResolution(resolution)
                                                                        .setTargetRotation(Surface.ROTATION_0)
                                                                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

                                                        val imageAnalysisUseCase =
                                                                imageAnalysisBuilder.build()

                                                        val analyzer =
                                                                CameraPreviewAnalyzer(
                                                                        det,
                                                                        onDetectionResult
                                                                )
                                                        imageAnalysisUseCase.setAnalyzer(
                                                                Executors.newSingleThreadExecutor(),
                                                                analyzer
                                                        )

                                                        previewUseCase.setSurfaceProvider(
                                                                preview.surfaceProvider
                                                        )

                                                        cameraProvider.bindToLifecycle(
                                                                lifecycleOwner,
                                                                CameraSelector.DEFAULT_BACK_CAMERA,
                                                                previewUseCase,
                                                                imageAnalysisUseCase
                                                        )

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

        LaunchedEffect(currentResolution) {
                currentResolution?.let { resolution -> applyResolution(resolution) }
        }

        Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
                currentResolution?.let { resolution ->
                        val baseSidePx = min(resolution.width, resolution.height)

                        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                                val squareSize = minOf(maxWidth, maxHeight)

                                Box(
                                        modifier =
                                                Modifier.size(squareSize)
                                                        .clipToBounds()
                                                        .align(Alignment.Center)
                                ) {
                                        key(resolution) {
                                                AndroidView(
                                                        factory = { ctx ->
                                                                PreviewView(ctx).apply {
                                                                        scaleType = PreviewView.ScaleType.FILL_START
                                                                        previewView = this
                                                                        Log.d(
                                                                                "CameraPreview",
                                                                                "PreviewView created cropped to base area ${baseSidePx}px for resolution: ${resolution.width}x${resolution.height}"
                                                                        )
                                                                }
                                                        },
                                                        modifier = Modifier.fillMaxSize()
                                                )
                                        }

                                        currentDetection?.let { detection ->
                                                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                                                        val baseWidth = baseSidePx.toFloat()
                                                        val baseHeight = baseSidePx.toFloat()
                                                        val maxBoxes = currentMaxBoxes.coerceAtLeast(0)

                                                        val displayBoxes = detection.boundingBoxes.take(maxBoxes)

                                                        val adjustedBoxes =
                                                                displayBoxes.map { box ->
                                                                        val left = box.startX.coerceIn(0f, baseWidth)
                                                                        val top = box.startY.coerceIn(0f, baseHeight)
                                                                        val right = (box.startX + box.width).coerceIn(0f, baseWidth)
                                                                        val bottom = (box.startY + box.height).coerceIn(0f, baseHeight)
                                                                        box.copy(
                                                                                startX = left,
                                                                                startY = top,
                                                                                width = max(0f, right - left),
                                                                                height = max(0f, bottom - top)
                                                                        )
                                                                }

                                                        val adjustedArea = detection.area.let { area ->
                                                                val left = area.startX.coerceIn(0f, baseWidth)
                                                                val top = area.startY.coerceIn(0f, baseHeight)
                                                                val right = (area.startX + area.width).coerceIn(0f, baseWidth)
                                                                val bottom = (area.startY + area.height).coerceIn(0f, baseHeight)
                                                                DetectionArea(
                                                                        startX = left,
                                                                        startY = top,
                                                                        width = max(0f, right - left),
                                                                        height = max(0f, bottom - top)
                                                                )
                                                        }

                                                        val adjustedDetection =
                                                                detection.copy(
                                                                        boundingBoxes = adjustedBoxes,
                                                                        area = adjustedArea
                                                                )

                                                        DetectionOverlay(
                                                                detection = adjustedDetection,
                                                                sourceWidth = baseSidePx,
                                                                sourceHeight = baseSidePx,
                                                                modifier = Modifier.fillMaxSize()
                                                        )

                                                        val fracBelowY =
                                                                ((adjustedArea.startY + adjustedArea.height) /
                                                                                baseHeight)
                                                                        .coerceIn(0f, 1f)
                                                        val offsetY = (maxHeight * fracBelowY) + 8.dp

                                                        androidx.compose.material3.Card(
                                                                modifier =
                                                                        Modifier.align(Alignment.TopEnd)
                                                                                .padding(end = 8.dp)
                                                                                .offset(y = offsetY),
                                                                colors =
                                                                        androidx.compose.material3
                                                                                .CardDefaults.cardColors(
                                                                                        containerColor =
                                                                                                Color.Black.copy(
                                                                                                        alpha = 0.7f
                                                                                                )
                                                                                )
                                                        ) {
                                                                val filteredOut =
                                                                        (detection.rawDetections -
                                                                                        detection.afterNmsDetections)
                                                                                .coerceAtLeast(0)
                                                                val fpsText =
                                                                        if (detection.fps >= 0f)
                                                                                String.format(
                                                                                        "%.1f",
                                                                                        detection.fps
                                                                                )
                                                                        else "-"
                                                                val infText =
                                                                        if (detection.inferenceMs >= 0)
                                                                                "${detection.inferenceMs} ms"
                                                                        else "-"
                                                                val delegateText =
                                                                        detector?.activeDelegateLabel() ?: "-"
                                                                val content =
                                                                        """
                            FPS: $fpsText
                            Inference: $infText
                            Objects: ${detection.afterNmsDetections} (kept)
                            NMS filtered/raw: $filteredOut/${detection.rawDetections}
                            Delegate: $delegateText
                        """.trimIndent()

                                                                androidx.compose.material3.Text(
                                                                        text = content,
                                                                        color = Color.White,
                                                                        style =
                                                                                androidx.compose.material3
                                                                                        .MaterialTheme.typography
                                                                                        .bodyMedium,
                                                                        modifier = Modifier.padding(8.dp)
                                                                )
                                                        }
                                                }
                                        }
                                }
                        }
                }
                        ?: run {
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

package link.sciber.foofinder.presentation

import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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

typealias TileCaptureRequester = (CameraAnalyzer.TileCaptureCallback) -> Boolean

@Composable
fun CameraPreview(
    currentResolution: Size?,
    onResolutionChange: (Size) -> Unit,
    currentDetection: Detection?,
    onDetectionResult: (Detection) -> Unit,
    currentConfidenceThreshold: Float,
    currentMaxBoxes: Int,
    currentNmsEnabled: Boolean,
    modifier: Modifier = Modifier,
    currentScanStrategy: CameraAnalyzer.ScanStrategy = CameraAnalyzer.ScanStrategy.CENTERED,
    modelId: String = "models/best_plain_float16.tflite",
    onCameraReady: (Camera?) -> Unit = {},
    onScanStrategyAutoChange: (CameraAnalyzer.ScanStrategy) -> Unit = {},
    onScanStrategyConstraintChange: (Boolean) -> Unit = {},
    onTileCaptureRequesterChange: (TileCaptureRequester?) -> Unit = {}
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var detector by remember { mutableStateOf<FooDetector?>(null) }
    var analyzer by remember { mutableStateOf<CameraAnalyzer?>(null) }

    LaunchedEffect(analyzer) {
        val requester: TileCaptureRequester? = analyzer?.let { instance ->
            { callback: CameraAnalyzer.TileCaptureCallback ->
                instance.requestTileCapture(callback)
            }
        }
        onTileCaptureRequesterChange(requester)
    }

    // Compute the center tile area in the same way as the analyzer
    fun computeCenterTileArea(baseSide: Int, tileSize: Int): DetectionArea {
        val offset = ((baseSide - tileSize) / 2f).coerceAtLeast(0f)
        return DetectionArea(
            startX = offset,
            startY = offset,
            width = tileSize.toFloat(),
            height = tileSize.toFloat()
        )
    }

    fun requiresScaledSingle(det: FooDetector?, resolution: Size?): Boolean {
        val tileSize = det?.getModelInputSize() ?: return false
        val target = resolution ?: return false
        return min(target.width, target.height) < tileSize
    }

    fun resolveStrategy(
        requested: CameraAnalyzer.ScanStrategy,
        det: FooDetector?,
        resolution: Size?,
        notify: Boolean
    ): CameraAnalyzer.ScanStrategy {
        val needsScaledSingle = requiresScaledSingle(det, resolution)
        if (notify) {
            onScanStrategyConstraintChange(needsScaledSingle)
        }
        return if (needsScaledSingle && requested != CameraAnalyzer.ScanStrategy.SCALED) {
            if (notify) {
                onScanStrategyAutoChange(
                    CameraAnalyzer.ScanStrategy.SCALED
                )
            }
            CameraAnalyzer.ScanStrategy.SCALED
        } else {
            requested
        }
    }

    fun applyResolution(resolution: Size) {
        previewView?.let { preview ->
            detector?.let { det ->
                try {
                    Log.d(
                        "CameraPreview",
                        "Applying resolution: ${resolution.width}x${resolution.height}"
                    )

                    val effectiveStrategy = resolveStrategy(
                        currentScanStrategy, det, resolution, notify = true
                    )

                    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                    cameraProviderFuture.addListener(
                        {
                            val cameraProvider = cameraProviderFuture.get()

                            cameraProvider.unbindAll()
                            onCameraReady(null)

                            val previewBuilder = Preview.Builder().setTargetResolution(
                                resolution
                            ).setTargetRotation(
                                Surface.ROTATION_0
                            )

                            val previewUseCase = previewBuilder.build()

                            val imageAnalysisBuilder = ImageAnalysis.Builder().setTargetResolution(
                                resolution
                            ).setTargetRotation(
                                Surface.ROTATION_0
                            ).setBackpressureStrategy(
                                ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                            )

                            val imageAnalysisUseCase = imageAnalysisBuilder.build()

                            val createdAnalyzer = CameraAnalyzer(
                                det, onDetectionResult
                            ).also {
                                it.setScanStrategy(
                                    effectiveStrategy
                                )
                            }
                            analyzer = createdAnalyzer
                            imageAnalysisUseCase.setAnalyzer(
                                Executors.newSingleThreadExecutor(), createdAnalyzer
                            )

                            previewUseCase.surfaceProvider = preview.surfaceProvider
                            val camera = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                previewUseCase,
                                imageAnalysisUseCase
                            )

                            onResolutionChange(resolution)
                            onCameraReady(camera)
                        }, ContextCompat.getMainExecutor(context)
                    )
                } catch (e: Exception) {
                    Log.e("CameraPreview", "Error applying resolution", e)
                }
            }
        }
    }

    // Initialize detector when model changes
    LaunchedEffect(modelId) {
        detector?.close()
        detector = null
        try {
            val newDetector = FooDetector(
                context, modelPath = modelId, accelerator = Accelerator.GPU, numThreads = 4
            ).also {
                it.setConfidenceThreshold(
                    currentConfidenceThreshold
                )
                it.setNmsEnabled(currentNmsEnabled)
            }
            detector = newDetector
            Log.d("CameraPreview", "FooDetector initialized with model: $modelId")
            currentResolution?.let { applyResolution(it) }
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

    LaunchedEffect(currentResolution) {
        currentResolution?.let { resolution -> applyResolution(resolution) }
    }

    LaunchedEffect(currentScanStrategy, detector, currentResolution) {
        val effective = resolveStrategy(
            currentScanStrategy, detector, currentResolution, notify = true
        )
        analyzer?.setScanStrategy(effective)
    }

    DisposableEffect(Unit) {
        onDispose {
            onTileCaptureRequesterChange(null)
            analyzer = null
            detector?.close()
            detector = null
            onCameraReady(null)
        }
    }

    Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        currentResolution?.let { resolution ->
            val baseSidePx = min(resolution.width, resolution.height)

            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val squareSize = minOf(maxWidth, maxHeight)
                val squareSizePx = with(LocalDensity.current) { squareSize.toPx() }

                // For CENTERED strategy, compute the static center tile area
                val isCenteredStrategy = currentScanStrategy == CameraAnalyzer.ScanStrategy.CENTERED
                val centerTileArea = if (isCenteredStrategy) {
                    val currentDetector = detector
                    if (currentDetector != null) {
                        try {
                            val modelInputSize = currentDetector.getModelInputSize()
                            val tileSize = modelInputSize.coerceAtMost(baseSidePx)
                            computeCenterTileArea(baseSidePx, tileSize)
                        } catch (e: Exception) {
                            null
                        }
                    } else {
                        null
                    }
                } else {
                    null
                }
                
                val (previewScale, previewOffsetX, previewOffsetY) = if (centerTileArea != null) {
                    // Calculate how much to zoom and pan to show only the center tile area
                    // Work in display pixels, then convert offset to DP
                    val pxPerBasePx = squareSizePx / baseSidePx.toFloat()
                    val scale = baseSidePx.toFloat() / centerTileArea.width
                    
                    // Calculate offset in pixels relative to the base square
                    val offsetPx = -centerTileArea.startX * pxPerBasePx * scale
                    val offsetPy = -centerTileArea.startY * pxPerBasePx * scale
                    
                    Log.d("CameraPreview", "CENTERED mode: baseSide=$baseSidePx, squarePx=$squareSizePx, tile=${centerTileArea.width.toInt()}x${centerTileArea.height.toInt()} at (${centerTileArea.startX.toInt()}, ${centerTileArea.startY.toInt()}), scale=$scale, offset=($offsetPx, $offsetPy)")
                    
                    Triple(scale, offsetPx, offsetPy)
                } else {
                    Triple(1f, 0f, 0f)
                }
                
                Box(
                    modifier = Modifier
                        .size(squareSize)
                        .align(Alignment.Center)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clipToBounds()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = previewScale,
                                    scaleY = previewScale,
                                    translationX = previewOffsetX,
                                    translationY = previewOffsetY,
                                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                                )
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
                                    }, modifier = Modifier.fillMaxSize()
                                )
                            }

                            currentDetection?.let { detection ->
                                Box(modifier = Modifier.fillMaxSize()) {
                            // Use original coordinates from analyzer - graphicsLayer will handle transformation
                            val baseWidth = baseSidePx.toFloat()
                            val baseHeight = baseSidePx.toFloat()
                            val maxBoxes = currentMaxBoxes.coerceAtLeast(0)

                            val displayBoxes = detection.boundingBoxes.take(maxBoxes)

                            val adjustedBoxes = displayBoxes.map { box ->
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

                            val adjustedDetection = detection.copy(
                                boundingBoxes = adjustedBoxes, area = adjustedArea
                            )

                            DetectionOverlay(
                                detection = adjustedDetection,
                                sourceWidth = baseSidePx,
                                sourceHeight = baseSidePx,
                                modifier = Modifier.fillMaxSize()
                            )

                            // Overlay-only stats card removed; InfoBar
                            // handles display below the preview.
                        }
                    }
                        }
                    }
                }
            }
        } ?: run {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FIT_CENTER
                        previewView = this
                    }
                }, modifier = Modifier.fillMaxSize()
            )
        }
    }
}

package link.sciber.foofinder.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.TorchState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import link.sciber.foofinder.R
import link.sciber.foofinder.data.detection.Accelerator
import link.sciber.foofinder.domain.Detection
import link.sciber.foofinder.presentation.components.DetectorInfoBar
import link.sciber.foofinder.presentation.components.DetectorSettingsSheet
import link.sciber.foofinder.presentation.components.DetectorTopBar
import link.sciber.foofinder.utils.CameraResolutionUtils
import link.sciber.foofinder.utils.ImageStorageManager

private const val TAG = "DetectorScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetectorScreen(
        onNavigateToImageViewer: (imageUri: String, fileName: String) -> Unit = { _, _ -> },
        onNavigateToDataset: () -> Unit = {}
) {
    val context = LocalContext.current

    var availableResolutions by remember { mutableStateOf<List<Size>>(emptyList()) }
    var currentCamera by remember { mutableStateOf<Camera?>(null) }
    var isCameraReady by remember { mutableStateOf(false) }
    var isTorchAvailable by remember { mutableStateOf(false) }
    var isTorchEnabled by remember { mutableStateOf(false) }
    var scanStrategyConstrained by remember { mutableStateOf(false) }

    val settingsViewModel: CameraSettingsViewModel = viewModel()
    val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()

    val currentResolution = settingsState.resolution
    val currentModelId = settingsState.modelId
    val currentScanStrategy = settingsState.scanStrategy
    val savedTorchEnabled = settingsState.torchEnabled

    val modelOptions = remember {
        listOf(
                "models/deepoo_yolov4_tiny_416_int8.tflite" to "YOLOv4-Tiny 416 INT8",
        )
    }

    val currentConfidenceThreshold = settingsState.confidenceThreshold
    val currentNmsEnabled = settingsState.nmsEnabled
    val currentMaxBoxes = settingsState.maxBoxes
    val currentAccelerator = settingsState.accelerator
    // Detection results
    var currentDetection by remember { mutableStateOf<Detection?>(null) }
    var currentTileSize by remember { mutableStateOf<Int?>(null) }
    var currentDelegate by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var tileCaptureRequester by remember { mutableStateOf<TileCaptureRequester?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var shouldCaptureAfterPermission by remember { mutableStateOf(false) }
    var hasLegacyWritePermission by remember {
        mutableStateOf(
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
                        ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                        ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val writePermission = Manifest.permission.WRITE_EXTERNAL_STORAGE

    fun canWriteToGallery(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || hasLegacyWritePermission

    fun startTileCapture() {
        shouldCaptureAfterPermission = false
        val requester = tileCaptureRequester
        if (requester == null) {
            scope.launch {
                snackbarHostState.showSnackbar(
                        message = "Analyzer not ready yet. Please try again shortly."
                )
            }
            return
        }

        isSaving = true
        val requested =
                requester.invoke { result ->
                    scope.launch {
                        if (result == null) {
                            isSaving = false
                            snackbarHostState.showSnackbar(
                                    message = "No detection tile available to save yet."
                            )
                            return@launch
                        }

                        val outcome =
                                try {
                                    withContext(Dispatchers.IO) {
                                        ImageStorageManager.saveDetectionTile(
                                                context,
                                                result.bitmap
                                        )
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to save detection tile", e)
                                    snackbarHostState.showSnackbar(
                                            message =
                                                    "Failed to save image: ${e.localizedMessage ?: "unknown error"}"
                                    )
                                    null
                                }

                        outcome?.let { saveOutcome ->
                            // Navigate to image viewer screen
                            onNavigateToImageViewer(
                                    saveOutcome.uri.toString(),
                                    saveOutcome.displayName
                            )
                        }

                        isSaving = false
                    }
                }

        if (!requested) {
            isSaving = false
            scope.launch {
                snackbarHostState.showSnackbar(message = "Capture already pending, please wait.")
            }
        }
    }

    val requestPermissionLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted
                ->
                hasLegacyWritePermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || granted
                if (granted) {
                    if (shouldCaptureAfterPermission) {
                        startTileCapture()
                    }
                } else {
                    shouldCaptureAfterPermission = false
                    scope.launch {
                        snackbarHostState.showSnackbar(
                                message = "Storage permission denied. Cannot save images."
                        )
                    }
                }
            }

    fun handleSnapshotRequest() {
        if (isSaving) {
            scope.launch {
                snackbarHostState.showSnackbar(message = "Image save already in progress.")
            }
            return
        }

        if (!canWriteToGallery()) {
            shouldCaptureAfterPermission = true
            requestPermissionLauncher.launch(writePermission)
            return
        }

        startTileCapture()
    }

    // Initialize resolutions when screen is first created
    LaunchedEffect(Unit) {
        val resolutions = CameraResolutionUtils.getAvailableResolutions(context)
        availableResolutions = CameraResolutionUtils.sortResolutionsByWidth(resolutions)
    }

    LaunchedEffect(availableResolutions, currentResolution) {
        if (availableResolutions.isEmpty()) return@LaunchedEffect

        val persisted = currentResolution
        val targetResolution =
                persisted?.let { saved -> availableResolutions.find { it == saved } }
                        ?: CameraResolutionUtils.findBestDefaultResolution(availableResolutions)

        if (targetResolution != null && targetResolution != persisted) {
            settingsViewModel.onResolutionChanged(targetResolution)
        }
    }

    val navInsets = WindowInsets.navigationBars.asPaddingValues()
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }

    LaunchedEffect(currentCamera) {
        val hasFlash = currentCamera?.cameraInfo?.hasFlashUnit() == true
        isTorchAvailable = hasFlash
        if (!hasFlash) {
            isTorchEnabled = false
        }
    }

    // Restore saved torch state when camera is ready
    LaunchedEffect(currentCamera, isTorchAvailable, savedTorchEnabled) {
        val cam = currentCamera
        if (cam != null && isTorchAvailable && savedTorchEnabled && !isTorchEnabled) {
            cam.cameraControl.enableTorch(true).addListener(Runnable {}, mainExecutor)
        }
    }

    DisposableEffect(currentCamera) {
        val cam = currentCamera
        if (cam == null) {
            onDispose {}
        } else {
            val torchObserver = Observer<Int> { state -> isTorchEnabled = state == TorchState.ON }
            cam.cameraInfo.torchState.observeForever(torchObserver)
            onDispose { cam.cameraInfo.torchState.removeObserver(torchObserver) }
        }
    }

    val toggleTorch = {
        val cam = currentCamera
        if (cam != null && isTorchAvailable) {
            val desiredState = !isTorchEnabled
            cam.cameraControl.enableTorch(desiredState).addListener(Runnable {}, mainExecutor)
            settingsViewModel.onTorchEnabledChanged(desiredState)
        }
    }

    val sheetScrollState = rememberScrollState()

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenHeight = maxHeight
        val topBarHeight = 56.dp // DetectorTopBar approximate height

        // Maximum sheet expansion should align with top bar's top edge
        val maxSheetHeight = screenHeight - topBarHeight

        // Two-state bottom sheet: Collapsed (peek) and Expanded (to top bar)
        val bottomSheetState =
                androidx.compose.material3.rememberBottomSheetScaffoldState(
                        bottomSheetState =
                                rememberStandardBottomSheetState(
                                        initialValue = SheetValue.PartiallyExpanded,
                                        skipHiddenState = true
                                )
                )

        BottomSheetScaffold(
                scaffoldState = bottomSheetState,
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                sheetPeekHeight = 92.dp,
                sheetContent = {
                    // Single scrollable content area with height constraint
                    Column(
                            modifier =
                                    Modifier.fillMaxWidth()
                                            .heightIn(max = maxSheetHeight)
                                            .verticalScroll(sheetScrollState)
                                            .padding(horizontal = 16.dp)
                                            .padding(
                                                    top = 16.dp,
                                                    bottom =
                                                            navInsets.calculateBottomPadding() +
                                                                    16.dp
                                            ),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        DetectorInfoBar(
                                currentDetection = currentDetection,
                                modifier = Modifier.fillMaxWidth(),
                                currentTileSize = currentTileSize,
                                delegateDescription = currentDelegate,
                        )

                        DetectorSettingsSheet(
                                currentConfidenceThreshold = currentConfidenceThreshold,
                                currentMaxBoxes = currentMaxBoxes,
                                currentNmsEnabled = currentNmsEnabled,
                                currentScanStrategy = currentScanStrategy,
                                scanStrategyConstrained = scanStrategyConstrained,
                                availableResolutions = availableResolutions,
                                currentResolution = currentResolution,
                                modelOptions = modelOptions,
                                currentModelId = currentModelId,
                                currentTileSize = currentTileSize,
                                currentAccelerator = currentAccelerator,
                                onConfidenceThresholdChanged =
                                        settingsViewModel::onConfidenceThresholdChanged,
                                onMaxBoxesChanged = settingsViewModel::onMaxBoxesChanged,
                                onNmsEnabledChanged = settingsViewModel::onNmsEnabledChanged,
                                onScanStrategyChanged = settingsViewModel::onScanStrategyChanged,
                                onResolutionChanged = settingsViewModel::onResolutionChanged,
                                onModelChanged = settingsViewModel::onModelChanged,
                                onAcceleratorChanged = settingsViewModel::onAcceleratorChanged
                        )
                    }
                }
        ) { paddingValues ->
            Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                DetectorTopBar(
                        isTorchEnabled = isTorchEnabled,
                        isTorchAvailable = isTorchAvailable,
                        onToggleTorch = toggleTorch,
                        onDatasetClick = onNavigateToDataset
                )

                Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
                    CameraPreview(
                            currentResolution = currentResolution,
                            onResolutionChange = { resolution ->
                                settingsViewModel.onResolutionChanged(resolution)
                            },
                            currentDetection = currentDetection,
                            onDetectionResult = { detection -> currentDetection = detection },
                            currentConfidenceThreshold = currentConfidenceThreshold,
                            currentMaxBoxes = currentMaxBoxes,
                            currentNmsEnabled = currentNmsEnabled,
                            modifier = Modifier.fillMaxSize(),
                            currentScanStrategy = currentScanStrategy,
                            modelId = currentModelId,
                            accelerator = currentAccelerator,
                            onTileSizeChanged = { size -> currentTileSize = size },
                            onDelegateChanged = { desc -> currentDelegate = desc },
                            onCameraReady = { camera ->
                                currentCamera = camera
                                isCameraReady = true
                            },
                            onScanStrategyAutoChange = { enforced ->
                                if (enforced != currentScanStrategy) {
                                    settingsViewModel.onScanStrategyChanged(enforced)
                                }
                            },
                            onScanStrategyConstraintChange = { constrained ->
                                scanStrategyConstrained = constrained
                            },
                            onTileCaptureRequesterChange = { requester ->
                                tileCaptureRequester = requester
                            }
                    )

                    // Loading indicator while camera is initializing
                    if (!isCameraReady) {
                        Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                    modifier = Modifier.size(48.dp),
                                    color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // Spacer above FAB
                Spacer(modifier = Modifier.weight(1f))

                // Snapshot FAB - centered between camera preview and bottom sheet
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    FloatingActionButton(
                            onClick = { handleSnapshotRequest() },
                            containerColor = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(64.dp)
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(
                                    painter = painterResource(id = R.drawable.camera_24),
                                    contentDescription = "Save analyzed image"
                            )
                        }
                    }
                }

                // Spacer below FAB
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

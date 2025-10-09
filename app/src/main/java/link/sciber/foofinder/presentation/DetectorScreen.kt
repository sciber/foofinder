package link.sciber.foofinder.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.TorchState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.rememberBottomSheetScaffoldState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import link.sciber.foofinder.domain.Detection
import link.sciber.foofinder.presentation.CameraAnalyzer.TileCaptureResult
import link.sciber.foofinder.presentation.components.DetectorInfoBar
import link.sciber.foofinder.presentation.components.DetectorSettingsSheet
import link.sciber.foofinder.presentation.components.DetectorTopBar
import link.sciber.foofinder.utils.CameraResolutionUtils
import link.sciber.foofinder.utils.ImageStorageManager
import link.sciber.foofinder.utils.SaveOutcome

private const val TAG = "DetectorScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetectorScreen() {
    val context = LocalContext.current

    var availableResolutions by remember { mutableStateOf<List<Size>>(emptyList()) }
    var currentCamera by remember { mutableStateOf<Camera?>(null) }
    var isTorchAvailable by remember { mutableStateOf(false) }
    var isTorchEnabled by remember { mutableStateOf(false) }
    var scanStrategyConstrained by remember { mutableStateOf(false) }

    val settingsViewModel: CameraSettingsViewModel = viewModel()
    val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()

    val currentResolution = settingsState.resolution
    val currentModelId = settingsState.modelId
    val currentScanStrategy = settingsState.scanStrategy

    val modelOptions = remember {
        listOf("models/best_plain_float16.tflite" to "DeePoo YOLOX Nano")
    }

    val currentConfidenceThreshold = settingsState.confidenceThreshold
    val currentNmsEnabled = settingsState.nmsEnabled
    val currentMaxBoxes = settingsState.maxBoxes
    // Detection results
    var currentDetection by remember { mutableStateOf<Detection?>(null) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var tileCaptureRequester by remember { mutableStateOf<TileCaptureRequester?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var shouldCaptureAfterPermission by remember { mutableStateOf(false) }
    var hasLegacyWritePermission by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var lastSavedMessage by remember { mutableStateOf<String?>(null) }
    var lastSavedUri by remember { mutableStateOf<Uri?>(null) }

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
        val requested = requester.invoke { result ->
            scope.launch {
                if (result == null) {
                    isSaving = false
                    snackbarHostState.showSnackbar(
                        message = "No detection tile available to save yet."
                    )
                    return@launch
                }

                val outcome = try {
                    withContext(Dispatchers.IO) {
                        ImageStorageManager.saveDetectionTile(context, result.bitmap)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save detection tile", e)
                    snackbarHostState.showSnackbar(
                        message = "Failed to save image: ${e.localizedMessage ?: "unknown error"}"
                    )
                    null
                }

                outcome?.let { saveOutcome ->
                    lastSavedUri = saveOutcome.uri
                    val summaryMessage =
                        "Saved ${saveOutcome.displayName} to ${saveOutcome.locationDescription}"
                    lastSavedMessage = summaryMessage
                    val snackbarResult = snackbarHostState.showSnackbar(
                        message = summaryMessage, actionLabel = "Open", withDismissAction = true
                    )
                    if (snackbarResult == SnackbarResult.ActionPerformed) {
                        ImageStorageManager.openImagePreview(context, saveOutcome.uri)
                    }
                }

                isSaving = false
            }
        }

        if (!requested) {
            isSaving = false
            scope.launch {
                snackbarHostState.showSnackbar(
                    message = "Capture already pending, please wait."
                )
            }
        }
    }

    val requestPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
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
                snackbarHostState.showSnackbar(
                    message = "Image save already in progress."
                )
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
        val targetResolution = persisted?.let { saved -> availableResolutions.find { it == saved } }
            ?: CameraResolutionUtils.findBestDefaultResolution(
                availableResolutions
            )

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
        }
    }

    val sheetScrollState = rememberScrollState()
    val sheetState = rememberBottomSheetScaffoldState()

    BottomSheetScaffold(
        scaffoldState = sheetState,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        sheetPeekHeight = 180.dp,
        sheetContainerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.98f),
        sheetDragHandle = {
            Column(
                modifier = Modifier.padding(top = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                            .height(4.dp)
                            .width(32.dp)
                            .clip(RoundedCornerShape(50))
                            .background(
                                    MaterialTheme.colorScheme.outlineVariant.copy(
                                            alpha = 0.6f
                                    )
                            )
                )
            }
        },
        sheetContent = {
            Column(
                modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(sheetScrollState)
                        .padding(top = 8.dp)
                        .padding(horizontal = 16.dp)
                        .padding(
                            bottom = navInsets.calculateBottomPadding() + 16.dp
                        ), verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                val openLastSaved = lastSavedUri?.let { uri ->
                    { ImageStorageManager.openImagePreview(context, uri) }
                }

                DetectorInfoBar(
                    currentDetection = currentDetection,
                    modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    isSaving = isSaving,
                    lastSavedMessage = lastSavedMessage,
                    onSnapshotClick = { handleSnapshotRequest() },
                    onOpenLastSaved = openLastSaved
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
                    onConfidenceThresholdChanged = settingsViewModel::onConfidenceThresholdChanged,
                    onMaxBoxesChanged = settingsViewModel::onMaxBoxesChanged,
                    onNmsEnabledChanged = settingsViewModel::onNmsEnabledChanged,
                    onScanStrategyChanged = settingsViewModel::onScanStrategyChanged,
                    onResolutionChanged = settingsViewModel::onResolutionChanged,
                    onModelChanged = settingsViewModel::onModelChanged
                )
            }
        }) { paddingValues ->
        Column(modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)) {
            DetectorTopBar(
                isTorchEnabled = isTorchEnabled,
                isTorchAvailable = isTorchAvailable,
                onToggleTorch = toggleTorch
            )

            Box(
                modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .aspectRatio(1f)
            ) {
                CameraPreview(
                    currentResolution = currentResolution,
                    onResolutionChange = { resolution ->
                        settingsViewModel.onResolutionChanged(resolution)
                    },
                    currentDetection = currentDetection,
                    onDetectionResult = { detection ->
                        currentDetection = detection
                    },
                    currentConfidenceThreshold = currentConfidenceThreshold,
                    currentMaxBoxes = currentMaxBoxes,
                    currentNmsEnabled = currentNmsEnabled,
                    modifier = Modifier.fillMaxSize(),
                    currentScanStrategy = currentScanStrategy,
                    modelId = currentModelId,
                    onCameraReady = { camera -> currentCamera = camera },
                    onScanStrategyAutoChange = { enforced ->
                        if (enforced != currentScanStrategy) {
                            settingsViewModel.onScanStrategyChanged(
                                enforced
                            )
                        }
                    },
                    onScanStrategyConstraintChange = { constrained ->
                        scanStrategyConstrained = constrained
                    },
                    onTileCaptureRequesterChange = { requester ->
                        tileCaptureRequester = requester
                    })
            }
        }
    }
}


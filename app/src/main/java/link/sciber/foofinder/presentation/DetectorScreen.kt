package link.sciber.foofinder.presentation

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.TorchState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import link.sciber.foofinder.R
import link.sciber.foofinder.domain.Detection
import link.sciber.foofinder.presentation.CameraAnalyzer.TileCaptureResult
import link.sciber.foofinder.utils.CameraResolutionUtils
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private const val SNAPSHOT_SUBDIR = "FooFinder"
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
                        saveDetectionTile(context, result)
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
                        openImagePreview(context, saveOutcome.uri)
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
                    { openImagePreview(context, uri) }
                }

                InfoBar(
                    currentDetection = currentDetection,
                    modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    isSaving = isSaving,
                    lastSavedMessage = lastSavedMessage,
                    onSnapshotClick = { handleSnapshotRequest() },
                    onOpenLastSaved = openLastSaved
                )

                SectionTitle("Detection")
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        LabeledSlider(
                            title = "Confidence Threshold",
                            value = currentConfidenceThreshold,
                            onValueChange = { value ->
                                settingsViewModel.onConfidenceThresholdChanged(
                                        value
                                    )
                            },
                            valueFormatter = { v ->
                                (v * 100).toInt().toString()
                            },
                            range = 0f..1f
                        )
                        LabeledSlider(
                            title = "Maximum Boxes",
                            value = currentMaxBoxes.toFloat(),
                            onValueChange = { value ->
                                settingsViewModel.onMaxBoxesChanged(
                                    value.roundToInt()
                                )
                            },
                            valueFormatter = { v ->
                                v.toInt().toString()
                            },
                            range = 1f..100f
                        )
                        val strategies = CameraAnalyzer.ScanStrategy.entries
                        val strategyLabels = strategies.map { entry ->
                            entry.name.replace('_', ' ').lowercase().replaceFirstChar {
                                    it.uppercase()
                                }
                        }
                        val selectedStrategyIndex = strategies.indexOf(currentScanStrategy)
                        LabeledDropdown(
                            title = "Scanning Strategy",
                            options = strategyLabels,
                            selectedIndex = selectedStrategyIndex,
                            isOptionEnabled = { idx ->
                                val strategy = strategies.getOrNull(idx)
                                if (!scanStrategyConstrained) {
                                    true
                                } else {
                                    strategy == CameraAnalyzer.ScanStrategy.SCALED_SINGLE
                                }
                            },
                            onSelectedIndex = { idx ->
                                if (idx in strategies.indices) {
                                    val strategy = strategies[idx]
                                    if (!scanStrategyConstrained || strategy == CameraAnalyzer.ScanStrategy.SCALED_SINGLE) {
                                        settingsViewModel.onScanStrategyChanged(
                                                strategy
                                            )
                                    }
                                }
                            })
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Enable NMS", style = MaterialTheme.typography.bodyMedium
                            )
                            Switch(
                                checked = currentNmsEnabled, onCheckedChange = { enabled ->
                                    settingsViewModel.onNmsEnabledChanged(
                                            enabled
                                        )
                                })
                        }
                    }
                }

                SectionTitle("Camera")
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val resLabels = availableResolutions.map { r ->
                            CameraResolutionUtils.formatResolution(r)
                        }
                        val selectedIdx = currentResolution?.let { sel ->
                            availableResolutions.indexOfFirst { it == sel }.takeIf { it >= 0 }
                        } ?: -1
                        LabeledDropdown(
                            title = "Resolution",
                            options = resLabels,
                            selectedIndex = selectedIdx,
                            onSelectedIndex = { idx ->
                                if (idx in availableResolutions.indices) {
                                    settingsViewModel.onResolutionChanged(
                                            availableResolutions[idx]
                                        )
                                }
                            })
                    }
                }

                SectionTitle("Model")
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val selectedModelIndex = modelOptions.indexOfFirst {
                                it.first == currentModelId
                            }.takeIf { it >= 0 } ?: 0
                        LabeledDropdown(
                            title = "Model",
                            options = modelOptions.map { it.second },
                            selectedIndex = selectedModelIndex,
                            onSelectedIndex = { idx ->
                                if (idx in modelOptions.indices) {
                                    settingsViewModel.onModelChanged(
                                            modelOptions[idx].first
                                        )
                                }
                            })
                        Text(
                            "Input size: 640 × 640",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }) { paddingValues ->
        Column(modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)) {
            TopBar(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    isTorchEnabled: Boolean, isTorchAvailable: Boolean, onToggleTorch: () -> Unit
) {
    TopAppBar(
        title = { Text("Detector", fontWeight = FontWeight.SemiBold) }, actions = {
        val iconRes = if (isTorchEnabled) R.drawable.flashlight_on_24
        else R.drawable.flashlight_off_24
        val contentDescription = if (isTorchEnabled) "Flashlight On" else "Flashlight Off"
        IconButton(onClick = onToggleTorch, enabled = isTorchAvailable) {
            Icon(
                painter = painterResource(id = iconRes), contentDescription = contentDescription
            )
        }
        IconButton(onClick = { /* TODO: grid */ }) {
            Icon(
                painter = painterResource(id = R.drawable.dataset_24),
                contentDescription = "Dataset"
            )
        }
    }, colors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.background
    )
    )
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun LabeledSlider(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueFormatter: (Float) -> String,
    range: ClosedFloatingPointRange<Float>
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("$title:", style = MaterialTheme.typography.bodyMedium)
            Text(
                valueFormatter(value),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range)
    }
}

@Composable
private fun LabeledDropdown(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    isOptionEnabled: (Int) -> Boolean = { true },
    onSelectedIndex: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("$title:", style = MaterialTheme.typography.bodyMedium)
        var expanded by remember { mutableStateOf(false) }
        val selectedText = options.getOrNull(selectedIndex) ?: "Select"
        OutlinedTextField(
            value = selectedText,
            onValueChange = {},
            modifier = Modifier.fillMaxWidth(),
            readOnly = true,
            trailingIcon = {
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        Icons.Default.ArrowDropDown, contentDescription = null
                    )
                }
            })
        androidx.compose.material3.DropdownMenu(
            expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { idx, label ->
                val enabled = isOptionEnabled(idx)
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(label) },
                    enabled = enabled,
                    onClick = {
                        if (!enabled) return@DropdownMenuItem
                        expanded = false
                        onSelectedIndex(idx)
                    })
            }
        }
    }
}

@Composable
private fun InfoBar(
    currentDetection: Detection?,
    modifier: Modifier = Modifier,
    onSnapshotClick: (() -> Unit)? = null,
    isSaving: Boolean = false,
    lastSavedMessage: String? = null,
    onOpenLastSaved: (() -> Unit)? = null
) {
    Card(
        modifier = modifier.padding(vertical = 8.dp), colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        )
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                val fpsText = currentDetection?.let {
                    if (it.fps >= 0f) String.format(
                        "%.1f", it.fps
                    )
                    else "-"
                } ?: "-"
                val infText = currentDetection?.let {
                    if (it.inferenceMs >= 0) "${it.inferenceMs} ms"
                    else "-"
                } ?: "-"
                val objectsText = currentDetection?.afterNmsDetections ?: 0
                val tileText = "640 × 640" // placeholder
                val delegateText = "CPU/XNNPACK(4t)" // placeholder until wired from detector

                Text(
                    buildString {
                        append("Object(s): ")
                        append(objectsText)
                    },
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "FPS: $fpsText",
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Inference: $infText",
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Tile: $tileText",
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Delegate: $delegateText",
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (onSnapshotClick != null) {
                FloatingActionButton(
                    onClick = onSnapshotClick,
                    modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp),
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
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
        }

        if (!lastSavedMessage.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = lastSavedMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                if (onOpenLastSaved != null) {
                    TextButton(onClick = onOpenLastSaved) {
                        Text(
                            "Open", style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }
}

private data class SaveOutcome(
    val uri: Uri, val displayName: String, val locationDescription: String
)

private fun saveDetectionTile(context: Context, result: TileCaptureResult): SaveOutcome {
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val displayName = "FooFinder_$timestamp.jpg"
    val relativePath = "${Environment.DIRECTORY_PICTURES}/$SNAPSHOT_SUBDIR"
    val resolver = context.contentResolver

    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        } else {
            val picturesDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES
            )
            val targetDir = File(picturesDir, SNAPSHOT_SUBDIR)
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                throw IOException("Unable to create directory: ${targetDir.absolutePath}")
            }
            val legacyPath = File(targetDir, displayName).absolutePath
            put(MediaStore.Images.Media.DATA, legacyPath)
        }
    }

    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: throw IOException("Failed to create MediaStore entry")

    try {
        resolver.openOutputStream(uri)?.use { output ->
            if (!result.bitmap.compress(
                    Bitmap.CompressFormat.JPEG, 95, output
                )
            ) {
                throw IOException("Failed to compress bitmap")
            }
        } ?: throw IOException("Failed to open image output stream")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }.also { resolver.update(uri, it, null, null) }
        } else {
            val legacyPath = values.getAsString(MediaStore.Images.Media.DATA)
            MediaScannerConnection.scanFile(
                context, arrayOf(legacyPath), arrayOf("image/jpeg"), null
            )
        }

        return SaveOutcome(
            uri = uri, displayName = displayName, locationDescription = relativePath
        )
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        throw e
    }
}

private fun openImagePreview(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "image/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    if (intent.resolveActivity(context.packageManager) != null) {
        context.startActivity(intent)
    } else {
        Toast.makeText(
            context, "No app found to open saved image", Toast.LENGTH_SHORT
        ).show()
    }
}
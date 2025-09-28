package link.sciber.foofinder.presentation

import android.util.Size
import androidx.camera.view.LifecycleCameraController
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import link.sciber.foofinder.R
import link.sciber.foofinder.domain.Detection
import link.sciber.foofinder.utils.CameraResolutionUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen() {
        val context = LocalContext.current
        val controller = remember { LifecycleCameraController(context) }

        var availableResolutions by remember { mutableStateOf<List<Size>>(emptyList()) }
        var currentResolution by remember { mutableStateOf<Size?>(null) }

        // Detection controls state
        var currentConfidenceThreshold by remember { mutableStateOf(0.45f) }
        var currentNmsEnabled by remember { mutableStateOf(true) }
        var currentMaxBoxes by remember { mutableStateOf(15) }
        var currentScanStrategy by remember {
                mutableStateOf(CameraPreviewAnalyzer.ScanStrategy.RANDOM)
        }

        // Detection results
        var currentDetection by remember { mutableStateOf<Detection?>(null) }

        // Initialize resolutions when screen is first created
        LaunchedEffect(Unit) {
                val resolutions = CameraResolutionUtils.getAvailableResolutions(context)
                availableResolutions = CameraResolutionUtils.sortResolutionsByWidth(resolutions)
                if (currentResolution == null && resolutions.isNotEmpty()) {
                        currentResolution =
                                CameraResolutionUtils.findBestDefaultResolution(resolutions)
                }
        }

        val navInsets = WindowInsets.navigationBars.asPaddingValues()

        val sheetScrollState = rememberScrollState()
        val sheetState = rememberBottomSheetScaffoldState()

        BottomSheetScaffold(
                scaffoldState = sheetState,
                sheetPeekHeight = 180.dp,
                sheetContainerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.98f),
                sheetDragHandle = {
                        Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                                Box(
                                        modifier = Modifier
                                                .height(4.dp)
                                                .width(32.dp)
                                                .clip(RoundedCornerShape(50))
                                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                                )
                        }
                },
                sheetContent = {
                        Column(
                                modifier = Modifier
                                        .fillMaxWidth()
                                        .verticalScroll(sheetScrollState)
                                        .padding(horizontal = 16.dp)
                                        .padding(bottom = navInsets.calculateBottomPadding() + 16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                                SectionTitle("Detection")
                                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                        Column(
                                                modifier = Modifier.padding(12.dp),
                                                verticalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                                LabeledSlider(
                                                        title = "Confidence Threshold",
                                                        value = currentConfidenceThreshold,
                                                        onValueChange = { currentConfidenceThreshold = it },
                                                        valueFormatter = { v -> (v * 100).toInt().toString() },
                                                        range = 0f..1f
                                                )
                                                LabeledSlider(
                                                        title = "Maximum Boxes",
                                                        value = currentMaxBoxes.toFloat(),
                                                        onValueChange = { currentMaxBoxes = it.toInt() },
                                                        valueFormatter = { v -> v.toInt().toString() },
                                                        range = 1f..100f
                                                )
                                                LabeledDropdown(
                                                        title = "Scanning Strategy",
                                                        options = CameraPreviewAnalyzer.ScanStrategy.entries.map { it.name.replace('_', ' ').lowercase().replaceFirstChar { c -> c.uppercase() } },
                                                        selectedIndex = CameraPreviewAnalyzer.ScanStrategy.entries.indexOf(currentScanStrategy),
                                                        onSelectedIndex = { idx ->
                                                                currentScanStrategy = CameraPreviewAnalyzer.ScanStrategy.entries[idx]
                                                        }
                                                )
                                                Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                        Text("Enable NMS", style = MaterialTheme.typography.bodyMedium)
                                                        Switch(checked = currentNmsEnabled, onCheckedChange = { currentNmsEnabled = it })
                                                }
                                        }
                                }

                                SectionTitle("Camera")
                                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                        Column(
                                                modifier = Modifier.padding(12.dp),
                                                verticalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                                val resLabels = availableResolutions.map { r -> CameraResolutionUtils.formatResolution(r) }
                                                val selectedIdx = currentResolution?.let { sel -> availableResolutions.indexOfFirst { it == sel }.takeIf { it >= 0 } } ?: -1
                                                LabeledDropdown(
                                                        title = "Resolution",
                                                        options = resLabels,
                                                        selectedIndex = selectedIdx,
                                                        onSelectedIndex = { idx ->
                                                                if (idx in availableResolutions.indices) currentResolution = availableResolutions[idx]
                                                        }
                                                )
                                        }
                                }

                                SectionTitle("Model")
                                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                        Column(
                                                modifier = Modifier.padding(12.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                                LabeledDropdown(
                                                        title = "Model",
                                                        options = listOf("DeePoo YOLOX Nano"),
                                                        selectedIndex = 0,
                                                        onSelectedIndex = {}
                                                )
                                                Text(
                                                        "Input size: 640 × 640",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                        }
                                }
                        }
                }
        ) { paddingValues ->
                Column(
                        modifier = Modifier
                                .fillMaxSize()
                                .padding(paddingValues)
                ) {
                        TopBar()

                        Box(
                                modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp)
                                        .aspectRatio(1f),
                                contentAlignment = Alignment.Center
                        ) {
                                CameraPreview(
                                        controller = controller,
                                        currentResolution = currentResolution,
                                        onResolutionChange = { resolution -> currentResolution = resolution },
                                        currentDetection = currentDetection,
                                        onDetectionResult = { detection -> currentDetection = detection },
                                        currentConfidenceThreshold = currentConfidenceThreshold,
                                        currentMaxBoxes = currentMaxBoxes,
                                        currentNmsEnabled = currentNmsEnabled,
                                        currentScanStrategy = currentScanStrategy,
                                        modifier = Modifier.fillMaxSize()
                                )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        InfoBar(currentDetection = currentDetection)
                }
        }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun TopBar() {
        TopAppBar(
                title = { Text("FooFinder", fontWeight = FontWeight.SemiBold) },
                actions = {
                        IconButton(onClick = { /* TODO: torch */ }) { Icon(painter = painterResource(id = R.drawable.flashlight_on_24), contentDescription = "Flashlight On") }
                        IconButton(onClick = { /* TODO: grid */ }) { Icon(painter = painterResource(id = R.drawable.dataset_24), contentDescription = "Dataset") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )
}

@Composable
private fun SectionTitle(title: String) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
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
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("$title:", style = MaterialTheme.typography.bodyMedium)
                        Text(valueFormatter(value), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                Slider(value = value, onValueChange = onValueChange, valueRange = range)
        }
}

@Composable
private fun LabeledDropdown(
        title: String,
        options: List<String>,
        selectedIndex: Int,
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
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                }
                        }
                )
                androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        options.forEachIndexed { idx, label ->
                                androidx.compose.material3.DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                                expanded = false
                                                onSelectedIndex(idx)
                                        }
                                )
                        }
                }
        }
}

@Composable
private fun InfoBar(currentDetection: Detection?) {
        Card(
                modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF607D8B)) // blue grey
        ) {
                Column(modifier = Modifier.padding(12.dp)) {
                        val fpsText = currentDetection?.let { if (it.fps >= 0f) String.format("%.1f", it.fps) else "-" } ?: "-"
                        val infText = currentDetection?.let { if (it.inferenceMs >= 0) "${it.inferenceMs} ms" else "-" } ?: "-"
                        val objectsText = currentDetection?.afterNmsDetections ?: 0
                        val tileText = "640 × 640" // placeholder
                        val delegateText = "CPU/XNNPACK(4t)" // placeholder until wired from detector

                        Text(buildString { append("Object(s): "); append(objectsText) }, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("FPS: $fpsText", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Inference: $infText", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Tile: $tileText", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Delegate: $delegateText", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                }
        }
}

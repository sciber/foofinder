package link.sciber.foofinder.presentation

import android.util.Size
import androidx.camera.view.LifecycleCameraController
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import link.sciber.foofinder.domain.Detection
import link.sciber.foofinder.presentation.components.ConfidenceThresholdDialog
import link.sciber.foofinder.presentation.components.MaxBoxesDialog
import link.sciber.foofinder.presentation.components.ResolutionSelectionDialog
import link.sciber.foofinder.utils.CameraResolutionUtils

@Composable
fun CameraScreen() {
        val context = LocalContext.current
        val controller = remember { LifecycleCameraController(context) }

        var availableResolutions by remember { mutableStateOf<List<Size>>(emptyList()) }
        var currentResolution by remember { mutableStateOf<Size?>(null) }
        var showResolutionDialog by remember { mutableStateOf(false) }

        // Confidence threshold state
        var currentConfidenceThreshold by remember { mutableStateOf(0.45f) }
        var showConfidenceDialog by remember { mutableStateOf(false) }

        // Max displayed boxes state
        var currentMaxBoxes by remember { mutableStateOf(50) }
        var showMaxBoxesDialog by remember { mutableStateOf(false) }

        // Detection state
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

        Box(modifier = Modifier.fillMaxSize()) {
                // Camera Preview with Detection Integration
                CameraPreview(
                        controller = controller,
                        currentResolution = currentResolution,
                        onResolutionChange = { resolution -> currentResolution = resolution },
                        currentDetection = currentDetection,
                        onDetectionResult = { detection -> currentDetection = detection },
                        currentConfidenceThreshold = currentConfidenceThreshold,
                        currentMaxBoxes = currentMaxBoxes,
                        modifier = Modifier.fillMaxSize()
                )

                // Bottom-left controls: Resolution + Confidence Threshold + Max Boxes
                val navigationBarsPadding = WindowInsets.navigationBars.asPaddingValues()
                Column(
                        modifier =
                                Modifier.align(Alignment.BottomStart)
                                        .padding(
                                                start = 16.dp,
                                                bottom =
                                                        16.dp +
                                                                navigationBarsPadding
                                                                        .calculateBottomPadding(),
                                                end = 16.dp,
                                        )
                ) {
                        // Resolution button (if resolution known)
                        currentResolution?.let { resolution ->
                                Card(
                                        modifier =
                                                Modifier.padding(top = 16.dp).clickable {
                                                        showResolutionDialog = true
                                                },
                                        colors =
                                                CardDefaults.cardColors(
                                                        containerColor =
                                                                Color.Black.copy(alpha = 0.7f)
                                                )
                                ) {
                                        Text(
                                                text =
                                                        CameraResolutionUtils.formatResolution(
                                                                resolution
                                                        ),
                                                color = Color.White,
                                                style = MaterialTheme.typography.bodyMedium,
                                                modifier = Modifier.padding(12.dp)
                                        )
                                }
                        }

                        // Confidence threshold button
                        Card(
                                modifier =
                                        Modifier.padding(top = 8.dp).clickable {
                                                showConfidenceDialog = true
                                        },
                                colors =
                                        CardDefaults.cardColors(
                                                containerColor = Color.Black.copy(alpha = 0.7f)
                                        )
                        ) {
                                val pct = (currentConfidenceThreshold * 100).toInt()
                                Text(
                                        text = "Confidence: ${pct}%",
                                        color = Color.White,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(12.dp)
                                )
                        }

                        // Max boxes button (positioned below confidence)
                        Card(
                                modifier =
                                        Modifier.padding(top = 8.dp).clickable {
                                                showMaxBoxesDialog = true
                                        },
                                colors =
                                        CardDefaults.cardColors(
                                                containerColor = Color.Black.copy(alpha = 0.7f)
                                        )
                        ) {
                                Text(
                                        text = "Max boxes: $currentMaxBoxes",
                                        color = Color.White,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(12.dp)
                                )
                        }
                }

                // (Stats now rendered near detection area in CameraPreview)
        }

        // Resolution Selection Dialog
        if (showResolutionDialog) {
                ResolutionSelectionDialog(
                        availableResolutions = availableResolutions,
                        currentResolution = currentResolution,
                        onResolutionSelected = { resolution ->
                                currentResolution = resolution
                                showResolutionDialog = false
                        },
                        onDismiss = { showResolutionDialog = false }
                )
        }

        // Confidence Threshold Dialog
        if (showConfidenceDialog) {
                ConfidenceThresholdDialog(
                        current = currentConfidenceThreshold,
                        onConfirm = { value ->
                                currentConfidenceThreshold = value
                                showConfidenceDialog = false
                        },
                        onDismiss = { showConfidenceDialog = false }
                )
        }

        // Max Boxes Dialog
        if (showMaxBoxesDialog) {
                MaxBoxesDialog(
                        current = currentMaxBoxes,
                        onConfirm = { value ->
                                currentMaxBoxes = value
                                showMaxBoxesDialog = false
                        },
                        onDismiss = { showMaxBoxesDialog = false }
                )
        }
}

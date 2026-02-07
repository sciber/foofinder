package link.sciber.foofinder.presentation.components

import android.util.Size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import link.sciber.foofinder.presentation.CameraAnalyzer
import link.sciber.foofinder.utils.CameraResolutionUtils

/** Settings sheet content for detector configuration */
@Composable
fun DetectorSettingsSheet(
        currentConfidenceThreshold: Float,
        currentMaxBoxes: Int,
        currentNmsEnabled: Boolean,
        currentScanStrategy: CameraAnalyzer.ScanStrategy,
        scanStrategyConstrained: Boolean,
        availableResolutions: List<Size>,
        currentResolution: Size?,
        modelOptions: List<Pair<String, String>>,
        currentModelId: String,
        currentTileSize: Int?,
        onConfidenceThresholdChanged: (Float) -> Unit,
        onMaxBoxesChanged: (Int) -> Unit,
        onNmsEnabledChanged: (Boolean) -> Unit,
        onScanStrategyChanged: (CameraAnalyzer.ScanStrategy) -> Unit,
        onResolutionChanged: (Size) -> Unit,
        onModelChanged: (String) -> Unit,
        modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Detection Settings
        SectionTitle("Detection")
        Card(
                colors =
                        CardDefaults.cardColors(
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
                        onValueChange = onConfidenceThresholdChanged,
                        valueFormatter = { v -> (v * 100).toInt().toString() },
                        range = 0f..1f
                )

                LabeledSlider(
                        title = "Maximum Boxes",
                        value = currentMaxBoxes.toFloat(),
                        onValueChange = { value -> onMaxBoxesChanged(value.roundToInt()) },
                        valueFormatter = { v -> v.toInt().toString() },
                        range = 1f..100f
                )

                val strategies = CameraAnalyzer.ScanStrategy.entries
                val strategyLabels =
                        strategies.map { entry ->
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
                                strategy == CameraAnalyzer.ScanStrategy.SCALED
                            }
                        },
                        onSelectedIndex = { idx ->
                            if (idx in strategies.indices) {
                                val strategy = strategies[idx]
                                if (!scanStrategyConstrained ||
                                                strategy == CameraAnalyzer.ScanStrategy.SCALED
                                ) {
                                    onScanStrategyChanged(strategy)
                                }
                            }
                        }
                )

                Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Enable NMS", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = currentNmsEnabled, onCheckedChange = onNmsEnabledChanged)
                }
            }
        }

        // Camera Settings
        SectionTitle("Camera")
        Card(
                colors =
                        CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
        ) {
            Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val resLabels =
                        availableResolutions.map { r -> CameraResolutionUtils.formatResolution(r) }
                val selectedIdx =
                        currentResolution?.let { sel ->
                            availableResolutions.indexOfFirst { it == sel }.takeIf { it >= 0 }
                        }
                                ?: -1

                LabeledDropdown(
                        title = "Resolution",
                        options = resLabels,
                        selectedIndex = selectedIdx,
                        onSelectedIndex = { idx ->
                            if (idx in availableResolutions.indices) {
                                onResolutionChanged(availableResolutions[idx])
                            }
                        }
                )
            }
        }

        // Model Settings
        SectionTitle("Model")
        Card(
                colors =
                        CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
        ) {
            Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val selectedModelIndex =
                        modelOptions.indexOfFirst { it.first == currentModelId }.takeIf { it >= 0 }
                                ?: 0

                LabeledDropdown(
                        title = "Model",
                        options = modelOptions.map { it.second },
                        selectedIndex = selectedModelIndex,
                        onSelectedIndex = { idx ->
                            if (idx in modelOptions.indices) {
                                onModelChanged(modelOptions[idx].first)
                            }
                        }
                )

                Text(
                        text = currentTileSize?.let { "Input size: ${it} × ${it}" }
                                        ?: "Input size: -",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

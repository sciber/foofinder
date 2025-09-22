package link.sciber.foofinder.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
fun ConfidenceThresholdDialog(
        current: Float,
        onConfirm: (Float) -> Unit,
        onDismiss: () -> Unit,
) {
    val sliderValue = remember(current) { mutableFloatStateOf(current.coerceIn(0f, 1f)) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                        text = "Confidence Threshold",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                )

                Spacer(modifier = Modifier.height(8.dp))

                val pct = (sliderValue.floatValue * 100).toInt()
                Text(
                        text = "${pct}%",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(bottom = 8.dp)
                )

                Slider(
                        value = sliderValue.floatValue,
                        onValueChange = { v -> sliderValue.floatValue = v.coerceIn(0f, 1f) },
                        valueRange = 0f..1f,
                        steps = 19, // 0.05 increments
                        modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(modifier = Modifier.weight(1f))
                    Button(onClick = { onConfirm(sliderValue.floatValue) }) { Text("Apply") }
                }
            }
        }
    }
}

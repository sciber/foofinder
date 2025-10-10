package link.sciber.foofinder.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import link.sciber.foofinder.domain.Detection

/**
 * Info bar displaying detection statistics and last saved image information
 */
@Composable
fun DetectorInfoBar(
    currentDetection: Detection?,
    modifier: Modifier = Modifier,
    lastSavedMessage: String? = null,
    onOpenLastSaved: (() -> Unit)? = null
) {
    Card(
        modifier = modifier.padding(vertical = 8.dp), colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            val fpsText = currentDetection?.let {
                if (it.fps >= 0f) String.format("%.1f", it.fps)
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
                text = buildString {
                    append("Object(s): ")
                    append(objectsText)
                },
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "FPS: $fpsText",
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Inference: $infText",
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Tile: $tileText",
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Delegate: $delegateText",
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                style = MaterialTheme.typography.bodyMedium
            )
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
                            text = "Open", style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }
}

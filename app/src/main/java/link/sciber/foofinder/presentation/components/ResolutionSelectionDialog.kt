package link.sciber.foofinder.presentation.components

import android.util.Size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import link.sciber.foofinder.utils.CameraResolutionUtils

@Composable
fun ResolutionSelectionDialog(
    availableResolutions: List<Size>,
    currentResolution: Size?,
    onResolutionSelected: (Size) -> Unit,
    onDismiss: () -> Unit
) {
    // Calculate initial scroll position based on selected resolution
    val initialScrollIndex =
        remember(currentResolution) {
            currentResolution?.let { selected ->
                availableResolutions.indexOfFirst { it == selected }.takeIf { it >= 0 } ?: 0
            }
                ?: 0
        }

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialScrollIndex)

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .clip(RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Camera Resolution",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                LazyColumn(state = listState) {
                    items(availableResolutions) { resolution ->
                        val isSelected = resolution == currentResolution

                        Card(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        onResolutionSelected(resolution)
                                    },
                            colors =
                                CardDefaults.cardColors(
                                    containerColor =
                                        if (isSelected)
                                            MaterialTheme.colorScheme
                                                .primaryContainer
                                        else
                                            MaterialTheme.colorScheme
                                                .surfaceVariant
                                )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text =
                                            CameraResolutionUtils.formatResolution(
                                                resolution
                                            ),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight =
                                            if (isSelected) FontWeight.Bold
                                            else FontWeight.Normal
                                    )
                                    Text(
                                        text =
                                            CameraResolutionUtils.calculateMegapixels(
                                                resolution
                                            ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Cancel")
                }
            }
        }
    }
}

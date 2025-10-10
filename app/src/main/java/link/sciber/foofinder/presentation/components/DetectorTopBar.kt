package link.sciber.foofinder.presentation.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import link.sciber.foofinder.R

/**
 * Top app bar for the detector screen with torch and dataset controls
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetectorTopBar(
    isTorchEnabled: Boolean,
    isTorchAvailable: Boolean,
    onToggleTorch: () -> Unit,
    onDatasetClick: () -> Unit = {}
) {
    TopAppBar(
        title = {
        Text(
            text = "Detector", fontWeight = FontWeight.SemiBold
        )
    }, actions = {
        val iconRes = if (isTorchEnabled) {
            R.drawable.flashlight_on_24
        } else {
            R.drawable.flashlight_off_24
        }
        val contentDescription = if (isTorchEnabled) {
            "Flashlight On"
        } else {
            "Flashlight Off"
        }

        IconButton(
            onClick = onToggleTorch, enabled = isTorchAvailable
        ) {
            Icon(
                painter = painterResource(id = iconRes), contentDescription = contentDescription
            )
        }

        IconButton(onClick = onDatasetClick) {
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

package link.sciber.foofinder.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Reusable section title component for settings categories
 */
@Composable
fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.fillMaxWidth()
    )
}

/**
 * Labeled slider with title and formatted value display
 */
@Composable
fun LabeledSlider(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueFormatter: (Float) -> String,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("$title:", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = valueFormatter(value),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range
        )
    }
}

/**
 * Labeled dropdown menu with optional per-item enabling
 */
@Composable
fun LabeledDropdown(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelectedIndex: (Int) -> Unit,
    isOptionEnabled: (Int) -> Boolean = { true },
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
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
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null
                    )
                }
            }
        )
        
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEachIndexed { idx, label ->
                val enabled = isOptionEnabled(idx)
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(label) },
                    enabled = enabled,
                    onClick = {
                        if (!enabled) return@DropdownMenuItem
                        expanded = false
                        onSelectedIndex(idx)
                    }
                )
            }
        }
    }
}

package link.sciber.foofinder.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import link.sciber.foofinder.domain.AnnotationBox

/**
 * Canvas composable for drawing annotation bounding boxes with interactive handles
 *
 * @param boundingBoxes List of annotation boxes to draw
 * @param activeBoxId ID of the currently selected box (if any)
 * @param modifier Modifier for the canvas
 */
@Composable
fun BoundingBoxCanvas(
    boundingBoxes: List<AnnotationBox>, activeBoxId: String?, modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        // Draw all bounding boxes
        boundingBoxes.forEach { box ->
            val isActive = box.id == activeBoxId
            val strokeColor = if (isActive) Color(0xFF64B5F6) else Color(0xFF1976D2)
            val strokeWidth = if (isActive) 5f else 3f

            // Draw box outline
            drawRect(
                color = strokeColor,
                topLeft = Offset(box.left, box.top),
                size = Size(box.right - box.left, box.bottom - box.top),
                style = Stroke(width = strokeWidth)
            )

            // Draw corner handles and delete button for active box
            if (box.id == activeBoxId) {
                drawCornerHandles(box)
                drawDeleteButton(box)
            }
        }
    }
}

/**
 * Draw corner handles for resizing (3 corners - top-right is reserved for delete button)
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCornerHandles(box: AnnotationBox) {
    val handleSize = 15f
    val corners = listOf(
        Offset(box.left, box.top),        // Top-left
        Offset(box.left, box.bottom),     // Bottom-left
        Offset(box.right, box.bottom)     // Bottom-right
    )

    corners.forEach { corner ->
        // Handle background
        drawCircle(
            color = Color(0xFF2196F3), radius = handleSize, center = corner, style = Fill
        )
        // Handle border
        drawCircle(
            color = Color.White,
            radius = handleSize - 3f,
            center = corner,
            style = Stroke(width = 2f)
        )
    }
}

/**
 * Draw delete button at top-right corner
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDeleteButton(box: AnnotationBox) {
    val deleteButtonX = box.right
    val deleteButtonY = box.top
    val deleteButtonRadius = 18f

    // Delete button background
    drawCircle(
        color = Color(0xFFFF5252),
        radius = deleteButtonRadius,
        center = Offset(deleteButtonX, deleteButtonY),
        style = Fill
    )

    // Delete button border
    drawCircle(
        color = Color.White,
        radius = deleteButtonRadius,
        center = Offset(deleteButtonX, deleteButtonY),
        style = Stroke(width = 2f)
    )

    // Draw X icon
    val xSize = 7f
    drawLine(
        color = Color.White,
        start = Offset(deleteButtonX - xSize, deleteButtonY - xSize),
        end = Offset(deleteButtonX + xSize, deleteButtonY + xSize),
        strokeWidth = 3f
    )
    drawLine(
        color = Color.White,
        start = Offset(deleteButtonX + xSize, deleteButtonY - xSize),
        end = Offset(deleteButtonX - xSize, deleteButtonY + xSize),
        strokeWidth = 3f
    )
}

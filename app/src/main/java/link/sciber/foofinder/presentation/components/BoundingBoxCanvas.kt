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
import link.sciber.foofinder.ui.theme.boundingBoxActive
import link.sciber.foofinder.ui.theme.boundingBoxInactive

/**
 * Canvas composable for drawing annotation bounding boxes with interactive handles
 *
 * @param boundingBoxes List of annotation boxes to draw
 * @param activeBoxId ID of the currently selected box (if any)
 * @param scale Current zoom scale factor
 * @param offsetX Current pan offset X
 * @param offsetY Current pan offset Y
 * @param modifier Modifier for the canvas
 */
@Composable
fun BoundingBoxCanvas(
    boundingBoxes: List<AnnotationBox>,
    activeBoxId: String?,
    scale: Float = 1f,
    offsetX: Float = 0f,
    offsetY: Float = 0f,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f

        // Helper function to transform coordinates
        fun transformCoord(x: Float, y: Float): Offset {
            val scaledX = (x - centerX) * scale + centerX + offsetX
            val scaledY = (y - centerY) * scale + centerY + offsetY
            return Offset(scaledX, scaledY)
        }

        // Draw all bounding boxes
        boundingBoxes.forEach { box ->
            val isActive = box.id == activeBoxId
            // Use bright color for all bounding boxes
            val strokeColor = boundingBoxActive
            val strokeWidth = 6f

            // Transform box coordinates
            val topLeft = transformCoord(box.left, box.top)
            val topRight = transformCoord(box.right, box.top)
            val bottomLeft = transformCoord(box.left, box.bottom)
            val bottomRight = transformCoord(box.right, box.bottom)

            // Draw box outline
            drawRect(
                color = strokeColor,
                topLeft = topLeft,
                size = Size(bottomRight.x - topLeft.x, bottomRight.y - topLeft.y),
                style = Stroke(width = strokeWidth)
            )

            // Draw corner handles and delete button for active box
            if (box.id == activeBoxId) {
                drawCornerHandles(topLeft, bottomLeft, bottomRight)
                drawDeleteButton(topRight)
            }
        }
    }
}

/**
 * Draw corner handles for resizing (3 corners - top-right is reserved for delete button)
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCornerHandles(
    topLeft: Offset,
    bottomLeft: Offset,
    bottomRight: Offset
) {
    val handleSize = 15f
    val corners = listOf(topLeft, bottomLeft, bottomRight)

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
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDeleteButton(topRight: Offset) {
    val deleteButtonRadius = 18f

    // Delete button background
    drawCircle(
        color = Color(0xFFFF5252),
        radius = deleteButtonRadius,
        center = topRight,
        style = Fill
    )

    // Delete button border
    drawCircle(
        color = Color.White,
        radius = deleteButtonRadius,
        center = topRight,
        style = Stroke(width = 2f)
    )

    // Draw X icon
    val xSize = 7f
    drawLine(
        color = Color.White,
        start = Offset(topRight.x - xSize, topRight.y - xSize),
        end = Offset(topRight.x + xSize, topRight.y + xSize),
        strokeWidth = 3f
    )
    drawLine(
        color = Color.White,
        start = Offset(topRight.x + xSize, topRight.y - xSize),
        end = Offset(topRight.x - xSize, topRight.y + xSize),
        strokeWidth = 3f
    )
}

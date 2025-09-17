package link.sciber.foofinder.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import link.sciber.foofinder.domain.BoundingBox

val GROUND_TRUTH_COLOR: Color = Color.White
const val GROUND_TRUTH_STROKE_WIDTH: Float = 3f

@Composable
fun GroundTruthOverlay(
        groundTruthBoxes: List<BoundingBox>,
        sourceWidth: Int,
        sourceHeight: Int,
        modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val canvasWidth = size.width
        val canvasHeight = size.height

        // Calculate scaling factors
        val scaleX = canvasWidth / sourceWidth.toFloat()
        val scaleY = canvasHeight / sourceHeight.toFloat()

        // Draw ground truth bounding boxes
        for (box in groundTruthBoxes) {
            val scaledBox = scaleBoundingBox(box, scaleX, scaleY)

            // Ground truth boxes in green
            drawRect(
                    color = GROUND_TRUTH_COLOR,
                    topLeft = Offset(scaledBox.startX, scaledBox.startY),
                    size = Size(scaledBox.width, scaledBox.height),
                    style = Stroke(width = GROUND_TRUTH_STROKE_WIDTH)
            )
        }
    }
}

private fun scaleBoundingBox(box: BoundingBox, scaleX: Float, scaleY: Float): BoundingBox {
    return BoundingBox(
            startX = box.startX * scaleX,
            startY = box.startY * scaleY,
            width = box.width * scaleX,
            height = box.height * scaleY,
            confidence = box.confidence,
            classId = box.classId,
            className = box.className
    )
}

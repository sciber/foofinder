package link.sciber.foofinder.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import link.sciber.foofinder.domain.BoundingBox
import link.sciber.foofinder.domain.Detection
import link.sciber.foofinder.domain.DetectionArea

val DETECTION_AREA_COLOR: Color = Color.Red
const val DETECTION_AREA_STROKE_WIDTH: Float = 4f
val FOO_CLASS_BOUNDING_BOX_COLOR: Color = Color.Blue
const val FOO_CLASS_BOUNDING_BOX_STROKE_WIDTH: Float = 3f
val NOT_FOO_CLASS_BOUNDING_BOX_COLOR: Color = Color.Magenta
const val NOT_FOO_CLASS_BOUNDING_BOX_STROKE_WIDTH: Float = 2f
val OTHER_CLASS_BOUNDING_BOX_COLOR: Color = Color.Cyan
const val OTHER_CLASS_BOUNDING_BOX_STROKE_WIDTH: Float = 1f

@Composable
fun DetectionOverlay(
        detection: Detection,
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

        // Transform detection area coordinates
        val scaledArea = scaleDetectionArea(detection.area, scaleX, scaleY)

        // Detection area
        drawRect(
                color = DETECTION_AREA_COLOR,
                topLeft = Offset(scaledArea.startX, scaledArea.startY),
                size = Size(scaledArea.width, scaledArea.height),
                style = Stroke(width = DETECTION_AREA_STROKE_WIDTH)
        )

        // Transform and draw bounding boxes
        for (box in detection.boundingBoxes) {
            val scaledBox = scaleBoundingBox(box, scaleX, scaleY)

            val boundingBoxColor =
                    when (scaledBox.classId) {
                        0 -> FOO_CLASS_BOUNDING_BOX_COLOR
                        1 -> NOT_FOO_CLASS_BOUNDING_BOX_COLOR
                        else -> OTHER_CLASS_BOUNDING_BOX_COLOR
                    }

            val boundingBoxStrokeWidth =
                    when (scaledBox.classId) {
                        0 -> FOO_CLASS_BOUNDING_BOX_STROKE_WIDTH
                        1 -> NOT_FOO_CLASS_BOUNDING_BOX_STROKE_WIDTH
                        else -> OTHER_CLASS_BOUNDING_BOX_STROKE_WIDTH
                    }

            // Bounding boxes
            drawRect(
                    color = boundingBoxColor,
                    topLeft = Offset(scaledBox.startX, scaledBox.startY),
                    size = Size(scaledBox.width, scaledBox.height),
                    style = Stroke(width = boundingBoxStrokeWidth)
            )
        }
    }
}

private fun scaleDetectionArea(area: DetectionArea, scaleX: Float, scaleY: Float): DetectionArea {
    return DetectionArea(
            startX = area.startX * scaleX,
            startY = area.startY * scaleY,
            width = area.width * scaleX,
            height = area.height * scaleY
    )
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

package link.sciber.foofinder.presentation

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import link.sciber.foofinder.domain.BoundingBox
import link.sciber.foofinder.domain.Detection
import link.sciber.foofinder.domain.DetectionArea

val DETECTION_AREA_COLOR: Color = Color.Red
const val DETECTION_AREA_STROKE_WIDTH: Float = 4f
val FOO_CLASS_BOUNDING_BOX_COLOR: Color = Color.Blue
const val FOO_CLASS_BOUNDING_BOX_STROKE_WIDTH: Float = 5f
val NOT_FOO_CLASS_BOUNDING_BOX_COLOR: Color = Color.Magenta
const val NOT_FOO_CLASS_BOUNDING_BOX_STROKE_WIDTH: Float = 2f
val OTHER_CLASS_BOUNDING_BOX_COLOR: Color = Color.Cyan
const val OTHER_CLASS_BOUNDING_BOX_STROKE_WIDTH: Float = 1f
val BASE_AREA_COLOR: Color = Color.Yellow
const val BASE_AREA_STROKE_WIDTH: Float = 3f

@Composable
fun DetectionOverlay(
        detection: Detection,
        sourceWidth: Int,
        sourceHeight: Int,
        modifier: Modifier = Modifier,
) {
        val density = LocalDensity.current
        val textSizePx = with(density) { 12.sp.toPx() }
        val labelPaddingPx = with(density) { 4.dp.toPx() } // internal text padding only
        val labelCornerRadiusPx = with(density) { 4.dp.toPx() }

        Canvas(modifier = modifier) {
                val renderStart = System.nanoTime()

                val canvasWidth = size.width
                val canvasHeight = size.height

                // Calculate scaling factors
                val scaleX = canvasWidth / sourceWidth.toFloat()
                val scaleY = canvasHeight / sourceHeight.toFloat()

                // Base square anchored at top-left (source coordinates 0..min(W,H))
                val baseSideSource = kotlin.math.min(sourceWidth, sourceHeight).toFloat()
                drawRect(
                        color = BASE_AREA_COLOR,
                        topLeft = Offset(0f, 0f),
                        size = Size(baseSideSource * scaleX, baseSideSource * scaleY),
                        style = Stroke(width = BASE_AREA_STROKE_WIDTH)
                )

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

                        // Confidence label (integer percent, matching control format)
                        val label = "${(scaledBox.confidence * 100).toInt()}%"
                        drawIntoCanvas { canvas ->
                                val textPaint =
                                        Paint().apply {
                                                isAntiAlias = true
                                                color = android.graphics.Color.WHITE
                                                textSize = textSizePx
                                                typeface = Typeface.DEFAULT_BOLD
                                        }
                                val bgPaint =
                                        Paint().apply {
                                                isAntiAlias = true
                                                // Use the same color as the bounding box with some
                                                // transparency
                                                color = boundingBoxColor.copy(alpha = 0.8f).toArgb()
                                        }

                                val textWidth = textPaint.measureText(label)
                                val fm = textPaint.fontMetrics
                                val textHeight = fm.bottom - fm.top

                                val rectWidth = textWidth + 2f * labelPaddingPx
                                val rectHeight = textHeight + 2f * labelPaddingPx

                                // Position inside the box at its top-right corner (no external
                                // padding)
                                val boxLeft = scaledBox.startX
                                val boxTop = scaledBox.startY
                                val boxRight = scaledBox.startX + scaledBox.width
                                val boxBottom = scaledBox.startY + scaledBox.height

                                var left = boxRight - rectWidth // flush with right edge inside
                                var top = boxTop // flush with top edge inside

                                // Clamp to keep the label fully inside the box
                                if (left < boxLeft) left = boxLeft
                                if (top < boxTop) top = boxTop
                                if (left + rectWidth > boxRight) left = boxRight - rectWidth
                                if (top + rectHeight > boxBottom) top = boxBottom - rectHeight

                                val rect = RectF(left, top, left + rectWidth, top + rectHeight)
                                canvas.nativeCanvas.drawRoundRect(
                                        rect,
                                        labelCornerRadiusPx,
                                        labelCornerRadiusPx,
                                        bgPaint
                                )

                                val textX = left + labelPaddingPx
                                // Baseline y for drawText
                                val textY = top + labelPaddingPx - fm.top
                                canvas.nativeCanvas.drawText(label, textX, textY, textPaint)
                        }
                }

                val renderEnd = System.nanoTime()
                val renderMs = ((renderEnd - renderStart) / 1_000_000L)
                // Keep tag short to avoid log overhead in render loop
                android.util.Log.d(
                        "DetectionOverlay",
                        "Timing(render): ${renderMs}ms for ${detection.boundingBoxes.size} boxes"
                )
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

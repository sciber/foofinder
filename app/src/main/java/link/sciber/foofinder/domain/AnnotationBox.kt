package link.sciber.foofinder.domain

import java.util.UUID

/**
 * Data class representing a manual bounding box annotation with interaction capabilities
 */
data class AnnotationBox(
    val id: String = UUID.randomUUID().toString(),
    var left: Float,
    var top: Float,
    var right: Float,
    var bottom: Float
) {
    /**
     * Check if a point is inside the bounding box
     */
    fun contains(x: Float, y: Float): Boolean {
        return x >= left && x <= right && y >= top && y <= bottom
    }

    /**
     * Check if a point is on the border of the bounding box
     */
    fun isOnBorder(x: Float, y: Float, threshold: Float = 20f): Boolean {
        val onLeft = x >= left - threshold && x <= left + threshold && y >= top && y <= bottom
        val onRight = x >= right - threshold && x <= right + threshold && y >= top && y <= bottom
        val onTop = y >= top - threshold && y <= top + threshold && x >= left && x <= right
        val onBottom = y >= bottom - threshold && y <= bottom + threshold && x >= left && x <= right
        return onLeft || onRight || onTop || onBottom
    }

    /**
     * Get which corner handle is being touched (if any)
     * Top-right corner is reserved for the delete button
     */
    fun getCornerHandle(x: Float, y: Float, handleSize: Float = 30f): Corner? {
        val topLeft =
            x >= left - handleSize && x <= left + handleSize && y >= top - handleSize && y <= top + handleSize
        val bottomLeft =
            x >= left - handleSize && x <= left + handleSize && y >= bottom - handleSize && y <= bottom + handleSize
        val bottomRight =
            x >= right - handleSize && x <= right + handleSize && y >= bottom - handleSize && y <= bottom + handleSize

        return when {
            topLeft -> Corner.TOP_LEFT
            bottomLeft -> Corner.BOTTOM_LEFT
            bottomRight -> Corner.BOTTOM_RIGHT
            else -> null
        }
    }

    /**
     * Check if a point is on the delete button (located at top-right corner)
     */
    fun isOnDeleteButton(x: Float, y: Float, buttonRadius: Float = 20f): Boolean {
        val deleteButtonX = right
        val deleteButtonY = top
        val dx = x - deleteButtonX
        val dy = y - deleteButtonY
        return (dx * dx + dy * dy) <= (buttonRadius * buttonRadius)
    }

    enum class Corner {
        TOP_LEFT, BOTTOM_LEFT, BOTTOM_RIGHT
    }
}

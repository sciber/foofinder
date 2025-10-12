package link.sciber.foofinder.utils

import androidx.compose.ui.geometry.Rect

/**
 * Utility object for calculating image display bounds
 */
object ImageBoundsCalculator {

    /**
     * Calculate actual image display bounds within a view when using ContentScale.Fit
     *
     * @param viewWidth Width of the view container
     * @param viewHeight Height of the view container
     * @param imageWidth Original image width
     * @param imageHeight Original image height
     * @return Rect representing the actual image display bounds (left, top, right, bottom)
     */
    fun calculateImageBounds(
        viewWidth: Float, viewHeight: Float, imageWidth: Int, imageHeight: Int
    ): Rect {
        val viewAspect = viewWidth / viewHeight
        val imageAspect = imageWidth.toFloat() / imageHeight.toFloat()

        val displayWidth: Float
        val displayHeight: Float
        val offsetX: Float
        val offsetY: Float

        if (imageAspect > viewAspect) {
            // Image is wider - fit to width
            displayWidth = viewWidth
            displayHeight = viewWidth / imageAspect
            offsetX = 0f
            offsetY = (viewHeight - displayHeight) / 2f
        } else {
            // Image is taller - fit to height
            displayHeight = viewHeight
            displayWidth = viewHeight * imageAspect
            offsetX = (viewWidth - displayWidth) / 2f
            offsetY = 0f
        }

        return Rect(offsetX, offsetY, offsetX + displayWidth, offsetY + displayHeight)
    }
}

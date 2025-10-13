package link.sciber.foofinder.presentation.components

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import link.sciber.foofinder.domain.AnnotationBox

/**
 * Transparent interaction layer for handling tap and drag gestures on annotation boxes
 *
 * @param boundingBoxes List of annotation boxes (mutable)
 * @param activeBoxId Currently selected box ID
 * @param imageDisplayBounds Bounds of the actual image display area
 * @param onActiveBoxIdChange Callback when active box changes
 * @param onBoxesChange Callback when boxes are modified
 * @param scale Current zoom scale factor
 * @param offsetX Current pan offset X
 * @param offsetY Current pan offset Y
 * @param modifier Modifier for the layer
 */
@Composable
fun BoundingBoxInteractionLayer(
    boundingBoxes: MutableList<AnnotationBox>,
    activeBoxId: String?,
    imageDisplayBounds: Rect,
    onActiveBoxIdChange: (String?) -> Unit,
    onBoxesChange: (List<AnnotationBox>) -> Unit,
    scale: Float = 1f,
    offsetX: Float = 0f,
    offsetY: Float = 0f,
    modifier: Modifier = Modifier
) {
    // Key these states to activeBoxId so they reset when switching boxes
    var isDragging by remember(activeBoxId) { mutableStateOf(false) }
    var resizingCorner by remember(activeBoxId) { mutableStateOf<AnnotationBox.Corner?>(null) }

    // Helper function to transform screen coordinates to image coordinates
    fun transformToImageCoords(screenX: Float, screenY: Float, viewWidth: Float, viewHeight: Float): androidx.compose.ui.geometry.Offset {
        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f
        val imageX = (screenX - centerX - offsetX) / scale + centerX
        val imageY = (screenY - centerY - offsetY) / scale + centerY
        return androidx.compose.ui.geometry.Offset(imageX, imageY)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .pointerInput(boundingBoxes.size, activeBoxId, scale, offsetX, offsetY) {
                detectTapGestures { offset ->
                    // Transform touch coordinates to image space
                    val imageCoords = transformToImageCoords(offset.x, offset.y, size.width.toFloat(), size.height.toFloat())
                    Log.d("BoundingBoxInteraction", "Tap at screen: (${offset.x}, ${offset.y}), image: (${imageCoords.x}, ${imageCoords.y})")

                    // Check if tapping delete button on active box
                    val activeBox = boundingBoxes.find { it.id == activeBoxId }
                    if (activeBox != null && activeBox.isOnDeleteButton(imageCoords.x, imageCoords.y)) {
                        Log.d(
                            "BoundingBoxInteraction",
                            "Delete button tapped for box: ${activeBox.id}"
                        )
                        boundingBoxes.remove(activeBox)
                        onActiveBoxIdChange(null)
                        onBoxesChange(boundingBoxes.toList())
                        return@detectTapGestures
                    }

                    // Find which box we're tapping
                    val tappedBox = boundingBoxes.findLast { box ->
                        box.contains(imageCoords.x, imageCoords.y) || box.isOnBorder(imageCoords.x, imageCoords.y)
                    }

                    if (tappedBox != null) {
                        Log.d("BoundingBoxInteraction", "Selected box: ${tappedBox.id}")
                        onActiveBoxIdChange(tappedBox.id)
                    } else {
                        // Deselect if tapping empty area
                        Log.d("BoundingBoxInteraction", "Deselected")
                        onActiveBoxIdChange(null)
                    }
                }
            }
            .pointerInput(boundingBoxes.size, activeBoxId, imageDisplayBounds, scale, offsetX, offsetY) {
                detectDragGestures(onDragStart = { offset ->
                    // Transform touch coordinates to image space
                    val imageCoords = transformToImageCoords(offset.x, offset.y, size.width.toFloat(), size.height.toFloat())
                    Log.d("BoundingBoxInteraction", "Drag start at screen: (${offset.x}, ${offset.y}), image: (${imageCoords.x}, ${imageCoords.y})")

                    // Find which box we're dragging
                    val draggedBox = boundingBoxes.find { it.id == activeBoxId }

                    if (draggedBox != null) {
                        // Check if on delete button - don't allow dragging from there
                        if (draggedBox.isOnDeleteButton(imageCoords.x, imageCoords.y)) {
                            return@detectDragGestures
                        }

                        // Check if starting a resize or move
                        val corner = draggedBox.getCornerHandle(imageCoords.x, imageCoords.y)
                        if (corner != null) {
                            Log.d("BoundingBoxInteraction", "Resizing corner: $corner")
                            resizingCorner = corner
                        } else if (draggedBox.contains(imageCoords.x, imageCoords.y)) {
                            Log.d("BoundingBoxInteraction", "Moving box")
                            isDragging = true
                        }
                    }
                }, onDrag = { _, dragAmount ->
                    val boxIndex = boundingBoxes.indexOfFirst { it.id == activeBoxId }
                    if (boxIndex != -1) {
                        val activeBox = boundingBoxes[boxIndex]

                        // Scale drag amount inversely to account for zoom
                        val scaledDragAmount = androidx.compose.ui.geometry.Offset(
                            dragAmount.x / scale,
                            dragAmount.y / scale
                        )

                        // Create new box with updated coordinates
                        val updatedBox = when {
                            resizingCorner != null -> {
                                resizeBox(
                                    activeBox, resizingCorner!!, scaledDragAmount, imageDisplayBounds
                                )
                            }

                            isDragging -> {
                                moveBox(activeBox, scaledDragAmount, imageDisplayBounds)
                            }

                            else -> activeBox
                        }

                        // Replace the box in the list to trigger recomposition
                        boundingBoxes[boxIndex] = updatedBox
                        onBoxesChange(boundingBoxes.toList())
                    }
                }, onDragEnd = {
                    Log.d("BoundingBoxInteraction", "Drag ended")
                    isDragging = false
                    resizingCorner = null
                })
            })
}

/**
 * Resize a bounding box based on corner being dragged
 */
private fun resizeBox(
    box: AnnotationBox,
    corner: AnnotationBox.Corner,
    dragAmount: androidx.compose.ui.geometry.Offset,
    bounds: Rect
): AnnotationBox {
    return when (corner) {
        AnnotationBox.Corner.TOP_LEFT -> {
            box.copy(
                left = (box.left + dragAmount.x).coerceAtMost(box.right - 10f)
                    .coerceIn(bounds.left, bounds.right),
                top = (box.top + dragAmount.y).coerceAtMost(box.bottom - 10f)
                    .coerceIn(bounds.top, bounds.bottom)
            )
        }

        AnnotationBox.Corner.BOTTOM_LEFT -> {
            box.copy(
                left = (box.left + dragAmount.x).coerceAtMost(box.right - 10f)
                    .coerceIn(bounds.left, bounds.right),
                bottom = (box.bottom + dragAmount.y).coerceAtLeast(box.top + 10f)
                    .coerceIn(bounds.top, bounds.bottom)
            )
        }

        AnnotationBox.Corner.BOTTOM_RIGHT -> {
            box.copy(
                right = (box.right + dragAmount.x).coerceAtLeast(box.left + 10f)
                    .coerceIn(bounds.left, bounds.right),
                bottom = (box.bottom + dragAmount.y).coerceAtLeast(box.top + 10f)
                    .coerceIn(bounds.top, bounds.bottom)
            )
        }
    }
}

/**
 * Move a bounding box while keeping it within image bounds
 */
private fun moveBox(
    box: AnnotationBox, dragAmount: androidx.compose.ui.geometry.Offset, bounds: Rect
): AnnotationBox {
    val boxWidth = box.right - box.left
    val boxHeight = box.bottom - box.top

    val newLeft = (box.left + dragAmount.x).coerceIn(
        bounds.left, bounds.right - boxWidth
    )
    val newTop = (box.top + dragAmount.y).coerceIn(
        bounds.top, bounds.bottom - boxHeight
    )

    return box.copy(
        left = newLeft, top = newTop, right = newLeft + boxWidth, bottom = newTop + boxHeight
    )
}

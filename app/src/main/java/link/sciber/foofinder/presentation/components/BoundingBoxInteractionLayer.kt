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
 * @param modifier Modifier for the layer
 */
@Composable
fun BoundingBoxInteractionLayer(
    boundingBoxes: MutableList<AnnotationBox>,
    activeBoxId: String?,
    imageDisplayBounds: Rect,
    onActiveBoxIdChange: (String?) -> Unit,
    onBoxesChange: (List<AnnotationBox>) -> Unit,
    modifier: Modifier = Modifier
) {
    // Key these states to activeBoxId so they reset when switching boxes
    var isDragging by remember(activeBoxId) { mutableStateOf(false) }
    var resizingCorner by remember(activeBoxId) { mutableStateOf<AnnotationBox.Corner?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .pointerInput(boundingBoxes.size, activeBoxId) {
                detectTapGestures { offset ->
                    Log.d("BoundingBoxInteraction", "Tap at: (${offset.x}, ${offset.y})")

                    // Check if tapping delete button on active box
                    val activeBox = boundingBoxes.find { it.id == activeBoxId }
                    if (activeBox != null && activeBox.isOnDeleteButton(offset.x, offset.y)) {
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
                        box.contains(offset.x, offset.y) || box.isOnBorder(offset.x, offset.y)
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
            .pointerInput(boundingBoxes.size, activeBoxId, imageDisplayBounds) {
                detectDragGestures(onDragStart = { offset ->
                    Log.d("BoundingBoxInteraction", "Drag start at: (${offset.x}, ${offset.y})")

                    // Find which box we're dragging
                    val draggedBox = boundingBoxes.find { it.id == activeBoxId }

                    if (draggedBox != null) {
                        // Check if on delete button - don't allow dragging from there
                        if (draggedBox.isOnDeleteButton(offset.x, offset.y)) {
                            return@detectDragGestures
                        }

                        // Check if starting a resize or move
                        val corner = draggedBox.getCornerHandle(offset.x, offset.y)
                        if (corner != null) {
                            Log.d("BoundingBoxInteraction", "Resizing corner: $corner")
                            resizingCorner = corner
                        } else if (draggedBox.contains(offset.x, offset.y)) {
                            Log.d("BoundingBoxInteraction", "Moving box")
                            isDragging = true
                        }
                    }
                }, onDrag = { _, dragAmount ->
                    val boxIndex = boundingBoxes.indexOfFirst { it.id == activeBoxId }
                    if (boxIndex != -1) {
                        val activeBox = boundingBoxes[boxIndex]

                        // Create new box with updated coordinates
                        val updatedBox = when {
                            resizingCorner != null -> {
                                resizeBox(
                                    activeBox, resizingCorner!!, dragAmount, imageDisplayBounds
                                )
                            }

                            isDragging -> {
                                moveBox(activeBox, dragAmount, imageDisplayBounds)
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
                left = (box.left + dragAmount.x).coerceAtMost(box.right - 50f)
                    .coerceIn(bounds.left, bounds.right),
                top = (box.top + dragAmount.y).coerceAtMost(box.bottom - 50f)
                    .coerceIn(bounds.top, bounds.bottom)
            )
        }

        AnnotationBox.Corner.BOTTOM_LEFT -> {
            box.copy(
                left = (box.left + dragAmount.x).coerceAtMost(box.right - 50f)
                    .coerceIn(bounds.left, bounds.right),
                bottom = (box.bottom + dragAmount.y).coerceAtLeast(box.top + 50f)
                    .coerceIn(bounds.top, bounds.bottom)
            )
        }

        AnnotationBox.Corner.BOTTOM_RIGHT -> {
            box.copy(
                right = (box.right + dragAmount.x).coerceAtLeast(box.left + 50f)
                    .coerceIn(bounds.left, bounds.right),
                bottom = (box.bottom + dragAmount.y).coerceAtLeast(box.top + 50f)
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

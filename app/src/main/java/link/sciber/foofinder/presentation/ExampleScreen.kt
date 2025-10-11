package link.sciber.foofinder.presentation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import link.sciber.foofinder.utils.AnnotationStorageManager
import java.util.UUID
import androidx.activity.compose.BackHandler

/**
 * Data class representing a bounding box annotation
 */
data class BoundingBox(
    val id: String = UUID.randomUUID().toString(),
    var left: Float,
    var top: Float,
    var right: Float,
    var bottom: Float
) {
    fun contains(x: Float, y: Float): Boolean {
        return x >= left && x <= right && y >= top && y <= bottom
    }

    fun isOnBorder(x: Float, y: Float, threshold: Float = 20f): Boolean {
        val onLeft = x >= left - threshold && x <= left + threshold && y >= top && y <= bottom
        val onRight = x >= right - threshold && x <= right + threshold && y >= top && y <= bottom
        val onTop = y >= top - threshold && y <= top + threshold && x >= left && x <= right
        val onBottom = y >= bottom - threshold && y <= bottom + threshold && x >= left && x <= right
        return onLeft || onRight || onTop || onBottom
    }

    fun getCornerHandle(x: Float, y: Float, handleSize: Float = 30f): Corner? {
        val topLeft =
            x >= left - handleSize && x <= left + handleSize && y >= top - handleSize && y <= top + handleSize
        // Top-right is now the delete button, not a resize handle
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

    fun isOnDeleteButton(x: Float, y: Float, buttonRadius: Float = 20f): Boolean {
        // Delete button is now at the top-right corner
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

/**
 * Screen to display a saved image with navigation support
 *
 * @param imageUri URI of the saved image to display
 * @param fileName Name of the file to show in the top bar
 * @param onNavigateBack Callback when back button is pressed
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExampleScreen(
    imageUri: String, fileName: String, onNavigateBack: () -> Unit
) {
    // Save annotations when navigating back
    fun saveAnnotationsAndNavigateBack(context: Context, bitmap: android.graphics.Bitmap?, bounds: Rect, boxes: List<BoundingBox>) {
        if (bitmap != null && bounds != Rect.Zero && boxes.isNotEmpty()) {
            try {
                AnnotationStorageManager.saveAnnotations(
                    context = context,
                    fileName = fileName,
                    boundingBoxes = boxes,
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height,
                    displayLeft = bounds.left,
                    displayTop = bounds.top,
                    displayWidth = bounds.width,
                    displayHeight = bounds.height
                )
                Log.d("ExampleScreen", "Auto-saved ${boxes.size} annotations")
            } catch (e: Exception) {
                Log.e("ExampleScreen", "Failed to auto-save annotations", e)
            }
        }
        onNavigateBack()
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    // Zoom and pan state
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // Bounding box state
    val boundingBoxes = remember { mutableStateListOf<BoundingBox>() }
    var activeBoxId by remember { mutableStateOf<String?>(null) }
    var isDragging by remember { mutableStateOf(false) }
    var resizingCorner by remember { mutableStateOf<BoundingBox.Corner?>(null) }
    var imageViewSize by remember { mutableStateOf(IntSize.Zero) }
    var imageDisplayBounds by remember { mutableStateOf(Rect.Zero) }
    var deleteMessage by remember { mutableStateOf<String?>(null) }
    var isNavigatingBack by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }
    
    // Handle device back button
    BackHandler(enabled = !isNavigatingBack) {
        if (!isNavigatingBack) {
            isNavigatingBack = true
            saveAnnotationsAndNavigateBack(context, bitmap, imageDisplayBounds, boundingBoxes.toList())
        }
    }

    // Calculate actual image display bounds within the view (for ContentScale.Fit)
    fun calculateImageBounds(
        viewWidth: Float,
        viewHeight: Float,
        imageWidth: Int,
        imageHeight: Int
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

    LaunchedEffect(imageUri) {
        isLoading = true
        bitmap = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(Uri.parse(imageUri))?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            } catch (e: Exception) {
                null
            }
        }
        isLoading = false
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(fileName.substringBeforeLast(".")) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (!isNavigatingBack) {
                                isNavigatingBack = true
                                saveAnnotationsAndNavigateBack(context, bitmap, imageDisplayBounds, boundingBoxes.toList())
                            }
                        },
                        enabled = !isNavigatingBack
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    // Delete image and annotation button
                    IconButton(
                        onClick = {
                            if (!isDeleting) {
                                isDeleting = true
                                try {
                                    // Delete the image
                                    val imageUriParsed = Uri.parse(imageUri)
                                    context.contentResolver.delete(imageUriParsed, null, null)
                                    
                                    // Try to delete annotation if it exists
                                    try {
                                        val txtFileName = fileName.replace(".jpg", ".txt", ignoreCase = true)
                                            .replace(".jpeg", ".txt", ignoreCase = true)
                                            .replace(".png", ".txt", ignoreCase = true)
                                        
                                        // Query for the annotation file
                                        val projection = arrayOf(
                                            MediaStore.MediaColumns._ID,
                                            MediaStore.MediaColumns.DISPLAY_NAME
                                        )
                                        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
                                        val selectionArgs = arrayOf(txtFileName)
                                        
                                        context.contentResolver.query(
                                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                            projection,
                                            selection,
                                            selectionArgs,
                                            null
                                        )?.use { cursor ->
                                            if (cursor.moveToFirst()) {
                                                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                                                val id = cursor.getLong(idColumn)
                                                val annotationUri = Uri.withAppendedPath(
                                                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                                    id.toString()
                                                )
                                                context.contentResolver.delete(annotationUri, null, null)
                                                Log.d("ExampleScreen", "Deleted annotation: $txtFileName")
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.w("ExampleScreen", "Could not delete annotation", e)
                                    }
                                    
                                    deleteMessage = "Deleted"
                                    Log.d("ExampleScreen", "Deleted image and annotation")
                                    
                                    // Navigate back after short delay
                                    scope.launch {
                                        kotlinx.coroutines.delay(500)
                                        onNavigateBack()
                                    }
                                } catch (e: Exception) {
                                    deleteMessage = "Failed to delete: ${e.message}"
                                    Log.e("ExampleScreen", "Failed to delete", e)
                                    isDeleting = false
                                }
                            }
                        },
                        enabled = !isDeleting
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete image and annotations",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Image viewing area
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                ) {
                when {
                    isLoading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    bitmap != null -> {
                        // Calculate image display bounds
                        LaunchedEffect(imageViewSize, bitmap) {
                            if (imageViewSize.width > 0 && imageViewSize.height > 0) {
                                imageDisplayBounds = calculateImageBounds(
                                    imageViewSize.width.toFloat(),
                                    imageViewSize.height.toFloat(),
                                    bitmap!!.width,
                                    bitmap!!.height
                                )
                            }
                        }

                        // Fixed square viewport with zoom and pan
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clipToBounds()
                                .onSizeChanged { size ->
                                    imageViewSize = size
                                }) {
                            // Image that zooms and pans within the fixed viewport
                            Image(
                                bitmap = bitmap!!.asImageBitmap(),
                                contentDescription = "Saved image",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer(
                                        scaleX = scale,
                                        scaleY = scale,
                                        translationX = offsetX,
                                        translationY = offsetY
                                    ),
                                contentScale = ContentScale.Fit
                            )

                            // Bounding box overlay - Canvas for drawing
                            Canvas(
                                modifier = Modifier.fillMaxSize()
                            ) {
                                // Draw all bounding boxes within image bounds
                                boundingBoxes.forEach { box ->
                                    val isActive = box.id == activeBoxId
                                    val strokeColor =
                                        if (isActive) Color(0xFF64B5F6) else Color(0xFF1976D2)
                                    val strokeWidth = if (isActive) 5f else 3f

                                    // Draw box
                                    drawRect(
                                        color = strokeColor,
                                        topLeft = Offset(box.left, box.top),
                                        size = Size(box.right - box.left, box.bottom - box.top),
                                        style = Stroke(width = strokeWidth)
                                    )

                                    // Draw corner handles and delete button for active box
                                    if (box.id == activeBoxId) {
                                        // Corner handles (only 3 - top-right is replaced by delete button)
                                        val handleSize = 15f
                                        listOf(
                                            Offset(box.left, box.top),        // Top-left
                                            Offset(box.left, box.bottom),     // Bottom-left
                                            Offset(box.right, box.bottom)     // Bottom-right
                                        ).forEach { corner ->
                                            drawCircle(
                                                color = Color(0xFF2196F3),
                                                radius = handleSize,
                                                center = corner,
                                                style = Fill
                                            )
                                            drawCircle(
                                                color = Color.White,
                                                radius = handleSize - 3f,
                                                center = corner,
                                                style = Stroke(width = 2f)
                                            )
                                        }

                                        // Delete button at top-right corner (replaces resize handle)
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
                                            start = Offset(
                                                deleteButtonX - xSize,
                                                deleteButtonY - xSize
                                            ),
                                            end = Offset(
                                                deleteButtonX + xSize,
                                                deleteButtonY + xSize
                                            ),
                                            strokeWidth = 3f
                                        )
                                        drawLine(
                                            color = Color.White,
                                            start = Offset(
                                                deleteButtonX + xSize,
                                                deleteButtonY - xSize
                                            ),
                                            end = Offset(
                                                deleteButtonX - xSize,
                                                deleteButtonY + xSize
                                            ),
                                            strokeWidth = 3f
                                        )
                                    }
                                }
                            }

                            // Transparent interaction layer on top
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Transparent)
                                    .pointerInput("tap") {
                                        detectTapGestures { offset ->
                                            Log.d(
                                                "ExampleScreen",
                                                "Tap at: (${offset.x}, ${offset.y})"
                                            )

                                            // Check if tapping delete button on active box
                                            val activeBox =
                                                boundingBoxes.find { it.id == activeBoxId }
                                            if (activeBox != null && activeBox.isOnDeleteButton(
                                                    offset.x, offset.y
                                                )
                                            ) {
                                                Log.d(
                                                    "ExampleScreen",
                                                    "Delete button tapped for box: ${activeBox.id}"
                                                )
                                                boundingBoxes.remove(activeBox)
                                                activeBoxId = null
                                                return@detectTapGestures
                                            }

                                            // Find which box we're tapping
                                            val tappedBox = boundingBoxes.findLast { box ->
                                                box.contains(offset.x, offset.y) || box.isOnBorder(
                                                    offset.x, offset.y
                                                )
                                            }

                                            if (tappedBox != null) {
                                                Log.d(
                                                    "ExampleScreen", "Selected box: ${tappedBox.id}"
                                                )
                                                activeBoxId = tappedBox.id
                                            } else {
                                                // Deselect if tapping empty area
                                                Log.d("ExampleScreen", "Deselected")
                                                activeBoxId = null
                                            }
                                        }
                                    }
                                    .pointerInput("drag") {
                                        detectDragGestures(onDragStart = { offset ->
                                            Log.d(
                                                "ExampleScreen",
                                                "Drag start at: (${offset.x}, ${offset.y})"
                                            )

                                            // Find which box we're dragging
                                            val draggedBox =
                                                boundingBoxes.find { it.id == activeBoxId }

                                            if (draggedBox != null) {
                                                // Check if on delete button - don't allow dragging from there
                                                if (draggedBox.isOnDeleteButton(
                                                        offset.x, offset.y
                                                    )
                                                ) {
                                                    // Delete button click handled by tap gesture, ignore drag
                                                    return@detectDragGestures
                                                }

                                                // Check if starting a resize or move
                                                val corner = draggedBox.getCornerHandle(
                                                    offset.x, offset.y
                                                )
                                                if (corner != null) {
                                                    Log.d(
                                                        "ExampleScreen", "Resizing corner: $corner"
                                                    )
                                                    resizingCorner = corner
                                                } else if (draggedBox.contains(
                                                        offset.x, offset.y
                                                    )
                                                ) {
                                                    Log.d("ExampleScreen", "Moving box")
                                                    isDragging = true
                                                }
                                            }
                                        }, onDrag = { _, dragAmount ->
                                            val boxIndex =
                                                boundingBoxes.indexOfFirst { it.id == activeBoxId }
                                            if (boxIndex != -1) {
                                                val activeBox = boundingBoxes[boxIndex]

                                                // Create new box with updated coordinates to trigger recomposition
                                                val updatedBox = if (resizingCorner != null) {
                                                    // Resize the box (no TOP_RIGHT since it's the delete button)
                                                    when (resizingCorner) {
                                                        BoundingBox.Corner.TOP_LEFT -> {
                                                            activeBox.copy(
                                                                left = (activeBox.left + dragAmount.x).coerceAtMost(
                                                                    activeBox.right - 50f
                                                                ).coerceIn(
                                                                    imageDisplayBounds.left,
                                                                    imageDisplayBounds.right
                                                                ),
                                                                top = (activeBox.top + dragAmount.y).coerceAtMost(
                                                                    activeBox.bottom - 50f
                                                                ).coerceIn(
                                                                    imageDisplayBounds.top,
                                                                    imageDisplayBounds.bottom
                                                                )
                                                            )
                                                        }

                                                        BoundingBox.Corner.BOTTOM_LEFT -> {
                                                            activeBox.copy(
                                                                left = (activeBox.left + dragAmount.x).coerceAtMost(
                                                                    activeBox.right - 50f
                                                                ).coerceIn(
                                                                    imageDisplayBounds.left,
                                                                    imageDisplayBounds.right
                                                                ),
                                                                bottom = (activeBox.bottom + dragAmount.y).coerceAtLeast(
                                                                    activeBox.top + 50f
                                                                ).coerceIn(
                                                                    imageDisplayBounds.top,
                                                                    imageDisplayBounds.bottom
                                                                )
                                                            )
                                                        }

                                                        BoundingBox.Corner.BOTTOM_RIGHT -> {
                                                            activeBox.copy(
                                                                right = (activeBox.right + dragAmount.x).coerceAtLeast(
                                                                    activeBox.left + 50f
                                                                ).coerceIn(
                                                                    imageDisplayBounds.left,
                                                                    imageDisplayBounds.right
                                                                ),
                                                                bottom = (activeBox.bottom + dragAmount.y).coerceAtLeast(
                                                                    activeBox.top + 50f
                                                                ).coerceIn(
                                                                    imageDisplayBounds.top,
                                                                    imageDisplayBounds.bottom
                                                                )
                                                            )
                                                        }

                                                        else -> activeBox
                                                    }
                                                } else if (isDragging) {
                                                    // Move the box
                                                    val boxWidth = activeBox.right - activeBox.left
                                                    val boxHeight = activeBox.bottom - activeBox.top

                                                    val newLeft =
                                                        (activeBox.left + dragAmount.x).coerceIn(
                                                            imageDisplayBounds.left,
                                                            imageDisplayBounds.right - boxWidth
                                                        )
                                                    val newTop =
                                                        (activeBox.top + dragAmount.y).coerceIn(
                                                            imageDisplayBounds.top,
                                                            imageDisplayBounds.bottom - boxHeight
                                                        )

                                                    activeBox.copy(
                                                        left = newLeft,
                                                        top = newTop,
                                                        right = newLeft + boxWidth,
                                                        bottom = newTop + boxHeight
                                                    )
                                                } else {
                                                    activeBox
                                                }

                                                // Replace the box in the list to trigger recomposition
                                                boundingBoxes[boxIndex] = updatedBox
                                            }
                                        }, onDragEnd = {
                                            Log.d("ExampleScreen", "Drag ended")
                                            isDragging = false
                                            resizingCorner = null
                                        })
                                    })
                        }
                    }

                    else -> {
                        Box(
                            modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Failed to load image",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }

            // Spacer above FAB
            Spacer(modifier = Modifier.weight(1f))

            // Annotation FAB - centered between image and bottom
            Box(
                modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center
            ) {
                FloatingActionButton(
                    onClick = {
                        // Add new bounding box in center of actual image display area
                        Log.d(
                            "ExampleScreen",
                            "FAB clicked. ImageDisplayBounds: $imageDisplayBounds"
                        )
                        if (imageDisplayBounds != Rect.Zero) {
                            val boxSize = 200f.coerceAtMost(
                                imageDisplayBounds.width * 0.5f
                            ).coerceAtMost(
                                imageDisplayBounds.height * 0.5f
                            )
                            val centerX = imageDisplayBounds.center.x
                            val centerY = imageDisplayBounds.center.y

                            val newBox = BoundingBox(
                                left = centerX - boxSize / 2,
                                top = centerY - boxSize / 2,
                                right = centerX + boxSize / 2,
                                bottom = centerY + boxSize / 2
                            )
                            boundingBoxes.add(newBox)
                            activeBoxId = newBox.id
                            Log.d(
                                "ExampleScreen",
                                "Added box: left=${newBox.left}, top=${newBox.top}, right=${newBox.right}, bottom=${newBox.bottom}"
                            )
                            Log.d("ExampleScreen", "Total boxes: ${boundingBoxes.size}")
                        } else {
                            Log.d("ExampleScreen", "ImageDisplayBounds is Zero!")
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add bounding box",
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            // Spacer below FAB
            Spacer(modifier = Modifier.weight(1f))
            }
            
            // Delete message overlay (outside Column, on top of everything)
            deleteMessage?.let { message ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Text(
                        text = message,
                        modifier = Modifier
                            .background(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = MaterialTheme.shapes.small
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                // Clear message after 2 seconds
                LaunchedEffect(message) {
                    kotlinx.coroutines.delay(2000)
                    deleteMessage = null
                }
            }
        }
    }
}

package link.sciber.foofinder.presentation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import link.sciber.foofinder.domain.AnnotationBox
import link.sciber.foofinder.presentation.components.AnnotationTopBar
import link.sciber.foofinder.presentation.components.BoundingBoxCanvas
import link.sciber.foofinder.presentation.components.BoundingBoxInteractionLayer
import link.sciber.foofinder.utils.AnnotationStorageManager
import link.sciber.foofinder.utils.ImageBoundsCalculator

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
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    // Zoom and pan state - reset when image changes
    var scale by remember(imageUri) { mutableFloatStateOf(1f) }
    var offsetX by remember(imageUri) { mutableFloatStateOf(0f) }
    var offsetY by remember(imageUri) { mutableFloatStateOf(0f) }

    // Bounding box state - keyed to imageUri to reset when viewing different images
    val boundingBoxes = remember(imageUri) { mutableStateListOf<AnnotationBox>() }
    var activeBoxId by remember(imageUri) { mutableStateOf<String?>(null) }
    var imageViewSize by remember(imageUri) { mutableStateOf(IntSize.Zero) }
    var imageDisplayBounds by remember(imageUri) { mutableStateOf(Rect.Zero) }

    // UI state
    var deleteMessage by remember { mutableStateOf<String?>(null) }
    var isNavigatingBack by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }

    // Save annotations and navigate back
    fun saveAnnotationsAndNavigateBack() {
        // Always save annotations (even if empty) to handle deletions
        if (bitmap != null && imageDisplayBounds != Rect.Zero) {
            try {
                AnnotationStorageManager.saveAnnotations(
                    context = context,
                    fileName = fileName,
                    boundingBoxes = boundingBoxes,
                    imageWidth = bitmap!!.width,
                    imageHeight = bitmap!!.height,
                    displayLeft = imageDisplayBounds.left,
                    displayTop = imageDisplayBounds.top,
                    displayWidth = imageDisplayBounds.width,
                    displayHeight = imageDisplayBounds.height
                )
                Log.d("ExampleScreen", "Auto-saved ${boundingBoxes.size} annotations")
            } catch (e: Exception) {
                Log.e("ExampleScreen", "Failed to auto-save annotations", e)
            }
        }
        onNavigateBack()
    }

    // Handle device back button
    BackHandler(enabled = !isNavigatingBack) {
        if (!isNavigatingBack) {
            isNavigatingBack = true
            saveAnnotationsAndNavigateBack()
        }
    }

    // Load image from URI
    LaunchedEffect(imageUri) {
        isLoading = true
        bitmap = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(imageUri.toUri())?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            } catch (e: Exception) {
                Log.e("ExampleScreen", "Failed to load image", e)
                null
            }
        }
        isLoading = false
    }

    // Calculate image bounds when size changes
    LaunchedEffect(imageViewSize, bitmap) {
        if (imageViewSize.width > 0 && imageViewSize.height > 0 && bitmap != null) {
            imageDisplayBounds = ImageBoundsCalculator.calculateImageBounds(
                imageViewSize.width.toFloat(),
                imageViewSize.height.toFloat(),
                bitmap!!.width,
                bitmap!!.height
            )
        }
    }

    // Load existing annotations when image and bounds are ready
    LaunchedEffect(imageUri, bitmap, imageDisplayBounds) {
        if (bitmap != null && imageDisplayBounds != Rect.Zero) {
            // Clear any existing boxes first
            boundingBoxes.clear()

            val loadedBoxes = withContext(Dispatchers.IO) {
                try {
                    AnnotationStorageManager.loadAnnotations(
                        context = context,
                        fileName = fileName,
                        imageWidth = bitmap!!.width,
                        imageHeight = bitmap!!.height,
                        displayLeft = imageDisplayBounds.left,
                        displayTop = imageDisplayBounds.top,
                        displayWidth = imageDisplayBounds.width,
                        displayHeight = imageDisplayBounds.height
                    )
                } catch (e: Exception) {
                    Log.e("ExampleScreen", "Failed to load annotations", e)
                    emptyList()
                }
            }
            boundingBoxes.addAll(loadedBoxes)
            Log.d("ExampleScreen", "Loaded ${loadedBoxes.size} annotations")
        }
    }

    Scaffold(
        topBar = {
            AnnotationTopBar(
                fileName = fileName,
                imageUri = imageUri,
                onNavigateBack = { saveAnnotationsAndNavigateBack() },
                onDeleteComplete = onNavigateBack,
                isNavigatingBack = isNavigatingBack,
                isDeleting = isDeleting,
                onIsNavigatingBackChange = { isNavigatingBack = it },
                onIsDeletingChange = { isDeleting = it },
                onDeleteMessage = { deleteMessage = it })
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
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            }
                        }

                        bitmap != null -> {
                            // Transformable state for pinch-to-zoom and pan
                            val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
                                // Update scale with limits
                                val newScale = (scale * zoomChange).coerceIn(1f, 5f)
                                scale = newScale

                                // Update pan offset
                                offsetX += panChange.x
                                offsetY += panChange.y

                                // Constrain pan within reasonable bounds
                                val maxOffset = imageViewSize.width * (scale - 1f) / 2f
                                offsetX = offsetX.coerceIn(-maxOffset, maxOffset)
                                offsetY = offsetY.coerceIn(-maxOffset, maxOffset)
                            }

                            // Fixed square viewport with zoom and pan
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clipToBounds()
                                    .onSizeChanged { size ->
                                        imageViewSize = size
                                    }
                                    .transformable(state = transformableState)
                            ) {
                                // Image with zoom and pan support
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

                                // Bounding box canvas overlay with coordinate transformation
                                BoundingBoxCanvas(
                                    boundingBoxes = boundingBoxes,
                                    activeBoxId = activeBoxId,
                                    scale = scale,
                                    offsetX = offsetX,
                                    offsetY = offsetY
                                )

                                // Interaction layer for tap and drag gestures
                                BoundingBoxInteractionLayer(
                                    boundingBoxes = boundingBoxes,
                                    activeBoxId = activeBoxId,
                                    imageDisplayBounds = imageDisplayBounds,
                                    onActiveBoxIdChange = { activeBoxId = it },
                                    onBoxesChange = { /* Trigger recomposition */ },
                                    scale = scale,
                                    offsetX = offsetX,
                                    offsetY = offsetY
                                )
                            }
                        }

                        else -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
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

                // Add annotation FAB - centered between image and bottom
                Box(
                    modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center
                ) {
                    FloatingActionButton(
                        onClick = {
                            // Add new bounding box in center of image display area
                            Log.d(
                                "ExampleScreen",
                                "FAB clicked. ImageDisplayBounds: $imageDisplayBounds"
                            )
                            if (imageDisplayBounds != Rect.Zero) {
                                val boxSize = 200f.coerceAtMost(imageDisplayBounds.width * 0.5f)
                                    .coerceAtMost(imageDisplayBounds.height * 0.5f)
                                val centerX = imageDisplayBounds.center.x
                                val centerY = imageDisplayBounds.center.y

                                val newBox = AnnotationBox(
                                    left = centerX - boxSize / 2,
                                    top = centerY - boxSize / 2,
                                    right = centerX + boxSize / 2,
                                    bottom = centerY + boxSize / 2
                                )
                                boundingBoxes.add(newBox)
                                activeBoxId = newBox.id
                                Log.d(
                                    "ExampleScreen",
                                    "Added box: ${newBox.id}. Total: ${boundingBoxes.size}"
                                )
                            } else {
                                Log.w(
                                    "ExampleScreen", "ImageDisplayBounds is Zero - cannot add box"
                                )
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

            // Delete message overlay
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

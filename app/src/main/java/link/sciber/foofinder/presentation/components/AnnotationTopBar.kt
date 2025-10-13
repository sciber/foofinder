package link.sciber.foofinder.presentation.components

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import kotlinx.coroutines.launch

/**
 * Top app bar for annotation screen with back navigation and delete functionality
 *
 * @param fileName Name of the file being annotated
 * @param imageUri URI of the image
 * @param onNavigateBack Callback when back button is pressed
 * @param onDeleteComplete Callback when deletion is complete
 * @param isNavigatingBack Whether navigation back is in progress
 * @param isDeleting Whether deletion is in progress
 * @param onIsNavigatingBackChange Callback to update navigation state
 * @param onIsDeletingChange Callback to update deletion state
 * @param onDeleteMessage Callback to show delete message
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotationTopBar(
    fileName: String,
    imageUri: String,
    onNavigateBack: () -> Unit,
    onDeleteComplete: () -> Unit,
    isNavigatingBack: Boolean,
    isDeleting: Boolean,
    onIsNavigatingBackChange: (Boolean) -> Unit,
    onIsDeletingChange: (Boolean) -> Unit,
    onDeleteMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Example?") },
            text = { Text("This will permanently delete the image and annotations.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        if (!isDeleting) {
                            onIsDeletingChange(true)
                            handleImageDeletion(
                                context = context,
                                imageUri = imageUri,
                                fileName = fileName,
                                onDeleteMessage = onDeleteMessage,
                                onDeleteComplete = {
                                    scope.launch {
                                        kotlinx.coroutines.delay(500)
                                        onDeleteComplete()
                                    }
                                },
                                onError = { onIsDeletingChange(false) })
                        }
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    TopAppBar(
        navigationIcon = {
            IconButton(
                onClick = {
                    if (!isNavigatingBack) {
                        onIsNavigatingBackChange(true)
                        onNavigateBack()
                    }
                }, enabled = !isNavigatingBack
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back"
                )
            }
        },
        title = {
            Text(
                text = fileName.substringBeforeLast("."),
                fontWeight = FontWeight.SemiBold
            )
        },
        actions = {
            // Delete image and annotation button
            IconButton(
                onClick = { showDeleteDialog = true },
                enabled = !isDeleting
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete image and annotations",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background
        )
    )
}

/**
 * Handle deletion of image and associated annotation file
 */
private fun handleImageDeletion(
    context: Context,
    imageUri: String,
    fileName: String,
    onDeleteMessage: (String) -> Unit,
    onDeleteComplete: () -> Unit,
    onError: () -> Unit
) {
    try {
        // Delete the image
        val imageUriParsed = imageUri.toUri()
        context.contentResolver.delete(imageUriParsed, null, null)

        // Try to delete annotation if it exists (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            deleteAnnotationFile(context, fileName)
        }

        onDeleteMessage("Deleted")
        Log.d("AnnotationTopBar", "Deleted image and annotation")
        onDeleteComplete()
    } catch (e: Exception) {
        onDeleteMessage("Failed to delete: ${e.message}")
        Log.e("AnnotationTopBar", "Failed to delete", e)
        onError()
    }
}

/**
 * Delete annotation file from MediaStore
 * Only works on API 29+ where MediaStore.Downloads is available
 */
private fun deleteAnnotationFile(context: Context, fileName: String) {
    // MediaStore.Downloads.EXTERNAL_CONTENT_URI requires API 29+
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        Log.d("AnnotationTopBar", "Annotation deletion not supported on API < 29")
        return
    }

    try {
        val txtFileName = fileName.replace(".jpg", ".txt", ignoreCase = true)
            .replace(".jpeg", ".txt", ignoreCase = true).replace(".png", ".txt", ignoreCase = true)

        // Query for the annotation file
        val projection = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME
        )
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(txtFileName)

        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val id = cursor.getLong(idColumn)
                val annotationUri = Uri.withAppendedPath(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString()
                )
                context.contentResolver.delete(annotationUri, null, null)
                Log.d("AnnotationTopBar", "Deleted annotation: $txtFileName")
            }
        }
    } catch (e: Exception) {
        Log.w("AnnotationTopBar", "Could not delete annotation", e)
    }
}

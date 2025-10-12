package link.sciber.foofinder.utils

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import link.sciber.foofinder.domain.AnnotationBox
import java.io.IOException
import java.util.Locale

private const val LABELS_SUBDIR = "Datasets/FooFinder/labels"

/**
 * Manages saving YOLO format annotations to device storage
 */
object AnnotationStorageManager {

    /**
     * Finds an existing annotation file by name in MediaStore
     *
     * @return URI of existing file, or null if not found
     */
    private fun findExistingAnnotationUri(
        resolver: ContentResolver, collection: Uri, fileName: String
    ): Uri? {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(fileName)

        return try {
            resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val id = cursor.getLong(idColumn)
                    Uri.withAppendedPath(collection, id.toString())
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Saves bounding boxes in YOLO format
     *
     * Format: <class_id> <x_center> <y_center> <width> <height>
     * All coordinates are normalized to [0, 1]
     *
     * @param context Android context
     * @param fileName Base filename (e.g., "FooFinder_20241011_123456.jpg")
     * @param boundingBoxes List of annotation boxes in display coordinates
     * @param imageWidth Original image width in pixels
     * @param imageHeight Original image height in pixels
     * @return URI of the saved annotation file
     * @throws IOException if save operation fails
     */
    fun saveAnnotations(
        context: Context,
        fileName: String,
        boundingBoxes: List<AnnotationBox>,
        imageWidth: Int,
        imageHeight: Int,
        displayLeft: Float,
        displayTop: Float,
        displayWidth: Float,
        displayHeight: Float
    ): Uri {
        // Convert filename from .jpg to .txt
        val txtFileName = fileName.replace(".jpg", ".txt", ignoreCase = true)
            .replace(".jpeg", ".txt", ignoreCase = true).replace(".png", ".txt", ignoreCase = true)

        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$LABELS_SUBDIR"
        val resolver = context.contentResolver

        // Convert bounding boxes to YOLO format
        val yoloLines = boundingBoxes.joinToString("\n") { box ->
            // Convert from display coordinates to image coordinates
            val imgLeft = ((box.left - displayLeft) / displayWidth) * imageWidth
            val imgTop = ((box.top - displayTop) / displayHeight) * imageHeight
            val imgRight = ((box.right - displayLeft) / displayWidth) * imageWidth
            val imgBottom = ((box.bottom - displayTop) / displayHeight) * imageHeight

            // Convert to YOLO format (normalized center x, center y, width, height)
            val centerX = ((imgLeft + imgRight) / 2f) / imageWidth
            val centerY = ((imgTop + imgBottom) / 2f) / imageHeight
            val width = (imgRight - imgLeft) / imageWidth
            val height = (imgBottom - imgTop) / imageHeight

            // YOLO format: class_id x_center y_center width height
            // Using class_id = 0 (single class "poo")
            // Use Locale.US to ensure period as decimal separator
            "0 %.6f %.6f %.6f %.6f".format(Locale.US, centerX, centerY, width, height)
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, txtFileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Files.getContentUri("external")
        }

        // Check if annotation file already exists
        val existingUri = findExistingAnnotationUri(resolver, collection, txtFileName)

        val uri = if (existingUri != null) {
            // Update existing file
            existingUri
        } else {
            // Create new file
            resolver.insert(collection, values)
                ?: throw IOException("Failed to create MediaStore entry for annotation")
        }

        try {
            // Write content (overwrites existing content if updating)
            resolver.openOutputStream(uri, "wt")?.use { output ->
                output.write(yoloLines.toByteArray())
            } ?: throw IOException("Failed to open annotation output stream")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && existingUri == null) {
                // Only set IS_PENDING to 0 for newly created files
                ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }.also { resolver.update(uri, it, null, null) }
            }

            return uri
        } catch (e: Exception) {
            // Only delete if we just created it
            if (existingUri == null) {
                resolver.delete(uri, null, null)
            }
            throw e
        }
    }

    /**
     * Loads bounding boxes from YOLO format annotation file
     *
     * @param context Android context
     * @param fileName Base filename (e.g., "241011_123456.jpg")
     * @param imageWidth Original image width in pixels
     * @param imageHeight Original image height in pixels
     * @param displayLeft Left position of image in display coordinates
     * @param displayTop Top position of image in display coordinates
     * @param displayWidth Width of image in display coordinates
     * @param displayHeight Height of image in display coordinates
     * @return List of annotation boxes in display coordinates
     */
    fun loadAnnotations(
        context: Context,
        fileName: String,
        imageWidth: Int,
        imageHeight: Int,
        displayLeft: Float,
        displayTop: Float,
        displayWidth: Float,
        displayHeight: Float
    ): List<AnnotationBox> {
        // Convert filename from .jpg to .txt
        val txtFileName = fileName.replace(".jpg", ".txt", ignoreCase = true)
            .replace(".jpeg", ".txt", ignoreCase = true).replace(".png", ".txt", ignoreCase = true)

        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Files.getContentUri("external")
        }

        val projection = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME
        )
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(txtFileName)

        val boxes = mutableListOf<AnnotationBox>()

        try {
            resolver.query(
                collection, projection, selection, selectionArgs, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val id = cursor.getLong(idColumn)
                    val annotationUri = Uri.withAppendedPath(collection, id.toString())

                    // Read annotation file content
                    resolver.openInputStream(annotationUri)?.use { input ->
                        val content = input.bufferedReader().readText()

                        // Parse YOLO format lines
                        content.lines().forEach { line ->
                            val trimmed = line.trim()
                            if (trimmed.isNotEmpty()) {
                                val parts = trimmed.split("\\s+".toRegex())
                                if (parts.size >= 5) {
                                    try {
                                        // Parse YOLO format: class_id x_center y_center width height
                                        val centerX = parts[1].toFloat()
                                        val centerY = parts[2].toFloat()
                                        val width = parts[3].toFloat()
                                        val height = parts[4].toFloat()

                                        // Convert from normalized YOLO to image coordinates
                                        val imgCenterX = centerX * imageWidth
                                        val imgCenterY = centerY * imageHeight
                                        val imgWidth = width * imageWidth
                                        val imgHeight = height * imageHeight

                                        val imgLeft = imgCenterX - imgWidth / 2f
                                        val imgTop = imgCenterY - imgHeight / 2f
                                        val imgRight = imgCenterX + imgWidth / 2f
                                        val imgBottom = imgCenterY + imgHeight / 2f

                                        // Convert from image coordinates to display coordinates
                                        val displayBoxLeft =
                                            displayLeft + (imgLeft / imageWidth) * displayWidth
                                        val displayBoxTop =
                                            displayTop + (imgTop / imageHeight) * displayHeight
                                        val displayBoxRight =
                                            displayLeft + (imgRight / imageWidth) * displayWidth
                                        val displayBoxBottom =
                                            displayTop + (imgBottom / imageHeight) * displayHeight

                                        boxes.add(
                                            AnnotationBox(
                                                left = displayBoxLeft,
                                                top = displayBoxTop,
                                                right = displayBoxRight,
                                                bottom = displayBoxBottom
                                            )
                                        )
                                    } catch (e: NumberFormatException) {
                                        // Skip malformed lines
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Return empty list on error
            e.printStackTrace()
        }

        return boxes
    }
}

package link.sciber.foofinder.utils

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

        val uri = resolver.insert(collection, values)
            ?: throw IOException("Failed to create MediaStore entry for annotation")

        try {
            resolver.openOutputStream(uri)?.use { output ->
                output.write(yoloLines.toByteArray())
            } ?: throw IOException("Failed to open annotation output stream")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }.also { resolver.update(uri, it, null, null) }
            }

            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}

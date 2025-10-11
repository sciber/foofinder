package link.sciber.foofinder.utils

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val SNAPSHOT_SUBDIR = "Datasets/FooFinder/images"

/**
 * Result of a successful image save operation
 */
data class SaveOutcome(
    val uri: Uri,
    val displayName: String,
    val locationDescription: String
)

/**
 * Manages saving detection tiles to device storage and opening images
 */
object ImageStorageManager {
    
    /**
     * Saves a detection tile bitmap to the device gallery
     * 
     * @param context Android context
     * @param bitmap The detection tile bitmap to save
     * @return SaveOutcome containing URI and metadata
     * @throws IOException if save operation fails
     */
    fun saveDetectionTile(context: Context, bitmap: Bitmap): SaveOutcome {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val displayName = "FooFinder_$timestamp.jpg"
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$SNAPSHOT_SUBDIR"
        val resolver = context.contentResolver

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                val targetDir = File(downloadsDir, SNAPSHOT_SUBDIR)
                if (!targetDir.exists() && !targetDir.mkdirs()) {
                    throw IOException("Unable to create directory: ${targetDir.absolutePath}")
                }
                val legacyPath = File(targetDir, displayName).absolutePath
                put(MediaStore.MediaColumns.DATA, legacyPath)
            }
        }

        // Use MediaStore.Downloads for Download directory on Android Q+
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Files.getContentUri("external")
        }

        val uri = resolver.insert(collection, values)
            ?: throw IOException("Failed to create MediaStore entry")

        try {
            resolver.openOutputStream(uri)?.use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) {
                    throw IOException("Failed to compress bitmap")
                }
            } ?: throw IOException("Failed to open image output stream")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }.also { resolver.update(uri, it, null, null) }
            } else {
                val legacyPath = values.getAsString(MediaStore.MediaColumns.DATA)
                MediaScannerConnection.scanFile(
                    context, arrayOf(legacyPath), arrayOf("image/jpeg"), null
                )
            }

            return SaveOutcome(
                uri = uri,
                displayName = displayName,
                locationDescription = relativePath
            )
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /**
     * Opens an image in the device's default image viewer
     * 
     * @param context Android context
     * @param uri URI of the image to open
     */
    fun openImagePreview(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        } else {
            Toast.makeText(
                context,
                "No app found to open saved image",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}

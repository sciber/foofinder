package link.sciber.foofinder.utils

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val SNAPSHOT_SUBDIR = "Datasets/FooFinder/images"

/**
 * Result of a successful image save operation
 */
data class SaveOutcome(
    val uri: Uri, val displayName: String, val locationDescription: String
)

/**
 * Represents a saved image in the dataset
 */
data class SavedImage(
    val uri: Uri, val displayName: String, val dateModified: Long
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
        val timestamp = SimpleDateFormat("yyMMdd_HHmmss", Locale.US).format(Date())
        val displayName = "$timestamp.jpg"
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
                uri = uri, displayName = displayName, locationDescription = relativePath
            )
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /**
     * Queries all saved images from the FooFinder dataset directory
     *
     * @param context Android context
     * @return List of SavedImage sorted by date (newest first)
     */
    fun getSavedImages(context: Context): List<SavedImage> {
        val images = mutableListOf<SavedImage>()
        val resolver = context.contentResolver

        // First, trigger a media scan to ensure MediaStore is up-to-date
        scanDatasetDirectory(context)

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Files.getContentUri("external")
        }

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.RELATIVE_PATH
        )

        // On Android Q+, RELATIVE_PATH format is "Download/Datasets/FooFinder/images/"
        // Note the leading "Download/" and trailing "/"
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.MIME_TYPE} LIKE ?"
        } else {
            "${MediaStore.MediaColumns.DATA} LIKE ?"
        }

        val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Match paths containing our subdirectory (with or without leading Download/)
            arrayOf("%$SNAPSHOT_SUBDIR%", "image/%")
        } else {
            arrayOf("%$SNAPSHOT_SUBDIR%")
        }

        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

        try {
            resolver.query(
                collection, projection, selection, selectionArgs, sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn)
                    val date = cursor.getLong(dateColumn)

                    val uri = Uri.withAppendedPath(collection, id.toString())

                    // Validate that the file actually exists (filter out stale MediaStore entries)
                    if (isUriAccessible(resolver, uri)) {
                        images.add(SavedImage(uri, name, date))
                    }
                }
            }
        } catch (e: Exception) {
            // Return empty list on error
            e.printStackTrace()
        }

        return images
    }

    /**
     * Checks if a URI is accessible (file still exists)
     */
    private fun isUriAccessible(resolver: ContentResolver, uri: Uri): Boolean {
        return try {
            resolver.openInputStream(uri)?.use { true } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Scans the dataset directory to update MediaStore with existing files
     * Only needed for pre-Android Q or for manually copied files
     */
    private fun scanDatasetDirectory(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // On Android Q+, files saved through MediaStore are automatically tracked
            return
        }

        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            val targetDir = File(downloadsDir, SNAPSHOT_SUBDIR)

            if (targetDir.exists() && targetDir.isDirectory) {
                val imageFiles = targetDir.listFiles { file ->
                    file.isFile && file.extension.lowercase() in listOf("jpg", "jpeg", "png")
                }

                if (imageFiles != null && imageFiles.isNotEmpty()) {
                    // Use CountDownLatch to wait for all scans to complete
                    val latch = CountDownLatch(imageFiles.size)

                    imageFiles.forEach { file ->
                        MediaScannerConnection.scanFile(
                            context, arrayOf(file.absolutePath), arrayOf("image/jpeg")
                        ) { _, _ ->
                            latch.countDown()
                        }
                    }

                    // Wait up to 5 seconds for all scans to complete
                    latch.await(5, TimeUnit.SECONDS)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

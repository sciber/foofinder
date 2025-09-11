package link.sciber.foofinder.utils

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object CameraResolutionUtils {

    /** Get available camera resolutions using CameraX and Camera2 APIs */
    suspend fun getAvailableResolutions(context: Context): List<Size> {
        return suspendCancellableCoroutine { continuation ->
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener(
                    {
                        try {
                            val cameraProvider = cameraProviderFuture.get()
                            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                            val cameraInfo =
                                    cameraProvider.availableCameraInfos.find { info ->
                                        cameraSelector.filter(listOf(info)).isNotEmpty()
                                    }

                            val resolutions =
                                    cameraInfo?.let { info ->
                                        getResolutionsFromCamera2Api(context)
                                    }
                                            ?: getFallbackResolutions()

                            continuation.resume(resolutions)
                        } catch (e: Exception) {
                            e.printStackTrace()
                            continuation.resume(getFallbackResolutions())
                        }
                    },
                    ContextCompat.getMainExecutor(context)
            )
        }
    }

    /** Get resolutions using Camera2 API for more accurate results */
    private fun getResolutionsFromCamera2Api(context: Context): List<Size> {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId =
                    cameraManager.cameraIdList.firstOrNull { id ->
                        val characteristics = cameraManager.getCameraCharacteristics(id)
                        characteristics.get(CameraCharacteristics.LENS_FACING) ==
                                CameraCharacteristics.LENS_FACING_BACK
                    }

            cameraId?.let { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val streamConfigurationMap =
                        characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

                streamConfigurationMap?.let { configMap ->
                    // Get supported preview sizes for SurfaceTexture (used by CameraX Preview)
                    val supportedSizes = configMap.getOutputSizes(SurfaceTexture::class.java)
                    val resolutions =
                            supportedSizes
                                    ?.map { size ->
                                        // For portrait mode, swap width/height if needed to ensure
                                        // height > width
                                        if (size.width > size.height) {
                                            Size(size.height, size.width) // Portrait orientation
                                        } else {
                                            Size(size.width, size.height)
                                        }
                                    }
                                    ?.distinctBy { "${it.width}x${it.height}" } // Remove duplicates
                                    ?.filter {
                                        it.height >= it.width
                                    } // Only portrait/square ratios
                             ?: emptyList()

                    resolutions.sortedByDescending { it.width * it.height }
                }
                        ?: getFallbackResolutions()
            }
                    ?: getFallbackResolutions()
        } catch (e: Exception) {
            e.printStackTrace()
            getFallbackResolutions()
        }
    }

    /** Fallback resolutions if camera detection fails */
    private fun getFallbackResolutions(): List<Size> {
        return listOf(
                Size(1920, 1080), // Full HD
                Size(1280, 720), // HD
                Size(640, 480) // VGA
        )
    }

    /** Format resolution for display */
    fun formatResolution(resolution: Size): String {
        return "${resolution.width} × ${resolution.height}"
    }

    /** Calculate megapixels for a resolution */
    fun calculateMegapixels(resolution: Size): String {
        val megapixels = resolution.width * resolution.height / 1000000.0
        return String.format("%.1f", megapixels) + "MP"
    }

    /** Calculate aspect ratio for a resolution */
    fun calculateAspectRatio(resolution: Size): Float {
        return resolution.width.toFloat() / resolution.height.toFloat()
    }

    /**
     * Find the best default resolution based on width for portrait mode. Priority: width=640 > next
     * lower width > next higher width
     */
    fun findBestDefaultResolution(availableResolutions: List<Size>): Size? {
        if (availableResolutions.isEmpty()) return null

        val targetWidth = 640

        // Sort resolutions by width for easier processing
        val sortedByWidth = availableResolutions.sortedBy { it.width }

        // 1. Look for exact width match (640)
        val exactMatch = availableResolutions.find { it.width == targetWidth }
        if (exactMatch != null) {
            return exactMatch
        }

        // 2. Find next lower width (largest width < 640)
        val lowerWidthResolutions = availableResolutions.filter { it.width < targetWidth }
        if (lowerWidthResolutions.isNotEmpty()) {
            return lowerWidthResolutions.maxByOrNull { it.width }
        }

        // 3. Fallback: find next higher width (smallest width > 640)
        val higherWidthResolutions = availableResolutions.filter { it.width > targetWidth }
        if (higherWidthResolutions.isNotEmpty()) {
            return higherWidthResolutions.minByOrNull { it.width }
        }

        // 4. Final fallback: return first available resolution
        return availableResolutions.firstOrNull()
    }

    /** Sort resolutions by width for display in dialog (portrait mode) */
    fun sortResolutionsByWidth(resolutions: List<Size>): List<Size> {
        return resolutions.sortedBy { it.width }
    }
}

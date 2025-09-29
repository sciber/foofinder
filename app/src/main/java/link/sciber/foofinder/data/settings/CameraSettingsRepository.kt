package link.sciber.foofinder.data.settings

import android.util.Size
import androidx.datastore.core.DataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import link.sciber.foofinder.datastore.UserSettings
import link.sciber.foofinder.presentation.CameraPreviewAnalyzer

class CameraSettingsRepository(
        private val dataStore: DataStore<UserSettings>
) {

    val settings: Flow<UserSettings> = dataStore.data.catch { throwable ->
        if (throwable is IOException) {
            emit(UserSettings.getDefaultInstance())
        } else {
            throw throwable
        }
    }

    suspend fun setResolution(size: Size?) {
        dataStore.updateData { current ->
            val builder = current.toBuilder()
            if (size == null) {
                builder.clearResolutionWidth()
                builder.clearResolutionHeight()
            } else {
                builder.resolutionWidth = size.width
                builder.resolutionHeight = size.height
            }
            builder.build()
        }
    }

    suspend fun setModelId(modelId: String) {
        dataStore.updateData { current ->
            current.toBuilder().setModelId(modelId).build()
        }
    }

    suspend fun setScanStrategy(strategy: CameraPreviewAnalyzer.ScanStrategy) {
        dataStore.updateData { current ->
            current.toBuilder().setScanStrategy(strategy.name).build()
        }
    }

    suspend fun setConfidenceThreshold(value: Float) {
        val coerced = value.coerceIn(0f, 1f)
        dataStore.updateData { current ->
            current.toBuilder().setConfidenceThreshold(coerced).build()
        }
    }

    suspend fun setMaxBoxes(value: Int) {
        val coerced = value.coerceIn(1, 500)
        dataStore.updateData { current ->
            current.toBuilder().setMaxBoxes(coerced).build()
        }
    }

    suspend fun setNmsEnabled(enabled: Boolean) {
        dataStore.updateData { current ->
            current.toBuilder().setNmsEnabled(enabled).build()
        }
    }
}

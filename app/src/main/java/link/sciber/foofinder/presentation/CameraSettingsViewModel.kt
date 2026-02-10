package link.sciber.foofinder.presentation

import android.app.Application
import android.util.Size
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import link.sciber.foofinder.data.detection.Accelerator
import link.sciber.foofinder.data.detection.DeePooDetector
import link.sciber.foofinder.data.settings.CameraSettingsRepository
import link.sciber.foofinder.data.settings.userSettingsStore
import link.sciber.foofinder.datastore.UserSettings

class CameraSettingsViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val DEFAULT_MODEL_ID = "models/deepoo_yolov4_tiny_416_int8.tflite"
        private const val DEFAULT_CONFIDENCE_THRESHOLD = 0.55f
        private const val DEFAULT_MAX_BOXES = 15
        private const val DEFAULT_NMS_ENABLED = true
        private const val DEFAULT_TORCH_ENABLED = false
        private val DEFAULT_ACCELERATOR = Accelerator.NNAPI
    }

    data class UiState(
            val resolution: Size? = null,
            val modelId: String = DEFAULT_MODEL_ID,
            val scanStrategy: CameraAnalyzer.ScanStrategy = CameraAnalyzer.ScanStrategy.CENTERED,
            val confidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD,
            val maxBoxes: Int = DEFAULT_MAX_BOXES,
            val nmsEnabled: Boolean = DEFAULT_NMS_ENABLED,
            val torchEnabled: Boolean = DEFAULT_TORCH_ENABLED,
            val accelerator: Accelerator = DEFAULT_ACCELERATOR,
            val isNnapiAvailable: Boolean? = null  // null = still probing
    )

    private val repository = CameraSettingsRepository(application.userSettingsStore)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var lastProbedModelId: String? = null

    init {
        viewModelScope.launch {
            repository.settings.collect { settings ->
                val modelId = settings.modelId.takeIf {
                    it.isNotBlank() && it == DEFAULT_MODEL_ID
                } ?: DEFAULT_MODEL_ID

                _uiState.value =
                        UiState(
                                resolution = settings.toResolution(),
                                modelId = modelId,
                                scanStrategy = settings.scanStrategy.toScanStrategy(),
                                confidenceThreshold = settings.toConfidenceThreshold(),
                                maxBoxes = settings.toMaxBoxes(),
                                nmsEnabled = settings.toNmsEnabled(),
                                torchEnabled = settings.toTorchEnabled(),
                                accelerator = settings.toAccelerator(),
                                isNnapiAvailable = _uiState.value.isNnapiAvailable
                        )

                if (modelId != lastProbedModelId) {
                    lastProbedModelId = modelId
                    probeNnapiAvailability(modelId)
                }
            }
        }
    }

    private fun probeNnapiAvailability(modelId: String) {
        _uiState.update { it.copy(isNnapiAvailable = null) }  // probing
        viewModelScope.launch {
            val usable = withContext(Dispatchers.IO) {
                DeePooDetector.isNnapiUsable(getApplication(), modelId)
            }
            _uiState.update { st ->
                val corrected = if (!usable && st.accelerator == Accelerator.NNAPI) {
                    // Saved preference was NNAPI but it's not available — auto-correct
                    launch { repository.setAccelerator(Accelerator.CPU.name) }
                    st.copy(isNnapiAvailable = usable, accelerator = Accelerator.CPU)
                } else {
                    st.copy(isNnapiAvailable = usable)
                }
                corrected
            }
        }
    }

    fun onResolutionChanged(size: Size?) {
        viewModelScope.launch { repository.setResolution(size) }
    }

    fun onModelChanged(modelId: String) {
        if (modelId.isBlank()) return
        viewModelScope.launch { repository.setModelId(modelId) }
    }

    fun onScanStrategyChanged(strategy: CameraAnalyzer.ScanStrategy) {
        viewModelScope.launch { repository.setScanStrategy(strategy) }
    }

    fun onConfidenceThresholdChanged(value: Float) {
        val coerced = value.coerceIn(0f, 1f)
        _uiState.update { it.copy(confidenceThreshold = coerced) }
        viewModelScope.launch { repository.setConfidenceThreshold(coerced) }
    }

    fun onMaxBoxesChanged(value: Int) {
        val coerced = value.coerceIn(1, 500)
        _uiState.update { it.copy(maxBoxes = coerced) }
        viewModelScope.launch { repository.setMaxBoxes(coerced) }
    }

    fun onNmsEnabledChanged(enabled: Boolean) {
        _uiState.update { it.copy(nmsEnabled = enabled) }
        viewModelScope.launch { repository.setNmsEnabled(enabled) }
    }

    fun onTorchEnabledChanged(enabled: Boolean) {
        _uiState.update { it.copy(torchEnabled = enabled) }
        viewModelScope.launch { repository.setTorchEnabled(enabled) }
    }

    fun onAcceleratorChanged(accelerator: Accelerator) {
        _uiState.update { it.copy(accelerator = accelerator) }
        viewModelScope.launch { repository.setAccelerator(accelerator.name) }
    }

    private fun UserSettings.toResolution(): Size? {
        return if (resolutionWidth > 0 && resolutionHeight > 0) {
            Size(resolutionWidth, resolutionHeight)
        } else {
            null
        }
    }

    private fun String.toScanStrategy(): CameraAnalyzer.ScanStrategy {
        return try {
            if (isBlank()) CameraAnalyzer.ScanStrategy.CENTERED
            else CameraAnalyzer.ScanStrategy.valueOf(this)
        } catch (_: IllegalArgumentException) {
            CameraAnalyzer.ScanStrategy.CENTERED
        }
    }

    private fun UserSettings.toConfidenceThreshold(): Float {
        return if (hasConfidenceThreshold()) confidenceThreshold else DEFAULT_CONFIDENCE_THRESHOLD
    }

    private fun UserSettings.toMaxBoxes(): Int {
        val value = if (hasMaxBoxes()) maxBoxes else DEFAULT_MAX_BOXES
        return value.coerceIn(1, 500)
    }

    private fun UserSettings.toNmsEnabled(): Boolean {
        return if (hasNmsEnabled()) nmsEnabled else DEFAULT_NMS_ENABLED
    }

    private fun UserSettings.toTorchEnabled(): Boolean {
        return if (hasTorchEnabled()) torchEnabled else DEFAULT_TORCH_ENABLED
    }

    private fun UserSettings.toAccelerator(): Accelerator {
        return if (hasAccelerator() && accelerator.isNotBlank()) {
            try {
                Accelerator.valueOf(accelerator)
            } catch (_: IllegalArgumentException) {
                DEFAULT_ACCELERATOR
            }
        } else {
            DEFAULT_ACCELERATOR
        }
    }
}

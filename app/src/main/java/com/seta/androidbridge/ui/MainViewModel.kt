package com.seta.androidbridge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seta.androidbridge.domain.contracts.CameraEngine
import com.seta.androidbridge.domain.contracts.HttpBridgeService
import com.seta.androidbridge.domain.contracts.Logger
import com.seta.androidbridge.domain.contracts.SessionStateStore
import com.seta.androidbridge.domain.models.AppError
import com.seta.androidbridge.domain.models.PreviewProfiles
import com.seta.androidbridge.domain.models.SettingDefinition
import com.seta.androidbridge.domain.models.SettingValue
import com.seta.androidbridge.net.NetworkInfoProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainViewModel(
    private val cameraEngine: CameraEngine,
    private val httpBridgeService: HttpBridgeService,
    private val sessionStateStore: SessionStateStore,
    private val networkInfoProvider: NetworkInfoProvider,
    private val logger: Logger,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        MainUiState(
            availablePreviewProfiles = PreviewProfiles.All,
        ),
    )
    val uiState: StateFlow<MainUiState> = _uiState

    fun initialize() {
        _uiState.value = _uiState.value.copy(cameraPermissionGranted = true)

        viewModelScope.launch {
            cameraEngine.initialize()
                .onFailure { handleFailure("camera initialize failed", it) }

            cameraEngine.openCamera()
                .onFailure { handleFailure("open camera failed", it) }

            cameraEngine.startLocalPreview()
                .onFailure { handleFailure("start local preview failed", it) }

            refreshNetworkInfo()
            refreshCapabilities()
            refreshPreviewProfile()
            refreshCameraSettings()
            syncFromSession()
        }
    }

    fun onStartServerClicked() {
        viewModelScope.launch {
            refreshNetworkInfo()

            httpBridgeService.start(_uiState.value.port)
                .onFailure { handleFailure("start server failed", it) }

            syncFromSession()
        }
    }

    fun onStopServerClicked() {
        viewModelScope.launch {
            httpBridgeService.stop()
                .onFailure { handleFailure("stop server failed", it) }

            syncFromSession()
        }
    }

    fun onCaptureClicked() {
        viewModelScope.launch {
            cameraEngine.captureJpeg()
                .onFailure { handleFailure("capture failed", it) }

            syncFromSession()
        }
    }

    fun onPreviewProfileSelected(profileId: String) {
        viewModelScope.launch {
            cameraEngine.setPreviewProfile(profileId)
                .onFailure { handleFailure("set preview profile failed", it) }

            refreshPreviewProfile()
            syncFromSession()
        }
    }

    fun onLensSelected(lensId: String) {
        viewModelScope.launch {
            cameraEngine.selectLens(lensId)
                .onFailure { handleFailure("select lens failed", it) }

            refreshCapabilities()
            refreshCameraSettings()
            syncFromSession()
        }
    }

    fun onFocusModeSelected(mode: String) {
        setCameraSetting(
            key = "focus_mode",
            value = SettingValue.StringValue(mode),
            failurePrefix = "set focus_mode failed",
        )
    }

    fun onFocusDistanceChanged(value: Float) {
        setCameraSetting(
            key = "focus_distance",
            value = SettingValue.FloatValue(value.coerceIn(0f, 1f)),
            failurePrefix = "set focus_distance failed",
        )
    }

    fun onAeLockChanged(enabled: Boolean) {
        setCameraSetting(
            key = "ae_lock",
            value = SettingValue.BoolValue(enabled),
            failurePrefix = "set ae_lock failed",
        )
    }

    fun onAwbLockChanged(enabled: Boolean) {
        setCameraSetting(
            key = "awb_lock",
            value = SettingValue.BoolValue(enabled),
            failurePrefix = "set awb_lock failed",
        )
    }

    fun onWhiteBalanceModeSelected(mode: String) {
        setCameraSetting(
            key = "white_balance_mode",
            value = SettingValue.StringValue(mode),
            failurePrefix = "set white_balance_mode failed",
        )
    }

    fun onIsoChanged(value: Float) {
        val min = _uiState.value.isoMin ?: value
        val max = _uiState.value.isoMax ?: value
        setCameraSetting(
            key = "iso",
            value = SettingValue.IntValue(value.coerceIn(min, max).toInt()),
            failurePrefix = "set iso failed",
        )
    }

    fun onExposureTimeChanged(value: Float) {
        val min = _uiState.value.exposureTimeMin ?: value
        val max = _uiState.value.exposureTimeMax ?: value
        setCameraSetting(
            key = "exposure_time",
            value = SettingValue.FloatValue(value.coerceIn(min, max)),
            failurePrefix = "set exposure_time failed",
        )
    }

    fun onWhiteBalanceTemperatureChanged(value: Float) {
        val min = _uiState.value.whiteBalanceTemperatureMin ?: value
        val max = _uiState.value.whiteBalanceTemperatureMax ?: value
        setCameraSetting(
            key = "white_balance_temperature",
            value = SettingValue.IntValue(value.coerceIn(min, max).toInt()),
            failurePrefix = "set white_balance_temperature failed",
        )
    }

    fun onCameraPermissionDenied() {
        sessionStateStore.update {
            it.copy(
                lastError = AppError(
                    code = "permission_denied",
                    message = "Camera permission denied",
                ),
            )
        }

        _uiState.value = _uiState.value.copy(cameraPermissionGranted = false)
        syncFromSession()
    }

    fun refreshNetworkInfo() {
        val ip = networkInfoProvider.getLocalIpAddress()
        sessionStateStore.update { it.copy(ipAddress = ip) }
        syncFromSession()
    }

    fun refreshCapabilities() {
        viewModelScope.launch {
            cameraEngine.getCapabilities()
                .onSuccess { caps ->
                    val focusModeDef = caps.settings["focus_mode"]
                    val isoDef = caps.settings["iso"]
                    val exposureDef = caps.settings["exposure_time"]
                    val whiteBalanceModeDef = caps.settings["white_balance_mode"]
                    val whiteBalanceTemperatureDef = caps.settings["white_balance_temperature"]

                    _uiState.value = _uiState.value.copy(
                        availableLensIds = caps.availableLenses.map { it.id },
                        activeLensId = caps.activeLensId,
                        availableCameraSettings = caps.settings.mapValues { (_, definition) ->
                            definition.label
                        },
                        availableFocusModes = definitionChoices(focusModeDef),
                        availableWhiteBalanceModes = definitionChoices(whiteBalanceModeDef),
                        isoMin = definitionMinAsFloat(isoDef),
                        isoMax = definitionMaxAsFloat(isoDef),
                        exposureTimeMin = definitionMinAsFloat(exposureDef),
                        exposureTimeMax = definitionMaxAsFloat(exposureDef),
                        whiteBalanceTemperatureMin = definitionMinAsFloat(whiteBalanceTemperatureDef),
                        whiteBalanceTemperatureMax = definitionMaxAsFloat(whiteBalanceTemperatureDef),
                    )
                }
                .onFailure { handleFailure("get capabilities failed", it) }
        }
    }

    fun refreshPreviewProfile() {
        val profile = cameraEngine.getPreviewProfile()
        _uiState.value = _uiState.value.copy(
            previewProfileId = profile.id,
            availablePreviewProfiles = PreviewProfiles.All,
        )
    }

    fun refreshCameraSettings() {
        viewModelScope.launch {
            cameraEngine.getAllSettings()
                .onSuccess { settings ->
                    _uiState.value = _uiState.value.copy(
                        currentFocusMode = (settings["focus_mode"] as? SettingValue.StringValue)?.value,
                        currentFocusDistance = when (val value = settings["focus_distance"]) {
                            is SettingValue.FloatValue -> value.value
                            is SettingValue.IntValue -> value.value.toFloat()
                            else -> null
                        },
                        currentAeLock = (settings["ae_lock"] as? SettingValue.BoolValue)?.value,
                        currentAwbLock = (settings["awb_lock"] as? SettingValue.BoolValue)?.value,
                        currentWhiteBalanceMode = (settings["white_balance_mode"] as? SettingValue.StringValue)?.value,
                        currentIso = when (val value = settings["iso"]) {
                            is SettingValue.IntValue -> value.value
                            is SettingValue.FloatValue -> value.value.toInt()
                            else -> null
                        },
                        currentExposureTime = when (val value = settings["exposure_time"]) {
                            is SettingValue.FloatValue -> value.value
                            is SettingValue.IntValue -> value.value.toFloat()
                            else -> null
                        },
                        currentWhiteBalanceTemperature = when (val value = settings["white_balance_temperature"]) {
                            is SettingValue.IntValue -> value.value
                            is SettingValue.FloatValue -> value.value.toInt()
                            else -> null
                        },
                        activeLensId = (settings["lens"] as? SettingValue.StringValue)?.value
                            ?: _uiState.value.activeLensId,
                    )
                }
                .onFailure { handleFailure("get camera settings failed", it) }
        }
    }

    private fun setCameraSetting(
        key: String,
        value: SettingValue,
        failurePrefix: String,
    ) {
        viewModelScope.launch {
            cameraEngine.setSetting(key, value)
                .onFailure { handleFailure(failurePrefix, it) }

            refreshCapabilities()
            refreshCameraSettings()
            syncFromSession()
        }
    }

    private fun syncFromSession() {
        val s = sessionStateStore.getState()
        val baseUrl = s.ipAddress?.let { "http://$it:${s.port}" }

        _uiState.value = _uiState.value.copy(
            serverRunning = s.serverRunning,
            cameraOpen = s.cameraOpen,
            previewLocalRunning = s.previewLocalRunning,
            ipAddress = s.ipAddress,
            port = s.port,
            baseUrl = baseUrl,
            activeLensId = s.activeLensId ?: _uiState.value.activeLensId,
            lastCaptureId = s.lastCaptureId,
            lastError = s.lastError?.message,
            previewProfileId = s.previewProfileId,
        )
    }

    private fun handleFailure(prefix: String, throwable: Throwable) {
        logger.error("$prefix: ${throwable.message}")
        sessionStateStore.update {
            it.copy(
                lastError = AppError(
                    code = "ui_operation_failed",
                    message = "$prefix: ${throwable.message ?: "unknown error"}",
                ),
            )
        }
        syncFromSession()
    }

    private fun definitionMinAsFloat(definition: SettingDefinition?): Float? {
        return definition?.minValue?.toFloat()
    }

    private fun definitionMaxAsFloat(definition: SettingDefinition?): Float? {
        return definition?.maxValue?.toFloat()
    }

    private fun definitionChoices(definition: SettingDefinition?): List<String> {
        return definition?.choices ?: emptyList()
    }
}

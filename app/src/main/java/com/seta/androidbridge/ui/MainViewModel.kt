package com.seta.androidbridge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seta.androidbridge.domain.contracts.CameraEngine
import com.seta.androidbridge.domain.contracts.HttpBridgeService
import com.seta.androidbridge.domain.contracts.Logger
import com.seta.androidbridge.domain.contracts.OverlayHistoryRepository
import com.seta.androidbridge.domain.contracts.SessionStateStore
import com.seta.androidbridge.domain.models.AppError
import com.seta.androidbridge.domain.models.CaptureRequestSource
import com.seta.androidbridge.domain.models.OverlayHistoryEntry
import com.seta.androidbridge.domain.models.PreviewProfiles
import com.seta.androidbridge.domain.models.SettingDefinition
import com.seta.androidbridge.domain.models.SettingValue
import com.seta.androidbridge.net.NetworkInfoProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainViewModel(
    private val cameraEngine: CameraEngine,
    private val httpBridgeService: HttpBridgeService,
    private val sessionStateStore: SessionStateStore,
    private val networkInfoProvider: NetworkInfoProvider,
    private val overlayHistoryRepository: OverlayHistoryRepository,
    private val logger: Logger,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        MainUiState(
            availablePreviewProfiles = PreviewProfiles.All,
        ),
    )
    val uiState: StateFlow<MainUiState> = _uiState

    init {
        viewModelScope.launch {
            overlayHistoryRepository.historyFlow.collect { history ->
                _uiState.value = recalculateOverlayState(
                    _uiState.value.copy(
                        overlayHistoryEntries = history,
                        overlayHistoryCount = history.size,
                    ),
                )
            }
        }
    }

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
            cameraEngine.captureJpeg(CaptureRequestSource.APP_DEBUG)
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

    fun onOverlayEnabledChanged(enabled: Boolean) {
        _uiState.value = recalculateOverlayState(
            _uiState.value.copy(overlayEnabled = enabled),
        )
    }

    fun onOverlayOpacityChanged(value: Float) {
        _uiState.value = recalculateOverlayState(
            _uiState.value.copy(
                overlayOpacity = value.coerceIn(0f, 1f),
            ),
        )
    }

    fun onOverlayStackDepthSelected(depth: OverlayStackDepth) {
        _uiState.value = recalculateOverlayState(
            _uiState.value.copy(overlayStackDepth = depth),
        )
    }

    fun onOverlayModeSelected(mode: OverlayMode) {
        val currentState = _uiState.value
        val manualSelection = when (mode) {
            OverlayMode.AUTO -> currentState.selectedOverlayCaptureId
            OverlayMode.MANUAL -> currentState.selectedOverlayCaptureId
                ?: currentState.activeOverlayCaptureId
                ?: currentState.overlayHistoryEntries.firstOrNull()?.captureId
        }

        _uiState.value = recalculateOverlayState(
            currentState.copy(
                overlayMode = mode,
                selectedOverlayCaptureId = manualSelection,
            ),
        )
    }

    fun onSelectOlderOverlayClicked() {
        val state = _uiState.value
        if (state.overlayMode != OverlayMode.MANUAL) return

        val history = state.overlayHistoryEntries
        val currentIndex = history.indexOfFirst { it.captureId == state.activeOverlayCaptureId }
        if (currentIndex == -1 || currentIndex >= history.lastIndex) return

        _uiState.value = recalculateOverlayState(
            state.copy(selectedOverlayCaptureId = history[currentIndex + 1].captureId),
        )
    }

    fun onSelectNewerOverlayClicked() {
        val state = _uiState.value
        if (state.overlayMode != OverlayMode.MANUAL) return

        val history = state.overlayHistoryEntries
        val currentIndex = history.indexOfFirst { it.captureId == state.activeOverlayCaptureId }
        if (currentIndex <= 0) return

        _uiState.value = recalculateOverlayState(
            state.copy(selectedOverlayCaptureId = history[currentIndex - 1].captureId),
        )
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

    fun onPurgeOverlayHistoryClicked() {
        viewModelScope.launch {
            runCatching {
                overlayHistoryRepository.purgeHistory()
            }.onFailure { handleFailure("purge overlay history failed", it) }

            _uiState.value = recalculateOverlayState(
                _uiState.value.copy(selectedOverlayCaptureId = null),
            )
            syncFromSession()
        }
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

    private fun recalculateOverlayState(state: MainUiState): MainUiState {
        val history = state.overlayHistoryEntries
        val manualSelectedId = when {
            history.isEmpty() -> null
            state.overlayMode == OverlayMode.MANUAL -> {
                val currentSelection = state.selectedOverlayCaptureId
                when {
                    currentSelection == null -> history.first().captureId
                    history.any { it.captureId == currentSelection } -> currentSelection
                    else -> history.first().captureId
                }
            }
            else -> state.selectedOverlayCaptureId
        }

        val primaryEntry = resolvePrimaryOverlayEntry(
            history = history,
            mode = state.overlayMode,
            selectedCaptureId = manualSelectedId,
        )

        val stackEntries = resolveOverlayStackEntries(
            history = history,
            mode = state.overlayMode,
            selectedCaptureId = manualSelectedId,
            depth = state.overlayStackDepth,
        )

        val renderLayers = buildOverlayRenderLayers(
            stackEntries = stackEntries,
            baseOpacity = state.overlayOpacity,
            enabled = state.overlayEnabled,
        )

        val activeIndex = primaryEntry?.let { entry ->
            history.indexOfFirst { it.captureId == entry.captureId }
        } ?: -1

        val activeLabel = when {
            primaryEntry == null -> null
            state.overlayMode == OverlayMode.AUTO -> {
                val depthLabel = when (state.overlayStackDepth) {
                    OverlayStackDepth.SINGLE -> "Single"
                    OverlayStackDepth.DOUBLE -> "Double"
                    OverlayStackDepth.TRIPLE -> "Triple"
                }
                "$depthLabel auto · ${primaryEntry.fileName}"
            }
            else -> "Manual ${activeIndex + 1}/${history.size} · ${primaryEntry.fileName}"
        }

        return state.copy(
            overlayHistoryCount = history.size,
            selectedOverlayCaptureId = manualSelectedId,
            activeOverlayCaptureId = primaryEntry?.captureId,
            activeOverlayFilePath = primaryEntry?.absolutePath,
            activeOverlayLayers = renderLayers,
            activeOverlayLabel = activeLabel,
            canSelectNewerOverlay = state.overlayMode == OverlayMode.MANUAL && activeIndex > 0,
            canSelectOlderOverlay = state.overlayMode == OverlayMode.MANUAL && activeIndex in 0 until history.lastIndex,
        )
    }

    private fun resolvePrimaryOverlayEntry(
        history: List<OverlayHistoryEntry>,
        mode: OverlayMode,
        selectedCaptureId: String?,
    ): OverlayHistoryEntry? {
        if (history.isEmpty()) return null

        return when (mode) {
            OverlayMode.AUTO -> history.firstOrNull()
            OverlayMode.MANUAL -> history.firstOrNull { it.captureId == selectedCaptureId }
                ?: history.firstOrNull()
        }
    }

    private fun resolveOverlayStackEntries(
        history: List<OverlayHistoryEntry>,
        mode: OverlayMode,
        selectedCaptureId: String?,
        depth: OverlayStackDepth,
    ): List<OverlayHistoryEntry> {
        if (history.isEmpty()) return emptyList()

        return when (mode) {
            OverlayMode.AUTO -> history.take(depth.layerCount)
            OverlayMode.MANUAL -> {
                val selectedIndex = history.indexOfFirst { it.captureId == selectedCaptureId }
                    .takeIf { it >= 0 } ?: 0
                history.drop(selectedIndex).take(depth.layerCount)
            }
        }
    }

    private fun buildOverlayRenderLayers(
        stackEntries: List<OverlayHistoryEntry>,
        baseOpacity: Float,
        enabled: Boolean,
    ): List<OverlayRenderLayer> {
        if (!enabled || baseOpacity <= 0f || stackEntries.isEmpty()) return emptyList()

        val alphaFalloff = listOf(1f, 0.6f, 0.3f)

        return stackEntries
            .mapIndexed { index, entry ->
                OverlayRenderLayer(
                    captureId = entry.captureId,
                    filePath = entry.absolutePath,
                    alpha = (baseOpacity * alphaFalloff.getOrElse(index) { 0.2f }).coerceIn(0f, 1f),
                )
            }
            .reversed()
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

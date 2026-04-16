package com.seta.androidbridge.ui

import com.seta.androidbridge.domain.models.PreviewProfile
import com.seta.androidbridge.domain.models.PreviewProfiles

data class MainUiState(
    val cameraPermissionGranted: Boolean = false,
    val serverRunning: Boolean = false,
    val cameraOpen: Boolean = false,
    val previewLocalRunning: Boolean = false,
    val ipAddress: String? = null,
    val port: Int = 8765,
    val baseUrl: String? = null,
    val activeLensId: String? = null,
    val availableLensIds: List<String> = emptyList(),
    val lastCaptureId: String? = null,
    val lastError: String? = null,
    val logs: List<String> = emptyList(),
    val previewProfileId: String = PreviewProfiles.Balanced.id,
    val availablePreviewProfiles: List<PreviewProfile> = PreviewProfiles.All,

    val availableCameraSettings: Map<String, String> = emptyMap(),
    val availableFocusModes: List<String> = emptyList(),
    val availableWhiteBalanceModes: List<String> = emptyList(),

    val currentFocusMode: String? = null,
    val currentFocusDistance: Float? = null,
    val currentAeLock: Boolean? = null,
    val currentAwbLock: Boolean? = null,
    val currentWhiteBalanceMode: String? = null,
    val currentIso: Int? = null,
    val currentExposureTime: Float? = null,
    val currentWhiteBalanceTemperature: Int? = null,

    val isoMin: Float? = null,
    val isoMax: Float? = null,
    val exposureTimeMin: Float? = null,
    val exposureTimeMax: Float? = null,
    val whiteBalanceTemperatureMin: Float? = null,
    val whiteBalanceTemperatureMax: Float? = null,
)

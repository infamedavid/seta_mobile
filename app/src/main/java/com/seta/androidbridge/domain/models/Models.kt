package com.seta.androidbridge.domain.models

data class AppError(
    val code: String,
    val message: String,
    val detail: String? = null,
)

data class AppSessionState(
    val serverRunning: Boolean = false,
    val cameraOpen: Boolean = false,
    val previewLocalRunning: Boolean = false,
    val previewRemoteRunning: Boolean = false,
    val activeLensId: String? = null,
    val ipAddress: String? = null,
    val port: Int = 8765,
    val authEnabled: Boolean = false,
    val lastCaptureId: String? = null,
    val lastError: AppError? = null,
    val previewProfileId: String = PreviewProfiles.Balanced.id,
)

data class DeviceInfo(
    val deviceId: String,
    val displayName: String,
    val manufacturer: String?,
    val model: String?,
    val protocolVersion: Int,
    val appVersion: String,
)

data class LensInfo(
    val id: String,
    val label: String,
    val facing: String,
    val isDefault: Boolean = false,
)

sealed class SettingValue {
    data class IntValue(val value: Int) : SettingValue()
    data class FloatValue(val value: Float) : SettingValue()
    data class BoolValue(val value: Boolean) : SettingValue()
    data class StringValue(val value: String) : SettingValue()
}

data class SettingDefinition(
    val key: String,
    val label: String,
    val supported: Boolean,
    val readable: Boolean,
    val writable: Boolean,
    val type: String? = null,
    val mode: String? = null,
    val choices: List<String>? = null,
    val minValue: Double? = null,
    val maxValue: Double? = null,
    val current: SettingValue? = null,
)

data class CameraCapabilities(
    val supportsCapture: Boolean,
    val supportsPreview: Boolean,
    val previewFormat: String?,
    val captureFormats: List<String>,
    val availableLenses: List<LensInfo>,
    val activeLensId: String?,
    val settings: Map<String, SettingDefinition>,
)

data class CapturedImage(
    val captureId: String,
    val fileName: String,
    val absolutePath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val timestampEpochMs: Long,
)

data class PreviewInfo(
    val format: String,
    val endpointPath: String,
)

data class PreviewProfile(
    val id: String,
    val label: String,
    val width: Int,
    val height: Int,
    val jpegQuality: Int,
    val maxFps: Int,
)

object PreviewProfiles {
    val LowLatency = PreviewProfile(
        id = "low_latency",
        label = "Baja latencia",
        width = 640,
        height = 360,
        jpegQuality = 45,
        maxFps = 8,
    )

    val Balanced = PreviewProfile(
        id = "balanced",
        label = "Equilibrado",
        width = 960,
        height = 540,
        jpegQuality = 60,
        maxFps = 8,
    )

    val HighQuality = PreviewProfile(
        id = "high_quality",
        label = "Alta calidad",
        width = 1280,
        height = 720,
        jpegQuality = 75,
        maxFps = 6,
    )

    val VeryHigh = PreviewProfile(
        id = "very_high",
        label = "Muy alta (experimental)",
        width = 1920,
        height = 1080,
        jpegQuality = 88,
        maxFps = 4,
    )

    val All: List<PreviewProfile> = listOf(
        LowLatency,
        Balanced,
        HighQuality,
        VeryHigh,
    )

    fun byId(id: String?): PreviewProfile {
        return All.firstOrNull { it.id == id } ?: Balanced
    }
}

object FocusModeIds {
    const val AUTO = "auto"
    const val CONTINUOUS = "continuous"
    const val MANUAL = "manual"
    const val LOCKED = "locked"

    val All = listOf(AUTO, CONTINUOUS, MANUAL, LOCKED)
}

object WhiteBalanceModeIds {
    const val AUTO = "auto"
    const val DAYLIGHT = "daylight"
    const val CLOUDY = "cloudy"
    const val INCANDESCENT = "incandescent"
    const val FLUORESCENT = "fluorescent"

    val All = listOf(
        AUTO,
        DAYLIGHT,
        CLOUDY,
        INCANDESCENT,
        FLUORESCENT,
    )
}
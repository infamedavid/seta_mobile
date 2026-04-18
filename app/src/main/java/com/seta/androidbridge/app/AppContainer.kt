package com.seta.androidbridge.app

import android.content.Context
import android.os.Build
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import com.seta.androidbridge.camera.CameraXEngine
import com.seta.androidbridge.domain.contracts.CameraEngine
import com.seta.androidbridge.domain.contracts.CaptureRepository
import com.seta.androidbridge.domain.contracts.HttpBridgeService
import com.seta.androidbridge.domain.contracts.Logger
import com.seta.androidbridge.domain.contracts.OverlayHistoryRepository
import com.seta.androidbridge.domain.contracts.SessionStateStore
import com.seta.androidbridge.domain.models.DeviceInfo
import com.seta.androidbridge.logging.AppLogger
import com.seta.androidbridge.net.NetworkInfoProvider
import com.seta.androidbridge.server.EmbeddedHttpServer
import com.seta.androidbridge.storage.CaptureFileStore
import com.seta.androidbridge.storage.InMemorySessionStateStore
import com.seta.androidbridge.storage.OverlayHistoryFileStore
import java.util.UUID

@OptIn(ExperimentalCamera2Interop::class)
class AppContainer(private val context: Context) {
    val appConfig = AppConfig()

    val logger: Logger by lazy { AppLogger() }
    val sessionStateStore: SessionStateStore by lazy { InMemorySessionStateStore() }
    val captureRepository: CaptureRepository by lazy { CaptureFileStore(context) }
    val networkInfoProvider: NetworkInfoProvider by lazy { NetworkInfoProvider(context) }
    val overlayHistoryRepository: OverlayHistoryRepository by lazy { OverlayHistoryFileStore(context, logger) }

    val deviceInfo: DeviceInfo by lazy {
        DeviceInfo(
            deviceId = buildDeviceId(),
            displayName = buildDisplayName(),
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            protocolVersion = 1,
            appVersion = "0.1.0",
        )
    }

    val cameraEngine: CameraEngine by lazy {
        CameraXEngine(context, captureRepository, sessionStateStore, overlayHistoryRepository, logger)
    }

    val httpBridgeService: HttpBridgeService by lazy {
        EmbeddedHttpServer(cameraEngine, sessionStateStore, captureRepository, deviceInfo, logger)
    }

    private fun buildDeviceId(): String {
        val raw = "${Build.MANUFACTURER}-${Build.MODEL}-${Build.DEVICE}-${UUID.randomUUID()}"
        return raw.lowercase()
            .replace("\\s+".toRegex(), "_")
            .replace("[^a-z0-9_\\-]".toRegex(), "")
    }

    private fun buildDisplayName(): String {
        val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
        val model = Build.MODEL?.trim().orEmpty()
        return listOf(manufacturer, model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Android Device" }
    }
}

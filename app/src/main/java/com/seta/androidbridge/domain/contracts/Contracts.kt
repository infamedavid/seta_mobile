package com.seta.androidbridge.domain.contracts

import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.seta.androidbridge.domain.models.AppSessionState
import com.seta.androidbridge.domain.models.CameraCapabilities
import com.seta.androidbridge.domain.models.CapturedImage
import com.seta.androidbridge.domain.models.PreviewInfo
import com.seta.androidbridge.domain.models.PreviewProfile
import com.seta.androidbridge.domain.models.SettingValue

interface Logger {
    fun info(message: String)
    fun warn(message: String)
    fun error(message: String)
}

interface SessionStateStore {
    fun getState(): AppSessionState
    fun update(transform: (AppSessionState) -> AppSessionState)
}

interface CaptureRepository {
    suspend fun saveJpeg(bytes: ByteArray): Result<CapturedImage>
    suspend fun getCapture(captureId: String): Result<CapturedImage>
}

interface HttpBridgeService {
    suspend fun start(port: Int): Result<Unit>
    suspend fun stop(): Result<Unit>
    fun isRunning(): Boolean
}

interface CameraEngine {
    suspend fun initialize(): Result<Unit>
    suspend fun openCamera(lensId: String? = null): Result<Unit>
    suspend fun closeCamera(): Result<Unit>

    suspend fun startLocalPreview(): Result<Unit>
    suspend fun stopLocalPreview(): Result<Unit>

    suspend fun startRemotePreview(): Result<PreviewInfo>
    suspend fun stopRemotePreview(): Result<Unit>
    fun isRemotePreviewRunning(): Boolean

    suspend fun captureJpeg(): Result<CapturedImage>

    suspend fun getCapabilities(): Result<CameraCapabilities>
    suspend fun getAllSettings(): Result<Map<String, SettingValue>>
    suspend fun getSetting(key: String): Result<SettingValue>
    suspend fun setSetting(key: String, value: SettingValue): Result<SettingValue>
    suspend fun selectLens(lensId: String): Result<Unit>

    fun attachPreviewView(previewView: PreviewView, lifecycleOwner: LifecycleOwner)
    fun detachPreviewView()
    fun latestPreviewJpegFrame(): ByteArray?

    fun getPreviewProfile(): PreviewProfile
    suspend fun setPreviewProfile(profileId: String): Result<PreviewProfile>
}
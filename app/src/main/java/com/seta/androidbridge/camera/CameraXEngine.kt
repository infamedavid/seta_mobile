package com.seta.androidbridge.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.media.ExifInterface
import android.view.Surface
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.seta.androidbridge.domain.contracts.CameraEngine
import com.seta.androidbridge.domain.contracts.CaptureRepository
import com.seta.androidbridge.domain.contracts.Logger
import com.seta.androidbridge.domain.contracts.OverlayHistoryRepository
import com.seta.androidbridge.domain.contracts.SessionStateStore
import com.seta.androidbridge.domain.models.CameraCapabilities
import com.seta.androidbridge.domain.models.CaptureRequestSource
import com.seta.androidbridge.domain.models.CapturedImage
import com.seta.androidbridge.domain.models.FocusModeIds
import com.seta.androidbridge.domain.models.LensInfo
import com.seta.androidbridge.domain.models.PreviewInfo
import com.seta.androidbridge.domain.models.PreviewProfile
import com.seta.androidbridge.domain.models.PreviewProfiles
import com.seta.androidbridge.domain.models.SettingDefinition
import com.seta.androidbridge.domain.models.SettingValue
import com.seta.androidbridge.domain.models.WhiteBalanceModeIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.ln
import kotlin.math.pow

@ExperimentalCamera2Interop
class CameraXEngine(
    private val context: Context,
    private val captureRepository: CaptureRepository,
    private val sessionStateStore: SessionStateStore,
    private val overlayHistoryRepository: OverlayHistoryRepository,
    private val logger: Logger,
) : CameraEngine {

    private data class CameraSettingSupport(
        val focusModeChoices: List<String>,
        val supportsManualFocusDistance: Boolean,
        val focusDistanceMin: Double?,
        val focusDistanceMax: Double?,
        val aeLockSupported: Boolean,
        val awbLockSupported: Boolean,
        val whiteBalanceModeChoices: List<String>,
        val torchSupported: Boolean,
        val supportsManualExposure: Boolean,
        val isoMin: Int?,
        val isoMax: Int?,
        val exposureTimeMinSeconds: Double?,
        val exposureTimeMaxSeconds: Double?,
        val supportsWhiteBalanceTemperature: Boolean,
        val whiteBalanceTemperatureMin: Int?,
        val whiteBalanceTemperatureMax: Int?,
    )

    private val cameraManager: CameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    private val bitmapFrameSource = PreviewBitmapFrameSource()
    private val analysisFrameSource = ImageAnalysisFrameSource(logger)

    @Volatile
    private var remotePreviewRunning = false

    @Volatile
    private var cameraProvider: ProcessCameraProvider? = null

    @Volatile
    private var lifecycleOwner: LifecycleOwner? = null

    @Volatile
    private var previewView: PreviewView? = null

    @Volatile
    private var previewUseCase: Preview? = null

    @Volatile
    private var imageCaptureUseCase: ImageCapture? = null

    @Volatile
    private var imageAnalysisUseCase: ImageAnalysis? = null

    @Volatile
    private var boundCamera: Camera? = null

    @Volatile
    private var activeLensId: String = "rear_main"

    @Volatile
    private var currentPreviewProfile: PreviewProfile = PreviewProfiles.Balanced

    @Volatile
    private var currentFocusMode: String = FocusModeIds.AUTO

    @Volatile
    private var currentFocusDistanceNormalized: Float = 0f

    @Volatile
    private var currentAeLock: Boolean = false

    @Volatile
    private var currentAwbLock: Boolean = false

    @Volatile
    private var currentWhiteBalanceMode: String = WhiteBalanceModeIds.AUTO

    @Volatile
    private var currentTorch: Boolean = false

    @Volatile
    private var manualExposureEnabled: Boolean = false

    @Volatile
    private var currentIso: Int? = null

    @Volatile
    private var currentExposureTimeSeconds: Double? = null

    @Volatile
    private var manualWhiteBalanceEnabled: Boolean = false

    @Volatile
    private var currentWhiteBalanceTemperature: Int? = null

    override suspend fun initialize(): Result<Unit> = runCatching {
        cameraProvider = awaitCameraProvider()
        sessionStateStore.update {
            it.copy(previewProfileId = currentPreviewProfile.id)
        }
        logger.info("CameraXEngine initialized with ProcessCameraProvider")
    }

    override suspend fun openCamera(lensId: String?): Result<Unit> = runCatching {
        if (!lensId.isNullOrBlank()) {
            activeLensId = lensId
        }

        withContext(Dispatchers.Main) {
            bindUseCases()
        }
    }

    override suspend fun closeCamera(): Result<Unit> = runCatching {
        withContext(Dispatchers.Main) {
            cameraProvider?.unbindAll()
        }

        boundCamera = null
        previewUseCase = null
        imageCaptureUseCase = null
        imageAnalysisUseCase = null
        analysisFrameSource.clear()
        remotePreviewRunning = false

        sessionStateStore.update {
            it.copy(
                cameraOpen = false,
                previewLocalRunning = false,
                previewRemoteRunning = false,
            )
        }

        logger.info("Camera closed")
    }

    override suspend fun startLocalPreview(): Result<Unit> = runCatching {
        withContext(Dispatchers.Main) {
            bindUseCases()
        }
    }

    override suspend fun stopLocalPreview(): Result<Unit> = runCatching {
        sessionStateStore.update { it.copy(previewLocalRunning = false) }
        logger.info("Local preview marked as stopped")
    }

    override suspend fun startRemotePreview(): Result<PreviewInfo> = runCatching {
        remotePreviewRunning = true
        sessionStateStore.update { it.copy(previewRemoteRunning = true) }
        logger.info("Remote preview started with profile=${currentPreviewProfile.id}")
        PreviewInfo(format = "mjpeg", endpointPath = "/api/v1/preview")
    }

    override suspend fun stopRemotePreview(): Result<Unit> = runCatching {
        remotePreviewRunning = false
        sessionStateStore.update { it.copy(previewRemoteRunning = false) }
        logger.info("Remote preview stopped")
    }

    override fun isRemotePreviewRunning(): Boolean = remotePreviewRunning

    override suspend fun captureJpeg(source: CaptureRequestSource): Result<CapturedImage> = withContext(Dispatchers.IO) {
        runCatching {
            val imageCapture = imageCaptureUseCase ?: error("ImageCapture not ready")

            withContext(Dispatchers.Main) {
                imageCapture.targetRotation = currentTargetRotation()
            }

            val tempFile = File.createTempFile("seta-mobile-cap-", ".jpg", context.cacheDir)

            try {
                val savedFile = takePictureToFile(imageCapture, tempFile)
                val normalizedBytes = normalizeCapturedJpeg(savedFile)
                val saved = captureRepository.saveJpeg(normalizedBytes).getOrThrow()

                sessionStateStore.update {
                    it.copy(
                        lastCaptureId = saved.captureId,
                        lastError = null,
                    )
                }

                recordOverlayHistoryIfNeeded(source, saved)

                logger.info("Capture saved with ImageCapture id=${saved.captureId} source=$source")
                saved
            } catch (captureError: Throwable) {
                val fallbackBytes = latestPreviewJpegFrame()
                    ?: throw captureError

                logger.warn("ImageCapture failed, using preview fallback: ${captureError.message}")

                val saved = captureRepository.saveJpeg(fallbackBytes).getOrThrow()
                sessionStateStore.update {
                    it.copy(
                        lastCaptureId = saved.captureId,
                        lastError = null,
                    )
                }
                recordOverlayHistoryIfNeeded(source, saved)
                logger.info("Capture saved with preview fallback id=${saved.captureId} source=$source")
                saved
            } finally {
                tempFile.delete()
            }
        }
    }

    private suspend fun recordOverlayHistoryIfNeeded(
        source: CaptureRequestSource,
        capturedImage: CapturedImage,
    ) {
        if (source != CaptureRequestSource.BLENDER_ADDON) {
            return
        }

        runCatching {
            overlayHistoryRepository.recordBlenderCapture(capturedImage)
        }.onFailure { error ->
            logger.warn("Failed to record overlay history for ${capturedImage.captureId}: ${error.message}")
        }
    }

    override suspend fun getCapabilities(): Result<CameraCapabilities> = runCatching {
        val lenses = buildAvailableLenses()
        val selectedLens = normalizeActiveLens(lenses)
        val support = currentSettingSupport()

        ensureManualExposureDefaults(support)
        ensureManualWhiteBalanceDefaults(support)

        val settings = linkedMapOf<String, SettingDefinition>()

        settings["lens"] = SettingDefinition(
            key = "lens",
            label = "Lens",
            supported = lenses.isNotEmpty(),
            readable = true,
            writable = lenses.isNotEmpty(),
            type = "choice",
            mode = "manual",
            choices = lenses.map { it.id },
            current = SettingValue.StringValue(selectedLens),
        )

        if (support.focusModeChoices.isNotEmpty()) {
            settings["focus_mode"] = SettingDefinition(
                key = "focus_mode",
                label = "Focus Mode",
                supported = true,
                readable = true,
                writable = true,
                type = "choice",
                mode = "manual",
                choices = support.focusModeChoices,
                current = SettingValue.StringValue(normalizeFocusMode(currentFocusMode, support)),
            )
        }

        if (support.supportsManualFocusDistance) {
            settings["focus_distance"] = SettingDefinition(
                key = "focus_distance",
                label = "Focus Distance",
                supported = true,
                readable = true,
                writable = true,
                type = "range",
                mode = "manual",
                minValue = 0.0,
                maxValue = 1.0,
                current = SettingValue.FloatValue(currentFocusDistanceNormalized),
            )
        }

        if (support.aeLockSupported) {
            settings["ae_lock"] = SettingDefinition(
                key = "ae_lock",
                label = "AE Lock",
                supported = true,
                readable = true,
                writable = true,
                type = "boolean",
                mode = "manual",
                current = SettingValue.BoolValue(currentAeLock),
            )
        }

        if (support.awbLockSupported) {
            settings["awb_lock"] = SettingDefinition(
                key = "awb_lock",
                label = "AWB Lock",
                supported = true,
                readable = true,
                writable = true,
                type = "boolean",
                mode = "manual",
                current = SettingValue.BoolValue(currentAwbLock),
            )
        }

        if (support.whiteBalanceModeChoices.isNotEmpty()) {
            settings["white_balance_mode"] = SettingDefinition(
                key = "white_balance_mode",
                label = "White Balance Mode",
                supported = true,
                readable = true,
                writable = true,
                type = "choice",
                mode = "manual",
                choices = support.whiteBalanceModeChoices,
                current = SettingValue.StringValue(
                    normalizeWhiteBalanceMode(currentWhiteBalanceMode, support),
                ),
            )
        }

        if (support.supportsWhiteBalanceTemperature) {
            val wbValue = currentWhiteBalanceTemperature ?: defaultWhiteBalanceTemperatureFor(support)

            settings["white_balance_temperature"] = SettingDefinition(
                key = "white_balance_temperature",
                label = "White Balance Temperature",
                supported = true,
                readable = true,
                writable = true,
                type = "range",
                mode = "manual",
                minValue = support.whiteBalanceTemperatureMin?.toDouble(),
                maxValue = support.whiteBalanceTemperatureMax?.toDouble(),
                current = SettingValue.IntValue(wbValue),
            )
        }

        if (support.torchSupported) {
            settings["torch"] = SettingDefinition(
                key = "torch",
                label = "Torch",
                supported = true,
                readable = true,
                writable = true,
                type = "boolean",
                mode = "manual",
                current = SettingValue.BoolValue(currentTorch),
            )
        }

        if (support.supportsManualExposure) {
            val isoValue = currentIso ?: defaultIsoFor(support) ?: 100
            val exposureValue = currentExposureTimeSeconds ?: defaultExposureTimeSecondsFor(support)

            settings["iso"] = SettingDefinition(
                key = "iso",
                label = "ISO",
                supported = true,
                readable = true,
                writable = true,
                type = "range",
                mode = "manual",
                minValue = support.isoMin?.toDouble(),
                maxValue = support.isoMax?.toDouble(),
                current = SettingValue.IntValue(isoValue),
            )

            settings["exposure_time"] = SettingDefinition(
                key = "exposure_time",
                label = "Exposure Time",
                supported = true,
                readable = true,
                writable = true,
                type = "range",
                mode = "manual",
                minValue = support.exposureTimeMinSeconds,
                maxValue = support.exposureTimeMaxSeconds,
                current = SettingValue.FloatValue(exposureValue.toFloat()),
            )
        }

        CameraCapabilities(
            supportsCapture = true,
            supportsPreview = true,
            previewFormat = "mjpeg",
            captureFormats = listOf("jpeg"),
            availableLenses = lenses,
            activeLensId = selectedLens,
            settings = settings,
        )
    }

    override suspend fun getAllSettings(): Result<Map<String, SettingValue>> = runCatching {
        val support = currentSettingSupport()
        ensureManualExposureDefaults(support)
        ensureManualWhiteBalanceDefaults(support)

        val out = linkedMapOf<String, SettingValue>()

        out["lens"] = SettingValue.StringValue(normalizeActiveLens(buildAvailableLenses()))

        if (support.focusModeChoices.isNotEmpty()) {
            out["focus_mode"] = SettingValue.StringValue(normalizeFocusMode(currentFocusMode, support))
        }

        if (support.supportsManualFocusDistance) {
            out["focus_distance"] = SettingValue.FloatValue(currentFocusDistanceNormalized)
        }

        if (support.aeLockSupported) {
            out["ae_lock"] = SettingValue.BoolValue(currentAeLock)
        }

        if (support.awbLockSupported) {
            out["awb_lock"] = SettingValue.BoolValue(currentAwbLock)
        }

        if (support.whiteBalanceModeChoices.isNotEmpty()) {
            out["white_balance_mode"] = SettingValue.StringValue(
                normalizeWhiteBalanceMode(currentWhiteBalanceMode, support),
            )
        }

        if (support.supportsWhiteBalanceTemperature) {
            out["white_balance_temperature"] = SettingValue.IntValue(
                currentWhiteBalanceTemperature ?: defaultWhiteBalanceTemperatureFor(support),
            )
        }

        if (support.torchSupported) {
            out["torch"] = SettingValue.BoolValue(currentTorch)
        }

        if (support.supportsManualExposure) {
            val isoValue = currentIso ?: defaultIsoFor(support) ?: 100
            val exposureValue = currentExposureTimeSeconds ?: defaultExposureTimeSecondsFor(support)
            out["iso"] = SettingValue.IntValue(isoValue)
            out["exposure_time"] = SettingValue.FloatValue(exposureValue.toFloat())
        }

        out
    }

    override suspend fun getSetting(key: String): Result<SettingValue> = runCatching {
        getAllSettings().getOrThrow()[key] ?: error("Unsupported setting: $key")
    }

    override suspend fun setSetting(key: String, value: SettingValue): Result<SettingValue> = runCatching {
        val support = currentSettingSupport()
        ensureManualExposureDefaults(support)
        ensureManualWhiteBalanceDefaults(support)

        when (key) {
            "lens" -> {
                val requestedLens = (value as? SettingValue.StringValue)?.value
                    ?: error("lens expects string")

                val available = buildAvailableLenses().map { it.id }
                require(requestedLens in available) { "Invalid value for lens: $requestedLens" }

                selectLens(requestedLens).getOrThrow()
                SettingValue.StringValue(activeLensId)
            }

            "focus_mode" -> {
                val requestedMode = (value as? SettingValue.StringValue)?.value
                    ?: error("focus_mode expects string")

                require(requestedMode in support.focusModeChoices) {
                    "Invalid value for focus_mode: $requestedMode"
                }

                currentFocusMode = requestedMode

                if (sessionStateStore.getState().cameraOpen) {
                    withContext(Dispatchers.Main) {
                        bindUseCases()
                    }
                }

                SettingValue.StringValue(currentFocusMode)
            }

            "focus_distance" -> {
                require(support.supportsManualFocusDistance) { "focus_distance not supported" }

                val requestedValue = when (value) {
                    is SettingValue.FloatValue -> value.value
                    is SettingValue.IntValue -> value.value.toFloat()
                    else -> error("focus_distance expects float")
                }

                val clamped = requestedValue.coerceIn(0f, 1f)
                currentFocusDistanceNormalized = clamped
                currentFocusMode = FocusModeIds.MANUAL

                if (sessionStateStore.getState().cameraOpen) {
                    withContext(Dispatchers.Main) {
                        bindUseCases()
                    }
                }

                SettingValue.FloatValue(currentFocusDistanceNormalized)
            }

            "ae_lock" -> {
                require(support.aeLockSupported) { "ae_lock not supported" }

                val requested = (value as? SettingValue.BoolValue)?.value
                    ?: error("ae_lock expects boolean")

                currentAeLock = requested

                if (sessionStateStore.getState().cameraOpen) {
                    withContext(Dispatchers.Main) {
                        bindUseCases()
                    }
                }

                SettingValue.BoolValue(currentAeLock)
            }

            "awb_lock" -> {
                require(support.awbLockSupported) { "awb_lock not supported" }

                val requested = (value as? SettingValue.BoolValue)?.value
                    ?: error("awb_lock expects boolean")

                currentAwbLock = requested

                if (sessionStateStore.getState().cameraOpen) {
                    withContext(Dispatchers.Main) {
                        bindUseCases()
                    }
                }

                SettingValue.BoolValue(currentAwbLock)
            }

            "white_balance_mode" -> {
                val requestedMode = (value as? SettingValue.StringValue)?.value
                    ?: error("white_balance_mode expects string")

                require(requestedMode in support.whiteBalanceModeChoices) {
                    "Invalid value for white_balance_mode: $requestedMode"
                }

                currentWhiteBalanceMode = requestedMode
                manualWhiteBalanceEnabled = false

                if (sessionStateStore.getState().cameraOpen) {
                    withContext(Dispatchers.Main) {
                        bindUseCases()
                    }
                }

                SettingValue.StringValue(currentWhiteBalanceMode)
            }

            "white_balance_temperature" -> {
                require(support.supportsWhiteBalanceTemperature) { "white_balance_temperature not supported" }

                val requestedValue = when (value) {
                    is SettingValue.IntValue -> value.value
                    is SettingValue.FloatValue -> value.value.toInt()
                    else -> error("white_balance_temperature expects numeric value")
                }

                val wbMin = support.whiteBalanceTemperatureMin ?: error("white_balance_temperature min unavailable")
                val wbMax = support.whiteBalanceTemperatureMax ?: error("white_balance_temperature max unavailable")
                val clamped = requestedValue.coerceIn(wbMin, wbMax)

                currentWhiteBalanceTemperature = clamped
                manualWhiteBalanceEnabled = true

                if (sessionStateStore.getState().cameraOpen) {
                    withContext(Dispatchers.Main) {
                        bindUseCases()
                    }
                }

                SettingValue.IntValue(clamped)
            }

            "torch" -> {
                require(support.torchSupported) { "torch not supported" }

                val requested = (value as? SettingValue.BoolValue)?.value
                    ?: error("torch expects boolean")

                currentTorch = requested
                applyTorchState()

                SettingValue.BoolValue(currentTorch)
            }

            "iso" -> {
                require(support.supportsManualExposure) { "iso not supported" }

                val requestedValue = when (value) {
                    is SettingValue.IntValue -> value.value
                    is SettingValue.FloatValue -> value.value.toInt()
                    else -> error("iso expects numeric value")
                }

                val isoMin = support.isoMin ?: error("iso min unavailable")
                val isoMax = support.isoMax ?: error("iso max unavailable")
                val clamped = requestedValue.coerceIn(isoMin, isoMax)

                currentIso = clamped
                manualExposureEnabled = true

                if (sessionStateStore.getState().cameraOpen) {
                    withContext(Dispatchers.Main) {
                        bindUseCases()
                    }
                }

                SettingValue.IntValue(clamped)
            }

            "exposure_time" -> {
                require(support.supportsManualExposure) { "exposure_time not supported" }

                val requestedValue = when (value) {
                    is SettingValue.FloatValue -> value.value.toDouble()
                    is SettingValue.IntValue -> value.value.toDouble()
                    else -> error("exposure_time expects numeric value")
                }

                val clamped = clampDouble(
                    requestedValue,
                    support.exposureTimeMinSeconds,
                    support.exposureTimeMaxSeconds,
                )

                currentExposureTimeSeconds = clamped
                manualExposureEnabled = true

                if (sessionStateStore.getState().cameraOpen) {
                    withContext(Dispatchers.Main) {
                        bindUseCases()
                    }
                }

                SettingValue.FloatValue(clamped.toFloat())
            }

            else -> error("Unsupported setting: $key")
        }
    }

    override suspend fun selectLens(lensId: String): Result<Unit> = runCatching {
        val available = buildAvailableLenses().map { it.id }
        require(lensId in available) { "Invalid value for lens: $lensId" }

        activeLensId = lensId
        sessionStateStore.update { it.copy(activeLensId = activeLensId) }

        if (sessionStateStore.getState().cameraOpen) {
            withContext(Dispatchers.Main) {
                bindUseCases()
            }
        }

        logger.info("Lens selected: $activeLensId")
    }

    override fun attachPreviewView(previewView: PreviewView, lifecycleOwner: LifecycleOwner) {
        this.previewView = previewView
        this.lifecycleOwner = lifecycleOwner
        bitmapFrameSource.attach(previewView)

        previewUseCase?.setSurfaceProvider(previewView.surfaceProvider)
        updateTargetRotations(currentTargetRotation())

        if (sessionStateStore.getState().cameraOpen) {
            sessionStateStore.update { it.copy(previewLocalRunning = true) }
        }
    }

    override fun detachPreviewView() {
        bitmapFrameSource.detach()
        previewView = null
        lifecycleOwner = null

        cameraProvider?.unbindAll()
        boundCamera = null
        previewUseCase = null
        imageCaptureUseCase = null
        imageAnalysisUseCase = null
        analysisFrameSource.clear()

        sessionStateStore.update {
            it.copy(
                cameraOpen = false,
                previewLocalRunning = false,
            )
        }
    }

    override fun latestPreviewJpegFrame(): ByteArray? {
        return analysisFrameSource.latestJpegFrame() ?: bitmapFrameSource.latestJpegFrame()
    }

    override fun getPreviewProfile(): PreviewProfile = currentPreviewProfile

    override suspend fun setPreviewProfile(profileId: String): Result<PreviewProfile> = runCatching {
        val newProfile = PreviewProfiles.byId(profileId)
        currentPreviewProfile = newProfile

        sessionStateStore.update {
            it.copy(previewProfileId = newProfile.id)
        }

        if (sessionStateStore.getState().cameraOpen) {
            withContext(Dispatchers.Main) {
                bindUseCases()
            }
        }

        logger.info(
            "Preview profile set to ${newProfile.id} " +
                    "(${newProfile.width}x${newProfile.height}, q=${newProfile.jpegQuality}, fps=${newProfile.maxFps})",
        )

        newProfile
    }

    private suspend fun bindUseCases() {
        val provider = cameraProvider ?: awaitCameraProvider().also { cameraProvider = it }
        val owner = lifecycleOwner ?: error("LifecycleOwner not attached")
        val rotation = currentTargetRotation()
        val profile = currentPreviewProfile
        val support = currentSettingSupport()

        currentFocusMode = normalizeFocusMode(currentFocusMode, support)
        currentWhiteBalanceMode = normalizeWhiteBalanceMode(currentWhiteBalanceMode, support)
        ensureManualExposureDefaults(support)
        ensureManualWhiteBalanceDefaults(support)

        val previewBuilder = Preview.Builder()
            .setTargetRotation(rotation)

        applyPreviewInterop(previewBuilder, support)

        val preview = previewBuilder.build()

        val attachedPreviewView = previewView
        attachedPreviewView?.let { preview.setSurfaceProvider(it.surfaceProvider) }

        val imageCaptureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(rotation)

        applyImageCaptureInterop(imageCaptureBuilder, support)

        val imageCapture = imageCaptureBuilder.build()

        val imageAnalysis = analysisFrameSource.buildUseCase(profile).also {
            it.targetRotation = rotation
        }

        val (resolvedLensId, selector) = resolveLensAndSelector(provider, activeLensId)

        provider.unbindAll()
        boundCamera = provider.bindToLifecycle(owner, selector, preview, imageCapture, imageAnalysis)

        previewUseCase = preview
        imageCaptureUseCase = imageCapture
        imageAnalysisUseCase = imageAnalysis
        activeLensId = resolvedLensId

        applyTorchState()

        sessionStateStore.update {
            it.copy(
                cameraOpen = true,
                previewLocalRunning = attachedPreviewView != null,
                activeLensId = resolvedLensId,
                previewProfileId = profile.id,
                lastError = null,
            )
        }

        logger.info(
            "Camera bound with lens=$resolvedLensId rotation=$rotation " +
                    "profile=${profile.id} ${profile.width}x${profile.height} q=${profile.jpegQuality} fps=${profile.maxFps}",
        )
    }

    private fun applyPreviewInterop(
        builder: Preview.Builder,
        support: CameraSettingSupport,
    ) {
        val extender = Camera2Interop.Extender(builder)
        applyCommonCaptureRequestOptions(
            setOption = { key, value -> extender.setCaptureRequestOption(key, value) },
            support = support,
        )
    }

    private fun applyImageCaptureInterop(
        builder: ImageCapture.Builder,
        support: CameraSettingSupport,
    ) {
        val extender = Camera2Interop.Extender(builder)
        applyCommonCaptureRequestOptions(
            setOption = { key, value -> extender.setCaptureRequestOption(key, value) },
            support = support,
        )
    }

    private fun applyCommonCaptureRequestOptions(
        setOption: (CaptureRequest.Key<Any>, Any) -> Unit,
        support: CameraSettingSupport,
    ) {
        val afMode = when (currentFocusMode) {
            FocusModeIds.CONTINUOUS -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            FocusModeIds.MANUAL -> CaptureRequest.CONTROL_AF_MODE_OFF
            else -> CaptureRequest.CONTROL_AF_MODE_AUTO
        }

        @Suppress("UNCHECKED_CAST")
        setOption(CaptureRequest.CONTROL_AF_MODE as CaptureRequest.Key<Any>, afMode)

        if (currentFocusMode == FocusModeIds.MANUAL && support.supportsManualFocusDistance) {
            val minDistance = support.focusDistanceMax?.toFloat() ?: 0f
            val rawDistance = (currentFocusDistanceNormalized.coerceIn(0f, 1f)) * minDistance

            @Suppress("UNCHECKED_CAST")
            setOption(CaptureRequest.LENS_FOCUS_DISTANCE as CaptureRequest.Key<Any>, rawDistance)
        }

        if (manualExposureEnabled && support.supportsManualExposure) {
            val isoValue = currentIso ?: defaultIsoFor(support)
            val exposureSeconds = currentExposureTimeSeconds ?: defaultExposureTimeSecondsFor(support)

            if (isoValue != null && exposureSeconds != null) {
                val exposureTimeNs = (exposureSeconds * 1_000_000_000.0).toLong()

                @Suppress("UNCHECKED_CAST")
                setOption(
                    CaptureRequest.CONTROL_AE_MODE as CaptureRequest.Key<Any>,
                    CaptureRequest.CONTROL_AE_MODE_OFF,
                )

                @Suppress("UNCHECKED_CAST")
                setOption(
                    CaptureRequest.SENSOR_SENSITIVITY as CaptureRequest.Key<Any>,
                    isoValue,
                )

                @Suppress("UNCHECKED_CAST")
                setOption(
                    CaptureRequest.SENSOR_EXPOSURE_TIME as CaptureRequest.Key<Any>,
                    exposureTimeNs,
                )
            }
        } else if (support.aeLockSupported) {
            @Suppress("UNCHECKED_CAST")
            setOption(CaptureRequest.CONTROL_AE_LOCK as CaptureRequest.Key<Any>, currentAeLock)
        }

        if (manualWhiteBalanceEnabled && support.supportsWhiteBalanceTemperature) {
            val temperature = currentWhiteBalanceTemperature ?: defaultWhiteBalanceTemperatureFor(support)
            val gains = kelvinToRggbChannelVector(temperature)
            val transform = identityColorTransform()

            @Suppress("UNCHECKED_CAST")
            setOption(
                CaptureRequest.CONTROL_AWB_MODE as CaptureRequest.Key<Any>,
                CaptureRequest.CONTROL_AWB_MODE_OFF,
            )

            @Suppress("UNCHECKED_CAST")
            setOption(
                CaptureRequest.COLOR_CORRECTION_MODE as CaptureRequest.Key<Any>,
                CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX,
            )

            @Suppress("UNCHECKED_CAST")
            setOption(
                CaptureRequest.COLOR_CORRECTION_GAINS as CaptureRequest.Key<Any>,
                gains,
            )

            @Suppress("UNCHECKED_CAST")
            setOption(
                CaptureRequest.COLOR_CORRECTION_TRANSFORM as CaptureRequest.Key<Any>,
                transform,
            )
        } else {
            if (support.awbLockSupported) {
                @Suppress("UNCHECKED_CAST")
                setOption(CaptureRequest.CONTROL_AWB_LOCK as CaptureRequest.Key<Any>, currentAwbLock)
            }

            val awbMode = mapWhiteBalanceModeToCaptureRequest(currentWhiteBalanceMode)
            if (awbMode != null && currentWhiteBalanceMode in support.whiteBalanceModeChoices) {
                @Suppress("UNCHECKED_CAST")
                setOption(CaptureRequest.CONTROL_AWB_MODE as CaptureRequest.Key<Any>, awbMode)
            }
        }
    }

    private fun applyTorchState() {
        if (!currentSettingSupport().torchSupported) return
        boundCamera?.cameraControl?.enableTorch(currentTorch)
    }

    private fun updateTargetRotations(rotation: Int) {
        previewUseCase?.targetRotation = rotation
        imageCaptureUseCase?.targetRotation = rotation
        imageAnalysisUseCase?.targetRotation = rotation
    }

    private fun currentTargetRotation(): Int {
        return previewView?.display?.rotation ?: Surface.ROTATION_0
    }

    private fun currentSettingSupport(): CameraSettingSupport {
        val cameraId = resolveCameraIdForActiveLens()
        val chars = cameraId?.let { cameraManager.getCameraCharacteristics(it) }

        val afModes = chars?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        val awbModes = chars?.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf()
        val minFocusDistance = chars?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val aeLockSupported = chars?.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) ?: false
        val awbLockSupported = chars?.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) ?: false
        val torchSupported = chars?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false

        val availableCapabilities = chars?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val sensitivityRange = chars?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposureTimeRangeNs = chars?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)

        val supportsManualExposure =
            availableCapabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) &&
                    sensitivityRange != null &&
                    exposureTimeRangeNs != null

        val supportsWhiteBalanceTemperature =
            availableCapabilities.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING,
            ) || awbModes.contains(CaptureRequest.CONTROL_AWB_MODE_OFF)

        val focusChoices = buildFocusModeChoices(afModes, minFocusDistance)
        val whiteBalanceChoices = buildWhiteBalanceModeChoices(awbModes)

        return CameraSettingSupport(
            focusModeChoices = focusChoices,
            supportsManualFocusDistance = minFocusDistance > 0f,
            focusDistanceMin = 0.0,
            focusDistanceMax = if (minFocusDistance > 0f) 1.0 else null,
            aeLockSupported = aeLockSupported,
            awbLockSupported = awbLockSupported,
            whiteBalanceModeChoices = whiteBalanceChoices,
            torchSupported = torchSupported,
            supportsManualExposure = supportsManualExposure,
            isoMin = sensitivityRange?.lower,
            isoMax = sensitivityRange?.upper,
            exposureTimeMinSeconds = exposureTimeRangeNs?.lower?.toDouble()?.div(1_000_000_000.0),
            exposureTimeMaxSeconds = exposureTimeRangeNs?.upper?.toDouble()?.div(1_000_000_000.0),
            supportsWhiteBalanceTemperature = supportsWhiteBalanceTemperature,
            whiteBalanceTemperatureMin = if (supportsWhiteBalanceTemperature) 2500 else null,
            whiteBalanceTemperatureMax = if (supportsWhiteBalanceTemperature) 9000 else null,
        )
    }

    private fun buildFocusModeChoices(
        afModes: IntArray,
        minFocusDistance: Float,
    ): List<String> {
        val out = mutableListOf<String>()

        if (afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)) {
            out += FocusModeIds.AUTO
        }

        if (
            afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ||
            afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        ) {
            out += FocusModeIds.CONTINUOUS
        }

        if (minFocusDistance > 0f) {
            out += FocusModeIds.MANUAL
        }

        return out.distinct()
    }

    private fun buildWhiteBalanceModeChoices(awbModes: IntArray): List<String> {
        val out = mutableListOf<String>()

        if (awbModes.contains(CaptureRequest.CONTROL_AWB_MODE_AUTO)) {
            out += WhiteBalanceModeIds.AUTO
        }
        if (awbModes.contains(CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT)) {
            out += WhiteBalanceModeIds.DAYLIGHT
        }
        if (awbModes.contains(CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT)) {
            out += WhiteBalanceModeIds.CLOUDY
        }
        if (awbModes.contains(CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT)) {
            out += WhiteBalanceModeIds.INCANDESCENT
        }
        if (awbModes.contains(CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT)) {
            out += WhiteBalanceModeIds.FLUORESCENT
        }

        if (out.isEmpty()) {
            out += WhiteBalanceModeIds.AUTO
        }

        return out.distinct()
    }

    private fun normalizeFocusMode(
        current: String,
        support: CameraSettingSupport,
    ): String {
        return support.focusModeChoices.firstOrNull { it == current }
            ?: support.focusModeChoices.firstOrNull()
            ?: FocusModeIds.AUTO
    }

    private fun normalizeWhiteBalanceMode(
        current: String,
        support: CameraSettingSupport,
    ): String {
        return support.whiteBalanceModeChoices.firstOrNull { it == current }
            ?: support.whiteBalanceModeChoices.firstOrNull()
            ?: WhiteBalanceModeIds.AUTO
    }

    private fun mapWhiteBalanceModeToCaptureRequest(mode: String): Int? {
        return when (mode) {
            WhiteBalanceModeIds.AUTO -> CaptureRequest.CONTROL_AWB_MODE_AUTO
            WhiteBalanceModeIds.DAYLIGHT -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
            WhiteBalanceModeIds.CLOUDY -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
            WhiteBalanceModeIds.INCANDESCENT -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
            WhiteBalanceModeIds.FLUORESCENT -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
            else -> null
        }
    }

    private fun ensureManualExposureDefaults(support: CameraSettingSupport) {
        if (!support.supportsManualExposure) {
            currentIso = null
            currentExposureTimeSeconds = null
            manualExposureEnabled = false
            return
        }

        if (currentIso == null) {
            currentIso = defaultIsoFor(support)
        }

        if (currentExposureTimeSeconds == null) {
            currentExposureTimeSeconds = defaultExposureTimeSecondsFor(support)
        }
    }

    private fun ensureManualWhiteBalanceDefaults(support: CameraSettingSupport) {
        if (!support.supportsWhiteBalanceTemperature) {
            currentWhiteBalanceTemperature = null
            manualWhiteBalanceEnabled = false
            return
        }

        if (currentWhiteBalanceTemperature == null) {
            currentWhiteBalanceTemperature = defaultWhiteBalanceTemperatureFor(support)
        }
    }

    private fun defaultIsoFor(support: CameraSettingSupport): Int? {
        val isoMin = support.isoMin ?: return null
        val isoMax = support.isoMax ?: return null
        val preferred = 100
        return preferred.coerceIn(isoMin, isoMax)
    }

    private fun defaultExposureTimeSecondsFor(support: CameraSettingSupport): Double {
        val preferred = 1.0 / 50.0
        return clampDouble(preferred, support.exposureTimeMinSeconds, support.exposureTimeMaxSeconds)
    }

    private fun defaultWhiteBalanceTemperatureFor(support: CameraSettingSupport): Int {
        val preferred = 5600
        val min = support.whiteBalanceTemperatureMin ?: preferred
        val max = support.whiteBalanceTemperatureMax ?: preferred
        return preferred.coerceIn(min, max)
    }

    private fun clampDouble(value: Double, min: Double?, max: Double?): Double {
        var out = value
        if (min != null && out < min) out = min
        if (max != null && out > max) out = max
        return out
    }

    private fun kelvinToRggbChannelVector(kelvin: Int): RggbChannelVector {
        val clampedKelvin = kelvin.coerceIn(2500, 9000)
        val rgb = kelvinToRgb(clampedKelvin)

        val r = rgb[0].coerceAtLeast(0.01f)
        val g = rgb[1].coerceAtLeast(0.01f)
        val b = rgb[2].coerceAtLeast(0.01f)

        val redGain = (g / r).coerceIn(0.5f, 3.0f)
        val blueGain = (g / b).coerceIn(0.5f, 3.0f)

        return RggbChannelVector(redGain, 1.0f, 1.0f, blueGain)
    }

    private fun kelvinToRgb(kelvin: Int): FloatArray {
        val temp = kelvin.coerceIn(1000, 40000) / 100.0

        val red = if (temp <= 66.0) {
            255.0
        } else {
            (329.698727446 * (temp - 60.0).pow(-0.1332047592)).coerceIn(0.0, 255.0)
        }

        val green = if (temp <= 66.0) {
            (99.4708025861 * ln(temp) - 161.1195681661).coerceIn(0.0, 255.0)
        } else {
            (288.1221695283 * (temp - 60.0).pow(-0.0755148492)).coerceIn(0.0, 255.0)
        }

        val blue = if (temp >= 66.0) {
            255.0
        } else if (temp <= 19.0) {
            0.0
        } else {
            (138.5177312231 * ln(temp - 10.0) - 305.0447927307).coerceIn(0.0, 255.0)
        }

        return floatArrayOf(
            (red / 255.0).toFloat(),
            (green / 255.0).toFloat(),
            (blue / 255.0).toFloat(),
        )
    }

    private fun identityColorTransform(): ColorSpaceTransform {
        return ColorSpaceTransform(
            intArrayOf(
                1, 1, 0, 1, 0, 1,
                0, 1, 1, 1, 0, 1,
                0, 1, 0, 1, 1, 1,
            ),
        )
    }

    private fun resolveCameraIdForActiveLens(): String? {
        val desiredFacing = when (activeLensId) {
            "front_main" -> CameraCharacteristics.LENS_FACING_FRONT
            else -> CameraCharacteristics.LENS_FACING_BACK
        }

        return cameraManager.cameraIdList.firstOrNull { cameraId ->
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            chars.get(CameraCharacteristics.LENS_FACING) == desiredFacing
        }
    }

    private fun resolveLensAndSelector(
        provider: ProcessCameraProvider,
        requestedLensId: String,
    ): Pair<String, CameraSelector> {
        val hasRear = provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
        val hasFront = provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)

        return when {
            requestedLensId == "front_main" && hasFront ->
                "front_main" to CameraSelector.DEFAULT_FRONT_CAMERA

            requestedLensId == "rear_main" && hasRear ->
                "rear_main" to CameraSelector.DEFAULT_BACK_CAMERA

            hasRear ->
                "rear_main" to CameraSelector.DEFAULT_BACK_CAMERA

            hasFront ->
                "front_main" to CameraSelector.DEFAULT_FRONT_CAMERA

            else ->
                throw IllegalStateException("No camera available on device")
        }
    }

    private fun buildAvailableLenses(): List<LensInfo> {
        val provider = cameraProvider
        if (provider == null) {
            return listOf(
                LensInfo(
                    id = "rear_main",
                    label = "Rear Main",
                    facing = "back",
                    isDefault = true,
                ),
            )
        }

        val result = mutableListOf<LensInfo>()

        if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
            result += LensInfo(
                id = "rear_main",
                label = "Rear Main",
                facing = "back",
                isDefault = true,
            )
        }

        if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
            result += LensInfo(
                id = "front_main",
                label = "Front Main",
                facing = "front",
                isDefault = result.isEmpty(),
            )
        }

        return result
    }

    private fun normalizeActiveLens(lenses: List<LensInfo>): String {
        return when {
            lenses.any { it.id == activeLensId } -> activeLensId
            lenses.any { it.id == "rear_main" } -> "rear_main"
            lenses.any { it.id == "front_main" } -> "front_main"
            lenses.isNotEmpty() -> lenses.first().id
            else -> "rear_main"
        }
    }

    private suspend fun awaitCameraProvider(): ProcessCameraProvider {
        return suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    try {
                        continuation.resume(future.get())
                    } catch (t: Throwable) {
                        continuation.cancel(t)
                    }
                },
                ContextCompat.getMainExecutor(context),
            )
        }
    }

    private suspend fun takePictureToFile(imageCapture: ImageCapture, outputFile: File): File {
        return suspendCancellableCoroutine { continuation ->
            val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()

            imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        continuation.resume(outputFile)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        continuation.cancel(exception)
                    }
                },
            )
        }
    }

    private fun normalizeCapturedJpeg(file: File): ByteArray {
        val sourceBytes = file.readBytes()
        val rotationDegrees = exifRotationDegrees(file)

        if (rotationDegrees == 0) {
            return sourceBytes
        }

        val bitmap = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size)
            ?: return sourceBytes

        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }

        val rotatedBitmap = Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true,
        )

        val output = ByteArrayOutputStream()
        rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)

        if (rotatedBitmap !== bitmap) {
            rotatedBitmap.recycle()
        }
        bitmap.recycle()

        return output.toByteArray()
    }

    private fun exifRotationDegrees(file: File): Int {
        val exif = ExifInterface(file.absolutePath)
        return when (
            exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }
}

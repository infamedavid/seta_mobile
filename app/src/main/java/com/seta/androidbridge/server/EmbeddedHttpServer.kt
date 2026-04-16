package com.seta.androidbridge.server

import com.seta.androidbridge.domain.contracts.CameraEngine
import com.seta.androidbridge.domain.contracts.CaptureRepository
import com.seta.androidbridge.domain.contracts.HttpBridgeService
import com.seta.androidbridge.domain.contracts.Logger
import com.seta.androidbridge.domain.contracts.SessionStateStore
import com.seta.androidbridge.domain.models.DeviceInfo
import com.seta.androidbridge.domain.models.SettingDefinition
import com.seta.androidbridge.domain.models.SettingValue
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.File

class EmbeddedHttpServer(
    private val cameraEngine: CameraEngine,
    private val sessionStateStore: SessionStateStore,
    private val captureRepository: CaptureRepository,
    private val deviceInfo: DeviceInfo,
    private val logger: Logger,
) : HttpBridgeService {

    @Volatile
    private var running = false

    @Volatile
    private var server: NanoHTTPD? = null

    override suspend fun start(port: Int): Result<Unit> = runCatching {
        stop().getOrThrow()

        val httpServer = object : NanoHTTPD("0.0.0.0", port) {
            override fun serve(session: IHTTPSession): Response {
                return this@EmbeddedHttpServer.handleSession(session)
            }
        }

        httpServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        server = httpServer
        running = true

        sessionStateStore.update { it.copy(serverRunning = true, port = port) }
        logger.info("HTTP server started on port=$port")
    }

    override suspend fun stop(): Result<Unit> = runCatching {
        server?.stop()
        server = null
        running = false

        sessionStateStore.update { it.copy(serverRunning = false) }
        logger.info("HTTP server stopped")
    }

    override fun isRunning(): Boolean = running

    private fun handleSession(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        return try {
            when {
                session.method == NanoHTTPD.Method.GET && session.uri == "/api/v1/status" ->
                    json(statusJson())

                session.method == NanoHTTPD.Method.GET && session.uri == "/api/v1/capabilities" ->
                    json(capabilitiesJson())

                session.method == NanoHTTPD.Method.GET && session.uri == "/api/v1/settings" ->
                    json(settingsJson())

                session.method == NanoHTTPD.Method.POST && session.uri == "/api/v1/settings" ->
                    json(applySettingsJson(session))

                session.method == NanoHTTPD.Method.POST && session.uri == "/api/v1/capture" ->
                    json(captureJson())

                session.method == NanoHTTPD.Method.GET && session.uri.startsWith("/api/v1/capture/") ->
                    getCapture(session.uri.removePrefix("/api/v1/capture/"))

                session.method == NanoHTTPD.Method.POST && session.uri == "/api/v1/preview/start" ->
                    json(previewStartJson())

                session.method == NanoHTTPD.Method.POST && session.uri == "/api/v1/preview/stop" ->
                    json(previewStopJson())

                session.method == NanoHTTPD.Method.GET && session.uri == "/api/v1/preview" ->
                    mjpegStream()

                else ->
                    errorJsonResponse(
                        status = NanoHTTPD.Response.Status.NOT_FOUND,
                        code = "NOT_FOUND",
                        message = "Not found",
                    )
            }
        } catch (t: Throwable) {
            logger.error("HTTP serve error: ${t.message}")
            errorJsonResponse(
                status = NanoHTTPD.Response.Status.INTERNAL_ERROR,
                code = "INTERNAL_ERROR",
                message = t.message ?: "error",
            )
        }
    }

    private fun statusJson(): String {
        val s = sessionStateStore.getState()
        return """{"ok":true,"data":{"protocolVersion":${deviceInfo.protocolVersion},"appVersion":"${deviceInfo.appVersion}","serverRunning":${s.serverRunning},"cameraOpen":${s.cameraOpen},"previewLocalRunning":${s.previewLocalRunning},"previewRemoteRunning":${s.previewRemoteRunning},"activeLens":"${s.activeLensId ?: ""}","lastCaptureId":"${s.lastCaptureId ?: ""}","ipAddress":"${s.ipAddress ?: ""}","port":${s.port}}}"""
    }

    private fun capabilitiesJson(): String = runBlocking {
        val caps = cameraEngine.getCapabilities().getOrThrow()
        """{"ok":true,"data":{"supportsCapture":${caps.supportsCapture},"supportsPreview":${caps.supportsPreview},"previewFormat":"${caps.previewFormat}"}}"""
    }

    private fun settingsJson(): String = runBlocking {
        val caps = cameraEngine.getCapabilities().getOrThrow()
        val settings = cameraEngine.getAllSettings().getOrThrow()

        val keys = caps.settings.keys.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }

        val valuesJson = caps.settings.entries.joinToString(prefix = "{", postfix = "}") { (key, definition) ->
            val currentValue = settings[key]
            val currentJson = currentValue?.let { settingValueToJson(it) } ?: "null"
            val metadataJson = buildSettingMetadataJson(definition)
            """"$key":{$metadataJson,"current":$currentJson}"""
        }

        """{"ok":true,"data":{"keys":$keys,"values":$valuesJson}}"""
    }

    private fun applySettingsJson(session: NanoHTTPD.IHTTPSession): String = runBlocking {
        val body = readBody(session)
        val caps = cameraEngine.getCapabilities().getOrThrow()
        val applied = linkedMapOf<String, SettingValue>()

        for ((key, definition) in caps.settings) {
            if (!definition.writable) continue

            val parsedValue = parseSettingFromBody(body, key, definition) ?: continue
            val appliedValue = cameraEngine.setSetting(key, parsedValue).getOrThrow()
            applied[key] = appliedValue
        }

        if (applied.isEmpty()) {
            return@runBlocking errorJson(
                code = "INVALID_REQUEST",
                message = "No supported writable settings found in request body",
            )
        }

        val appliedJson = applied.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            """"$key":${settingValueToJson(value)}"""
        }

        """{"ok":true,"data":{"applied":$appliedJson}}"""
    }

    private fun captureJson(): String = runBlocking {
        val cap = cameraEngine.captureJpeg().getOrThrow()
        """{"ok":true,"data":{"captureId":"${cap.captureId}","contentType":"${cap.mimeType}","sizeBytes":${cap.sizeBytes}}}"""
    }

    private fun previewStartJson(): String = runBlocking {
        val info = cameraEngine.startRemotePreview().getOrThrow()
        """{"ok":true,"data":{"previewRunning":true,"format":"${info.format}","endpoint":"${info.endpointPath}"}}"""
    }

    private fun previewStopJson(): String = runBlocking {
        cameraEngine.stopRemotePreview().getOrThrow()
        """{"ok":true,"data":{"previewRunning":false}}"""
    }

    private fun getCapture(captureId: String): NanoHTTPD.Response = runBlocking {
        val capture = captureRepository.getCapture(captureId).getOrThrow()
        val bytes = File(capture.absolutePath).readBytes()

        NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            capture.mimeType,
            ByteArrayInputStream(bytes),
            bytes.size.toLong(),
        )
    }

    private fun mjpegStream(): NanoHTTPD.Response {
        if (!cameraEngine.isRemotePreviewRunning()) {
            return errorJsonResponse(
                status = NanoHTTPD.Response.Status.CONFLICT,
                code = "PREVIEW_NOT_RUNNING",
                message = "Remote preview not running",
            )
        }

        val stream = MjpegStreamInputStream(cameraEngine = cameraEngine, boundary = "seta")

        return NanoHTTPD.newChunkedResponse(
            NanoHTTPD.Response.Status.OK,
            "multipart/x-mixed-replace; boundary=seta",
            stream,
        ).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("Pragma", "no-cache")
            addHeader("Connection", "close")
        }
    }

    private fun buildSettingMetadataJson(definition: SettingDefinition): String {
        val parts = mutableListOf<String>()

        parts += """"type":"${definition.type ?: "unknown"}""""

        definition.choices?.let { choices ->
            val choicesJson = choices.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
            parts += """"choices":$choicesJson"""
        }

        definition.minValue?.let { min ->
            parts += """"min":$min"""
        }

        definition.maxValue?.let { max ->
            parts += """"max":$max"""
        }

        return parts.joinToString(",")
    }

    private fun parseSettingFromBody(
        body: String,
        key: String,
        definition: SettingDefinition,
    ): SettingValue? {
        return when (definition.type) {
            "choice" -> {
                val value = extractStringField(body, key) ?: return null
                val choices = definition.choices ?: emptyList()
                if (choices.isNotEmpty() && value !in choices) {
                    throw IllegalArgumentException("Invalid value for $key: $value")
                }
                SettingValue.StringValue(value)
            }

            "boolean" -> {
                val value = extractBooleanField(body, key) ?: return null
                SettingValue.BoolValue(value)
            }

            "range" -> {
                val value = extractNumberField(body, key) ?: return null
                val clamped = value
                    .coerceAtLeast(definition.minValue ?: value)
                    .coerceAtMost(definition.maxValue ?: value)
                    .toFloat()
                SettingValue.FloatValue(clamped)
            }

            else -> null
        }
    }

    private fun readBody(session: NanoHTTPD.IHTTPSession): String {
        val files = HashMap<String, String>()
        session.parseBody(files)
        return files["postData"] ?: ""
    }

    private fun extractStringField(body: String, key: String): String? {
        val regex = Regex(""""$key"\s*:\s*"([^"]*)"""")
        return regex.find(body)?.groupValues?.getOrNull(1)
    }

    private fun extractBooleanField(body: String, key: String): Boolean? {
        val regex = Regex(""""$key"\s*:\s*(true|false)""", RegexOption.IGNORE_CASE)
        val raw = regex.find(body)?.groupValues?.getOrNull(1) ?: return null
        return raw.equals("true", ignoreCase = true)
    }

    private fun extractNumberField(body: String, key: String): Double? {
        val regex = Regex(""""$key"\s*:\s*(-?\d+(?:\.\d+)?)""")
        return regex.find(body)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
    }

    private fun settingValueToJson(value: SettingValue): String {
        return when (value) {
            is SettingValue.IntValue -> value.value.toString()
            is SettingValue.FloatValue -> value.value.toString()
            is SettingValue.BoolValue -> value.value.toString()
            is SettingValue.StringValue -> """"${value.value}""""
        }
    }

    private fun errorJson(code: String, message: String, details: String? = null): String {
        val detailsPart = details?.let { """,$it""" } ?: ""
        return """{"ok":false,"error":{"code":"$code","message":"$message"$detailsPart}}"""
    }

    private fun errorJsonResponse(
        status: NanoHTTPD.Response.Status,
        code: String,
        message: String,
    ): NanoHTTPD.Response {
        return NanoHTTPD.newFixedLengthResponse(
            status,
            "application/json",
            errorJson(code = code, message = message),
        )
    }

    private fun json(body: String): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "application/json",
            body,
        )
}
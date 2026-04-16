package com.seta.androidbridge.storage

import android.content.Context
import com.seta.androidbridge.domain.contracts.CaptureRepository
import com.seta.androidbridge.domain.models.CapturedImage
import com.seta.androidbridge.util.IdGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class CaptureFileStore(private val context: Context) : CaptureRepository {
    private val captureDir: File by lazy {
        File(context.filesDir, "captures").apply { mkdirs() }
    }

    override suspend fun saveJpeg(bytes: ByteArray): Result<CapturedImage> = withContext(Dispatchers.IO) {
        runCatching {
            val captureId = IdGenerator.newCaptureId()
            val file = File(captureDir, "$captureId.jpg")
            file.writeBytes(bytes)
            CapturedImage(
                captureId = captureId,
                fileName = file.name,
                absolutePath = file.absolutePath,
                mimeType = "image/jpeg",
                sizeBytes = file.length(),
                timestampEpochMs = System.currentTimeMillis(),
            )
        }
    }

    override suspend fun getCapture(captureId: String): Result<CapturedImage> = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(captureDir, "$captureId.jpg")
            require(file.exists()) { "Capture not found: $captureId" }
            CapturedImage(
                captureId = captureId,
                fileName = file.name,
                absolutePath = file.absolutePath,
                mimeType = "image/jpeg",
                sizeBytes = file.length(),
                timestampEpochMs = file.lastModified(),
            )
        }
    }
}

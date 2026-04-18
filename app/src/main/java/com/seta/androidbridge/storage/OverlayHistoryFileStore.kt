package com.seta.androidbridge.storage

import android.content.Context
import com.seta.androidbridge.domain.contracts.Logger
import com.seta.androidbridge.domain.contracts.OverlayHistoryRepository
import com.seta.androidbridge.domain.models.CapturedImage
import com.seta.androidbridge.domain.models.OverlayHistoryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class OverlayHistoryFileStore(
    context: Context,
    private val logger: Logger,
) : OverlayHistoryRepository {

    companion object {
        private const val MAX_PERSISTED_ENTRIES = 50
    }

    private val lock = ReentrantLock()
    private val captureDir: File = File(context.filesDir, "captures").apply { mkdirs() }
    private val snapshotFile: File = File(captureDir, "overlay_history.json")
    private val sessionHistory = mutableListOf<OverlayHistoryEntry>()
    private var nextSessionSequence: Long = 1L
    private val mutableHistoryFlow = MutableStateFlow<List<OverlayHistoryEntry>>(emptyList())

    override val historyFlow: StateFlow<List<OverlayHistoryEntry>> = mutableHistoryFlow

    init {
        loadSnapshot()
    }

    override fun getHistoryNewestFirst(): List<OverlayHistoryEntry> = lock.withLock {
        sessionHistory.asReversed().toList()
    }

    override fun getHistoryCount(): Int = lock.withLock { sessionHistory.size }

    override suspend fun recordBlenderCapture(capturedImage: CapturedImage) {
        withContext(Dispatchers.IO) {
            lock.withLock {
                sessionHistory.removeAll { it.captureId == capturedImage.captureId }
                sessionHistory += OverlayHistoryEntry(
                    captureId = capturedImage.captureId,
                    fileName = capturedImage.fileName,
                    absolutePath = capturedImage.absolutePath,
                    timestampEpochMs = capturedImage.timestampEpochMs,
                    sessionSequence = nextSessionSequence++,
                )
                persistSnapshotLocked()
                publishHistoryLocked()
            }
        }
    }

    override suspend fun purgeHistory() {
        withContext(Dispatchers.IO) {
            lock.withLock {
                sessionHistory.clear()
                nextSessionSequence = 1L
                persistSnapshotLocked()
                publishHistoryLocked()
            }
        }
    }

    private fun loadSnapshot() {
        lock.withLock {
            if (!snapshotFile.exists()) {
                nextSessionSequence = 1L
                publishHistoryLocked()
                return
            }

            runCatching {
                val root = JSONObject(snapshotFile.readText())
                val entries = root.optJSONArray("entries") ?: JSONArray()
                val loaded = mutableListOf<OverlayHistoryEntry>()
                var maxSequence = 0L

                for (index in 0 until entries.length()) {
                    val obj = entries.optJSONObject(index) ?: continue
                    val entry = obj.toEntryOrNull() ?: continue
                    if (!File(entry.absolutePath).exists()) {
                        continue
                    }
                    loaded += entry
                    if (entry.sessionSequence > maxSequence) {
                        maxSequence = entry.sessionSequence
                    }
                }

                sessionHistory.clear()
                sessionHistory.addAll(loaded.sortedBy { it.sessionSequence })
                nextSessionSequence = (maxSequence + 1L).coerceAtLeast(1L)

                if (loaded.size != entries.length()) {
                    persistSnapshotLocked()
                }
            }.onFailure { error ->
                logger.warn("Failed to load overlay history snapshot: ${error.message}")
                sessionHistory.clear()
                nextSessionSequence = 1L
            }

            publishHistoryLocked()
        }
    }

    private fun persistSnapshotLocked() {
        val persistedEntries = sessionHistory
            .filter { File(it.absolutePath).exists() }
            .takeLast(MAX_PERSISTED_ENTRIES)

        val payload = JSONObject().apply {
            put(
                "entries",
                JSONArray().apply {
                    persistedEntries.forEach { entry ->
                        put(
                            JSONObject().apply {
                                put("captureId", entry.captureId)
                                put("fileName", entry.fileName)
                                put("absolutePath", entry.absolutePath)
                                put("timestampEpochMs", entry.timestampEpochMs)
                                put("sessionSequence", entry.sessionSequence)
                            },
                        )
                    }
                },
            )
        }

        snapshotFile.parentFile?.mkdirs()
        snapshotFile.writeText(payload.toString())
    }

    private fun publishHistoryLocked() {
        mutableHistoryFlow.value = sessionHistory.asReversed().toList()
    }

    private fun JSONObject.toEntryOrNull(): OverlayHistoryEntry? {
        val captureId = optString("captureId").takeIf { it.isNotBlank() } ?: return null
        val fileName = optString("fileName").takeIf { it.isNotBlank() } ?: return null
        val absolutePath = optString("absolutePath").takeIf { it.isNotBlank() } ?: return null
        val timestampEpochMs = optLong("timestampEpochMs", -1L).takeIf { it >= 0L } ?: return null
        val sessionSequence = optLong("sessionSequence", -1L).takeIf { it >= 0L } ?: return null

        return OverlayHistoryEntry(
            captureId = captureId,
            fileName = fileName,
            absolutePath = absolutePath,
            timestampEpochMs = timestampEpochMs,
            sessionSequence = sessionSequence,
        )
    }
}

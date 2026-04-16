package com.seta.androidbridge.server

import com.seta.androidbridge.domain.contracts.CameraEngine
import java.io.InputStream
import kotlin.math.min

class MjpegStreamInputStream(
    private val cameraEngine: CameraEngine,
    private val boundary: String = "seta",
) : InputStream() {

    private var currentChunk: ByteArray = ByteArray(0)
    private var currentOffset: Int = 0

    private var lastFrameRef: ByteArray? = null
    private var lastEmitAtMs: Long = 0L

    override fun read(): Int {
        ensureChunk()
        if (currentOffset >= currentChunk.size) {
            return -1
        }
        return currentChunk[currentOffset++].toInt() and 0xFF
    }

    override fun read(buffer: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) return 0

        ensureChunk()
        if (currentOffset >= currentChunk.size) {
            return -1
        }

        val count = min(len, currentChunk.size - currentOffset)
        currentChunk.copyInto(
            destination = buffer,
            destinationOffset = off,
            startIndex = currentOffset,
            endIndex = currentOffset + count,
        )
        currentOffset += count
        return count
    }

    private fun ensureChunk() {
        if (currentOffset < currentChunk.size) {
            return
        }

        currentChunk = waitForNextChunk()
        currentOffset = 0
    }

    private fun waitForNextChunk(): ByteArray {
        val profile = cameraEngine.getPreviewProfile()
        val minFrameIntervalMs = (1000L / profile.maxFps.coerceAtLeast(1)).coerceAtLeast(1L)

        while (true) {
            if (!cameraEngine.isRemotePreviewRunning()) {
                return ByteArray(0)
            }

            val frame = cameraEngine.latestPreviewJpegFrame()
            if (frame == null) {
                Thread.sleep(10)
                continue
            }

            val now = System.currentTimeMillis()
            val sameReferenceAsLast = frame === lastFrameRef
            val enoughTimeElapsed = (now - lastEmitAtMs) >= minFrameIntervalMs

            if (sameReferenceAsLast || !enoughTimeElapsed) {
                Thread.sleep(5)
                continue
            }

            lastFrameRef = frame
            lastEmitAtMs = now
            return buildChunk(frame)
        }
    }

    private fun buildChunk(frame: ByteArray): ByteArray {
        val header = buildString {
            append("--")
            append(boundary)
            append("\r\n")
            append("Content-Type: image/jpeg\r\n")
            append("Content-Length: ")
            append(frame.size)
            append("\r\n")
            append("\r\n")
        }.toByteArray(Charsets.UTF_8)

        val footer = "\r\n".toByteArray(Charsets.UTF_8)

        return ByteArray(header.size + frame.size + footer.size).also { out ->
            var pos = 0
            header.copyInto(out, destinationOffset = pos)
            pos += header.size
            frame.copyInto(out, destinationOffset = pos)
            pos += frame.size
            footer.copyInto(out, destinationOffset = pos)
        }
    }
}
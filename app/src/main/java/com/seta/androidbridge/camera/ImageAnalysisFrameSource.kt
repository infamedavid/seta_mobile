package com.seta.androidbridge.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.seta.androidbridge.domain.contracts.Logger
import com.seta.androidbridge.domain.models.PreviewProfile
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class ImageAnalysisFrameSource(
    private val logger: Logger,
) {

    private data class FramePacket(
        val sequence: Long,
        val jpegBytes: ByteArray,
    )

    private val latestFrame = AtomicReference<FramePacket?>(null)
    private val analyzerExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var currentProfile: PreviewProfile? = null

    @Volatile
    private var frameSequence: Long = 0L

    fun buildUseCase(profile: PreviewProfile): ImageAnalysis {
        currentProfile = profile

        return ImageAnalysis.Builder()
            .setTargetResolution(Size(profile.width, profile.height))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(analyzerExecutor) { imageProxy ->
                    try {
                        val jpegBytes = imageProxyToJpeg(imageProxy, profile)
                        val packet = FramePacket(
                            sequence = ++frameSequence,
                            jpegBytes = jpegBytes,
                        )
                        latestFrame.set(packet)
                    } catch (t: Throwable) {
                        logger.warn("ImageAnalysis frame conversion failed: ${t.message}")
                    } finally {
                        imageProxy.close()
                    }
                }
            }
    }

    fun latestJpegFrame(): ByteArray? = latestFrame.get()?.jpegBytes

    fun latestFrameSequence(): Long = latestFrame.get()?.sequence ?: -1L

    fun clear() {
        latestFrame.set(null)
        frameSequence = 0L
    }

    fun profile(): PreviewProfile? = currentProfile

    private fun imageProxyToJpeg(
        imageProxy: ImageProxy,
        profile: PreviewProfile,
    ): ByteArray {
        val nv21 = yuv420888ToNv21(imageProxy)
        val yuvImage = YuvImage(
            nv21,
            android.graphics.ImageFormat.NV21,
            imageProxy.width,
            imageProxy.height,
            null,
        )

        val jpegStream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(
            Rect(0, 0, imageProxy.width, imageProxy.height),
            profile.jpegQuality,
            jpegStream,
        )

        val jpegBytes = jpegStream.toByteArray()
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees

        if (rotationDegrees == 0) {
            return jpegBytes
        }

        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            ?: return jpegBytes

        val matrix = Matrix().apply {
            postRotate(rotationDegrees.toFloat())
        }

        val rotatedBitmap = Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true,
        )

        val rotatedStream = ByteArrayOutputStream()
        rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, profile.jpegQuality, rotatedStream)

        if (rotatedBitmap !== bitmap) {
            rotatedBitmap.recycle()
        }
        bitmap.recycle()

        return rotatedStream.toByteArray()
    }

    private fun yuv420888ToNv21(imageProxy: ImageProxy): ByteArray {
        val width = imageProxy.width
        val height = imageProxy.height
        val ySize = width * height
        val uvSize = width * height / 4

        val out = ByteArray(ySize + uvSize * 2)

        copyPlane(
            plane = imageProxy.planes[0],
            width = width,
            height = height,
            out = out,
            offset = 0,
            pixelStrideOverride = 1,
            outputStride = 1,
        )

        val uPlane = imageProxy.planes[1]
        val vPlane = imageProxy.planes[2]

        val chromaWidth = width / 2
        val chromaHeight = height / 2

        val vBuffer = vPlane.buffer.duplicate()
        val uBuffer = uPlane.buffer.duplicate()

        val vRowStride = vPlane.rowStride
        val uRowStride = uPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        val uPixelStride = uPlane.pixelStride

        var outputOffset = ySize

        for (row in 0 until chromaHeight) {
            val vRowStart = row * vRowStride
            val uRowStart = row * uRowStride

            for (col in 0 until chromaWidth) {
                out[outputOffset++] = vBuffer.get(vRowStart + col * vPixelStride)
                out[outputOffset++] = uBuffer.get(uRowStart + col * uPixelStride)
            }
        }

        return out
    }

    private fun copyPlane(
        plane: ImageProxy.PlaneProxy,
        width: Int,
        height: Int,
        out: ByteArray,
        offset: Int,
        pixelStrideOverride: Int,
        outputStride: Int,
    ) {
        val buffer = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = if (pixelStrideOverride > 0) pixelStrideOverride else plane.pixelStride

        var outputOffset = offset

        for (row in 0 until height) {
            val rowStart = row * rowStride
            for (col in 0 until width) {
                out[outputOffset] = buffer.get(rowStart + col * pixelStride)
                outputOffset += outputStride
            }
        }
    }
}
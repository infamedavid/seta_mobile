package com.seta.androidbridge.camera

import android.graphics.Bitmap
import androidx.camera.view.PreviewView
import java.io.ByteArrayOutputStream

class PreviewBitmapFrameSource(
    private val jpegQuality: Int = 70,
) : PreviewFrameSource {

    @Volatile
    private var previewView: PreviewView? = null

    fun attach(previewView: PreviewView) {
        this.previewView = previewView
    }

    fun detach() {
        this.previewView = null
    }

    override fun latestJpegFrame(): ByteArray? {
        val bitmap = previewView?.bitmap ?: return null
        return bitmapToJpeg(bitmap, jpegQuality)
    }

    private fun bitmapToJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
        return out.toByteArray()
    }
}

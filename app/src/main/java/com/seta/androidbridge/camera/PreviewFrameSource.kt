package com.seta.androidbridge.camera

interface PreviewFrameSource {
    fun latestJpegFrame(): ByteArray?
}

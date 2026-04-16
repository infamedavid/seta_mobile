package com.seta.androidbridge.util

import java.util.UUID

object IdGenerator {
    fun newCaptureId(): String = "cap_" + UUID.randomUUID().toString().replace("-", "").take(12)
}

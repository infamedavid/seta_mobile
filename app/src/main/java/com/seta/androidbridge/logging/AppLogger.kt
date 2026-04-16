package com.seta.androidbridge.logging

import android.util.Log
import com.seta.androidbridge.domain.contracts.Logger

class AppLogger(private val tag: String = "SETA-Mobile") : Logger {
    override fun info(message: String) {
        Log.i(tag, message)
    }

    override fun warn(message: String) {
        Log.w(tag, message)
    }

    override fun error(message: String) {
        Log.e(tag, message)
    }
}
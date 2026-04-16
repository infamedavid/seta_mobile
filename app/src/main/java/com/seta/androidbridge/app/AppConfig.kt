package com.seta.androidbridge.app

data class AppConfig(
    val port: Int = 8765,
    val authEnabled: Boolean = false,
    val accessToken: String? = null,
    val defaultLensId: String? = null,
)

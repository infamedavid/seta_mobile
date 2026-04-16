package com.seta.androidbridge.app

import android.app.Application

class SetaMobileApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(applicationContext)
    }
}

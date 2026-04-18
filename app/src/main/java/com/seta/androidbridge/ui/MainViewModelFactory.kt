package com.seta.androidbridge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.seta.androidbridge.app.AppContainer

class MainViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return MainViewModel(
            cameraEngine = container.cameraEngine,
            httpBridgeService = container.httpBridgeService,
            sessionStateStore = container.sessionStateStore,
            networkInfoProvider = container.networkInfoProvider,
            overlayHistoryRepository = container.overlayHistoryRepository,
            logger = container.logger,
        ) as T
    }
}

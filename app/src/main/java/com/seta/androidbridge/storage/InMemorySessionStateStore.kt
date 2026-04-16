package com.seta.androidbridge.storage

import com.seta.androidbridge.domain.contracts.SessionStateStore
import com.seta.androidbridge.domain.models.AppSessionState
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class InMemorySessionStateStore(
    initialState: AppSessionState = AppSessionState(),
) : SessionStateStore {
    private val lock = ReentrantLock()
    private var state: AppSessionState = initialState

    override fun getState(): AppSessionState = lock.withLock { state }

    override fun update(transform: (AppSessionState) -> AppSessionState) {
        lock.withLock { state = transform(state) }
    }
}

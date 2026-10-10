package dev.android.ide.browser

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Coalesces browser-state writes without losing the latest durable snapshot. */
class BrowserPersistenceCoordinator(
    private val scope: CoroutineScope,
    private val delayMs: Long = 350L,
) {
    private var pendingWrite: Job? = null
    private var dirty = false
    private var latestWrite: (() -> Unit)? = null

    fun markDirty(writeLatestSnapshot: () -> Unit) {
        dirty = true
        latestWrite = writeLatestSnapshot
        pendingWrite?.cancel()
        pendingWrite = scope.launch {
            delay(delayMs)
            flush()
        }
    }

    fun flush() {
        pendingWrite?.cancel()
        pendingWrite = null
        if (!dirty) return
        dirty = false
        val write = latestWrite
        latestWrite = null
        write?.invoke()
    }

    fun cancel() {
        pendingWrite?.cancel()
        pendingWrite = null
        dirty = false
        latestWrite = null
    }
}

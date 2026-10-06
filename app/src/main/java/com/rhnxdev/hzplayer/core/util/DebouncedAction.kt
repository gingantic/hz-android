package com.rhnxdev.hzplayer.core.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runs [action] on [scope] after [debounceMs] of quiet, coalescing bursts of
 * [schedule] calls into a single run.
 *
 * A run already in progress is awaited before the next one starts, so the last
 * scheduled action always completes last. Not thread-safe — call from one
 * thread (both call sites are Main).
 */
class DebouncedAction(
    private val scope: CoroutineScope,
    private val debounceMs: Long,
) {
    private var job: Job? = null

    /** Schedules [action], superseding any run that has not started yet. */
    fun schedule(action: suspend () -> Unit) = launchAfter(debounceMs, action)

    /** Runs [action] at once, superseding any pending scheduled run. */
    fun runNow(action: suspend () -> Unit) = launchAfter(0L, action)

    /** Drops a pending run; an action already executing is left to finish. */
    fun cancel() {
        job?.cancel()
    }

    private fun launchAfter(delayMs: Long, action: suspend () -> Unit) {
        val previous = job
        previous?.cancel()
        job = scope.launch {
            previous?.join()
            delay(delayMs)
            action()
        }
    }
}

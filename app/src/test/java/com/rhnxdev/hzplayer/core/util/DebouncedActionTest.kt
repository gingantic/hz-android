package com.rhnxdev.hzplayer.core.util

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * B1 relies on this coalescing: a burst of keystroke-driven calls must produce
 * a single deferred run, a discrete change must run at once, and the last
 * scheduled action must always complete last.
 */
class DebouncedActionTest {

    private val debounceMs = 80L

    /** Generous: an order of magnitude past the debounce, so only a stalled VM flakes. */
    private val settleMs = 400L

    @Test
    fun burstOfSchedules_runsOnce() = runBlocking {
        val runs = AtomicInteger()
        val debounced = DebouncedAction(this, debounceMs)

        repeat(5) { debounced.schedule { runs.incrementAndGet() } }
        delay(settleMs)

        assertEquals(1, runs.get())
    }

    @Test
    fun schedulingAgainAfterThePause_runsAgain() = runBlocking {
        val runs = AtomicInteger()
        val debounced = DebouncedAction(this, debounceMs)

        debounced.schedule { runs.incrementAndGet() }
        delay(settleMs)
        debounced.schedule { runs.incrementAndGet() }
        delay(settleMs)

        assertEquals(2, runs.get())
    }

    @Test
    fun runNow_supersedesAPendingRun() = runBlocking {
        val order = mutableListOf<String>()
        val debounced = DebouncedAction(this, debounceMs)

        debounced.schedule { order += "scheduled" }
        debounced.runNow { order += "now" }
        delay(settleMs)

        assertEquals(listOf("now"), order)
    }

    @Test
    fun runThatCannotBeInterrupted_isAwaitedBeforeTheNextStarts() = runBlocking {
        val order = mutableListOf<String>()
        val debounced = DebouncedAction(this, debounceMs = 0)

        debounced.schedule {
            order += "first-start"
            // Mirrors the production rebuild: a native call that can't be
            // interrupted, so cancellation only takes effect once it returns.
            withContext(NonCancellable) { delay(120) }
            order += "first-end"
        }
        delay(20)   // let the first run start
        debounced.runNow { order += "second" }
        delay(settleMs)

        assertEquals(listOf("first-start", "first-end", "second"), order)
    }

    @Test
    fun cancel_dropsThePendingRun() = runBlocking {
        val runs = AtomicInteger()
        val debounced = DebouncedAction(this, debounceMs)

        debounced.schedule { runs.incrementAndGet() }
        debounced.cancel()
        delay(settleMs)

        assertEquals(0, runs.get())
    }
}

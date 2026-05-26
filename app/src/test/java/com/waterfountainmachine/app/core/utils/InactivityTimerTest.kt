package com.waterfountainmachine.app.core.utils

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [InactivityTimer]. The timer uses `Dispatchers.Main` directly,
 * so we substitute a [StandardTestDispatcher] and drive virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InactivityTimerTest {

    private val testDispatcher = StandardTestDispatcher()
    private val timeoutMs = 5_000L

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `start fires onTimeout after exactly timeoutMillis`() = runTest {
        var fired = 0
        val timer = InactivityTimer(timeoutMs) { fired++ }

        timer.start()

        // Just before the deadline — must not fire yet.
        advanceTimeBy(timeoutMs - 1)
        runCurrent()
        assertThat(fired).isEqualTo(0)

        // Crossing the deadline must fire exactly once.
        advanceTimeBy(2)
        runCurrent()
        assertThat(fired).isEqualTo(1)
    }

    @Test
    fun `stop before timeout prevents callback`() = runTest {
        var fired = 0
        val timer = InactivityTimer(timeoutMs) { fired++ }

        timer.start()
        advanceTimeBy(timeoutMs / 2)
        runCurrent()
        timer.stop()

        advanceTimeBy(timeoutMs * 2)
        runCurrent()

        assertThat(fired).isEqualTo(0)
    }

    @Test
    fun `reset restarts the countdown`() = runTest {
        var fired = 0
        val timer = InactivityTimer(timeoutMs) { fired++ }

        timer.start()
        advanceTimeBy(timeoutMs - 100)
        runCurrent()

        timer.reset()
        // After reset, the OLD deadline should be moot.
        advanceTimeBy(200)
        runCurrent()
        assertThat(fired).isEqualTo(0)

        // New deadline is now ~timeoutMs from reset.
        advanceTimeBy(timeoutMs)
        runCurrent()
        assertThat(fired).isEqualTo(1)
    }

    @Test
    fun `critical state prevents timeout and reschedules`() = runTest {
        var fired = 0
        val timer = InactivityTimer(timeoutMs) { fired++ }

        timer.start()
        timer.setCriticalState(true)

        // Cross the original deadline while critical — must NOT fire.
        advanceTimeBy(timeoutMs + 100)
        runCurrent()
        assertThat(fired).isEqualTo(0)

        // Exiting critical state allows the next reschedule to fire.
        timer.setCriticalState(false)
        advanceTimeBy(timeoutMs + 100)
        runCurrent()
        assertThat(fired).isEqualTo(1)
    }

    @Test
    fun `pause and resume are aliases for critical state`() = runTest {
        val timer = InactivityTimer(timeoutMs) { /* no-op */ }

        timer.pause()
        assertThat(timer.isInCriticalState()).isTrue()

        timer.resume()
        assertThat(timer.isInCriticalState()).isFalse()
    }

    @Test
    fun `cleanup cancels pending callback`() = runTest {
        var fired = 0
        val timer = InactivityTimer(timeoutMs) { fired++ }

        timer.start()
        advanceTimeBy(timeoutMs - 1)
        runCurrent()
        timer.cleanup()

        advanceTimeBy(timeoutMs * 5)
        runCurrent()
        assertThat(fired).isEqualTo(0)
    }

    @Test
    fun `cancel is an alias for cleanup`() = runTest {
        var fired = 0
        val timer = InactivityTimer(timeoutMs) { fired++ }

        timer.start()
        timer.cancel()

        advanceTimeBy(timeoutMs * 5)
        runCurrent()
        assertThat(fired).isEqualTo(0)
    }

    @Test
    fun `start while already running supersedes the previous job`() = runTest {
        var fired = 0
        val timer = InactivityTimer(timeoutMs) { fired++ }

        timer.start()
        advanceTimeBy(timeoutMs - 100)
        runCurrent()

        // Calling start() again must NOT fire on the old deadline AND must
        // not double-fire later. Effectively a reset.
        timer.start()
        advanceTimeBy(200)
        runCurrent()
        assertThat(fired).isEqualTo(0)

        advanceTimeBy(timeoutMs)
        runCurrent()
        assertThat(fired).isEqualTo(1)
    }
}

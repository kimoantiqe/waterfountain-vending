package com.waterfountainmachine.app.ui.views

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavioral tests for [RipplePondView].
 *
 * Locks in the neumorphic ripple contract:
 *  - [RipplePondView.startCadence] schedules a recurring emitter so a
 *    fresh ripple appears at each EMIT_INTERVAL_MS while active.
 *  - [RipplePondView.crest] cancels the cadence and drains the pond
 *    before firing the mega ripple (no collisions with in-flight beats).
 *  - [RipplePondView.pulse] emits a single ripple, used by Phase 3.
 *  - [RipplePondView.stop] clears all in-flight + pending work.
 *
 * Uses [HiltTestApplication] to avoid booting the real
 * WaterFountainApplication.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [33])
class RipplePondViewTest {

    private fun newView(): RipplePondView {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return RipplePondView(ctx)
    }

    @Test
    fun `startCadence schedules the recurring emitter and emits the first ripple`() {
        val view = newView()
        view.startCadence()
        assertThat(view.isCadenceScheduled()).isTrue()
        // First ripple fires immediately so the cadence visibly begins.
        assertThat(view.activeRippleCount()).isEqualTo(1)
        view.stop()
    }

    @Test
    fun `startCadence is idempotent so back-to-back calls don't double-schedule`() {
        val view = newView()
        view.startCadence()
        val firstActive = view.activeRippleCount()
        view.startCadence()
        // Second call is a no-op: still one ripple from the initial emit.
        assertThat(view.activeRippleCount()).isEqualTo(firstActive)
        assertThat(view.isCadenceScheduled()).isTrue()
        view.stop()
    }

    @Test
    fun `crest cancels the cadence and blends the climax with in-flight ripples`() {
        val view = newView()
        view.startCadence()
        assertThat(view.isCadenceScheduled()).isTrue()
        // startCadence emitted the first ripple immediately.
        val priorRipples = view.activeRippleCount()
        view.crest()
        // Cadence is cancelled (no more recurring beats) but the in-flight
        // ripples are intentionally left to blend with the climax — so we
        // expect priorRipples + 1 (the mega) in flight.
        assertThat(view.isCadenceScheduled()).isFalse()
        assertThat(view.activeRippleCount()).isEqualTo(priorRipples + 1)
        view.stop()
    }

    @Test
    fun `crest emits a ripple immediately`() {
        val view = newView()
        view.crest()
        assertThat(view.activeRippleCount()).isEqualTo(1)
        view.stop()
    }

    @Test
    fun `pulse emits a single ripple for the drop ring-flash`() {
        val view = newView()
        view.pulse()
        assertThat(view.activeRippleCount()).isEqualTo(1)
        view.stop()
    }

    @Test
    fun `stop clears all in-flight and pending state`() {
        val view = newView()
        view.startCadence()
        view.pulse()
        view.stop()
        assertThat(view.isCadenceScheduled()).isFalse()
        assertThat(view.activeRippleCount()).isEqualTo(0)
    }
}

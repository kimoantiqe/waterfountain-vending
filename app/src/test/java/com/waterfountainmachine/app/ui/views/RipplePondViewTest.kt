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
 * Locks in the Phase A cadence contract:
 *  - [RipplePondView.startCadence] schedules the 3 building beats.
 *  - [RipplePondView.crest] cancels any pending beats so the mega-ripple
 *    can't collide with a tail-end beat or a future internally-scheduled
 *    crest (defensive idempotence — see Phase 2 polish bundle).
 *  - [RipplePondView.pulse] emits a single ripple synchronously, used by
 *    Phase 3 (drop) as the ring-flash punctuation.
 *  - [RipplePondView.stop] clears all in-flight + pending work.
 *
 * Uses [HiltTestApplication] to avoid booting the real
 * WaterFountainApplication (which initializes SQLCipher / EncryptedPrefs
 * and hits the host keystore — irrelevant for view-level tests).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [33])
class RipplePondViewTest {

    private fun newView(): RipplePondView {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return RipplePondView(ctx)
    }

    @Test
    fun `startCadence schedules pending beats`() {
        val view = newView()
        view.startCadence()
        assertThat(view.pendingPostCount()).isEqualTo(view.beatOffsetsMs.size)
        view.stop()
    }

    @Test
    fun `startCadence is idempotent so back-to-back calls don't double-schedule`() {
        val view = newView()
        view.startCadence()
        val firstCount = view.pendingPostCount()
        view.startCadence()
        assertThat(view.pendingPostCount()).isEqualTo(firstCount)
        view.stop()
    }

    @Test
    fun `crest cancels pending beats so the climax can't collide with a tail beat`() {
        val view = newView()
        view.startCadence()
        assertThat(view.pendingPostCount()).isGreaterThan(0)
        view.crest()
        // After crest, no beat is allowed to still be scheduled.
        assertThat(view.pendingPostCount()).isEqualTo(0)
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
        assertThat(view.pendingPostCount()).isEqualTo(0)
        assertThat(view.activeRippleCount()).isEqualTo(0)
    }
}

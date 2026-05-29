package com.waterfountainmachine.app.features.vending.ui

import android.view.LayoutInflater
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.waterfountainmachine.app.databinding.ActivityVendingAnimationBinding
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Layout-level guarantees for the Phase A vending animation surface
 * (activity_vending_animation.xml). We inflate the binding under
 * HiltTestApplication (matches other Activity tests in this package —
 * the real WaterFountainApplication boots SQLCipher / EncryptedPrefs
 * which need a Robolectric-friendly Application) to lock in the static
 * XML contract [VendingAnimationActivity] depends on:
 *
 *  - Phase 1 surface: centered phase1Container with wfLogoPhase1 +
 *    statusText, all starting alpha 0. statusText pre-populated with
 *    the scan-QR reminder copy.
 *  - Phase 2/3 surface: ringContainer + logoImage + centerMessage +
 *    completionText, all starting alpha 0. centerMessage starts EMPTY
 *    (no default tagline — advertiser-only after the 5b polish bundle).
 *  - The ripple pond fills the activity surface so the cadence crescendo
 *    can radiate full-screen.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [33])
class VendingAnimationLayoutTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    private fun inflate(): ActivityVendingAnimationBinding {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return ActivityVendingAnimationBinding.inflate(LayoutInflater.from(ctx))
    }

    @Test
    fun `ripple pond exists and fills the activity surface`() {
        val binding = inflate()
        assertThat(binding.ripplePond).isNotNull()
        // match_parent encodes as -1 in LayoutParams.
        assertThat(binding.ripplePond.layoutParams.width).isEqualTo(-1)
        assertThat(binding.ripplePond.layoutParams.height).isEqualTo(-1)
    }

    @Test
    fun `centerMessage starts hidden and empty so unbranded vends keep the slot clean`() {
        val binding = inflate()
        // alpha 0 so revealCenterMessage() can rise it in at the Phase 2
        // reveal; text empty so revealCenterMessage() no-ops if no
        // advertiser animationMessage was supplied.
        assertThat(binding.centerMessage.alpha).isEqualTo(0f)
        assertThat(binding.centerMessage.text.toString()).isEmpty()
    }

    @Test
    fun `logo image and ring container are present for the Phase 2 disc reveal`() {
        val binding = inflate()
        // Disc is the visible brand surface through Phase 2 + 3; the
        // morphToLogo() crossfade swaps the bitmap in place. If either
        // view disappears, the reveal target is gone.
        assertThat(binding.ringContainer).isNotNull()
        assertThat(binding.logoImage).isNotNull()
        assertThat(binding.logoImage.alpha).isEqualTo(0f) // fades in via revealDisc()
    }

    @Test
    fun `phase 1 stack is present and starts hidden so it can fade in for the scan reminder`() {
        val binding = inflate()
        // wfLogoPhase1 is the bobbing WF brand mark above the QR reminder.
        // statusText sits centered below it. Both start alpha 0;
        // fadeInPhase1() rises them in.
        assertThat(binding.wfLogoPhase1).isNotNull()
        assertThat(binding.wfLogoPhase1.alpha).isEqualTo(0f)
        assertThat(binding.statusText.alpha).isEqualTo(0f)
        // statusText pre-populated so Phase 1 always has content.
        assertThat(binding.statusText.text.toString()).contains("Scan the QR")
    }

    @Test
    fun `pickup reminder panel exists and starts hidden`() {
        val binding = inflate()
        assertThat(binding.pickupReminderPanel.visibility).isEqualTo(View.GONE)
    }

    @Test
    fun `completion text starts invisible so it can crossfade in at the drop`() {
        val binding = inflate()
        assertThat(binding.completionText.alpha).isEqualTo(0f)
    }
}

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
 * Layout-level guarantees for the sponsorship billboard added to
 * activity_vending_animation.xml. We inflate the binding under
 * HiltTestApplication (matches other Activity tests in this package —
 * the real WaterFountainApplication boots SQLCipher / EncryptedPrefs
 * which need a Robolectric-friendly Application) to verify the static
 * XML invariants the [VendingAnimationActivity] code relies on:
 *
 *  - billboard starts hidden so a vend with no animationMessage never
 *    flashes an empty panel;
 *  - byline starts hidden so a vend with no advertiserName never shows
 *    an orphan "brought to you by" line.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [33])
class SponsorshipBillboardLayoutTest {

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
    fun `sponsorship billboard starts hidden`() {
        val binding = inflate()
        assertThat(binding.sponsorshipBillboard.visibility).isEqualTo(View.GONE)
        assertThat(binding.sponsorshipBillboard.alpha).isEqualTo(0f)
    }

    @Test
    fun `sponsorship byline starts hidden so a missing advertiser name does not show an orphan label`() {
        val binding = inflate()
        assertThat(binding.sponsorshipByline.visibility).isEqualTo(View.GONE)
    }

    @Test
    fun `sponsorship message view is empty by default`() {
        val binding = inflate()
        assertThat(binding.sponsorshipMessage.text.toString()).isEmpty()
    }
}

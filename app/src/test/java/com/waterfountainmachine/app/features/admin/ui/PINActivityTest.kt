package com.waterfountainmachine.app.features.admin.ui

import android.os.Looper
import android.widget.ImageButton
import androidx.test.core.app.ActivityScenario
import com.google.common.truth.Truth.assertThat
import com.waterfountainmachine.app.R
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Robolectric tests for [PINActivity].
 *
 * PINActivity is the lightweight "enter PIN" screen reached from the
 * vending flow. The activity itself owns only:
 *  - back-button click handler
 *  - hardware-key back handler
 *  - 30-second inactivity timer
 *
 * The interesting PIN-verification work lives in [AdminPinManager] (covered
 * by 32 unit tests) and the rate-limited entry surface lives in
 * [AdminAuthActivity] (covered by 5 Robolectric tests). This test is the
 * thin slice that asserts the screen actually opens and the dismiss paths
 * fire `finish()` -- a regression here would soft-lock the customer flow.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [33])
class PINActivityTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Test
    fun `activity launches without crashing`() {
        ActivityScenario.launch(PINActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertThat(activity.isFinishing).isFalse()
                // Back button is the only interactive widget on this screen
                // -- make sure it actually exists in the inflated tree.
                val backButton = activity.findViewById<ImageButton>(R.id.backButton)
                assertThat(backButton).isNotNull()
            }
        }
    }

    @Test
    fun `back button finishes the activity`() {
        ActivityScenario.launch(PINActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<ImageButton>(R.id.backButton).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                assertThat(activity.isFinishing).isTrue()
            }
        }
    }

    @Test
    fun `inactivity timer finishes the activity after 30 seconds`() {
        ActivityScenario.launch(PINActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertThat(activity.isFinishing).isFalse()
                // The activity arms a 30s InactivityTimer in onCreate. Drain
                // a little longer than that so the timer's postDelayed
                // callback runs and calls finish().
                shadowOf(Looper.getMainLooper()).idleFor(31, TimeUnit.SECONDS)
                assertThat(activity.isFinishing).isTrue()
            }
        }
    }
}

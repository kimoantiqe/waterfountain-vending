package com.waterfountainmachine.app.features.error

import android.app.Application
import android.content.Intent
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.waterfountainmachine.app.R
import com.waterfountainmachine.app.core.utils.UserErrorMessages
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * Robolectric coverage for [ErrorActivity].
 *
 * Focus: the message-routing contract that drives the customer-facing
 * lockout screen --
 *   - default message when no extra is supplied,
 *   - custom message when [ErrorActivity.EXTRA_MESSAGE] is set,
 *   - intent extras are respected even when they collide with the
 *     bundled `UserErrorMessages` constants.
 *
 * We DELIBERATELY do not assert on the auto-dismiss timer or the sound
 * load coroutine: both are launched on `lifecycleScope` with `delay(...)`
 * and would require advancing the Robolectric main looper, which is
 * brittle and not what these tests are meant to cover.
 *
 * The application override prevents `WaterFountainApplication.onCreate`
 * (Keystore + Firebase) from running on the JVM; the real manifest is
 * still loaded so that `ErrorActivity` is registered and its theme /
 * data-binding layout resolve.
 */
@RunWith(AndroidJUnit4::class)
@Config(
    sdk = [33],
    application = Application::class,
)
class ErrorActivityTest {

    @Test
    fun `default message is shown when no extra is provided`() {
        val activity = Robolectric.buildActivity(ErrorActivity::class.java)
            .create()
            .start()
            .resume()
            .get()

        val messageText = activity.findViewById<TextView>(R.id.messageText)
        assertThat(messageText.text.toString())
            .isEqualTo(UserErrorMessages.DAILY_LIMIT_REACHED)
    }

    @Test
    fun `custom EXTRA_MESSAGE is shown when provided`() {
        val customMessage = "Test custom error message"
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            ErrorActivity::class.java
        ).putExtra(ErrorActivity.EXTRA_MESSAGE, customMessage)

        val activity = Robolectric.buildActivity(ErrorActivity::class.java, intent)
            .create()
            .start()
            .resume()
            .get()

        val messageText = activity.findViewById<TextView>(R.id.messageText)
        assertThat(messageText.text.toString()).isEqualTo(customMessage)
    }

    @Test
    fun `EXTRA_MESSAGE accepts the generic error constant`() {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            ErrorActivity::class.java
        ).putExtra(ErrorActivity.EXTRA_MESSAGE, UserErrorMessages.GENERIC_ERROR)

        val activity = Robolectric.buildActivity(ErrorActivity::class.java, intent)
            .create()
            .start()
            .resume()
            .get()

        val messageText = activity.findViewById<TextView>(R.id.messageText)
        assertThat(messageText.text.toString()).isEqualTo(UserErrorMessages.GENERIC_ERROR)
    }

    @Test
    fun `back press is consumed and does not finish the activity`() {
        val controller = Robolectric.buildActivity(ErrorActivity::class.java)
            .create()
            .start()
            .resume()
        val activity = controller.get()

        // The customer-facing error must not be dismissable by Back --
        // the back-press callback handles the event and never finishes.
        activity.onBackPressedDispatcher.onBackPressed()

        assertThat(activity.isFinishing).isFalse()
    }
}

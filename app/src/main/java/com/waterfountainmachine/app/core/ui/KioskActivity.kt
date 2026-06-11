package com.waterfountainmachine.app.core.ui

import android.os.Bundle
import android.view.View
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import com.waterfountainmachine.app.core.di.HealthMonitorModule
import com.waterfountainmachine.app.core.utils.AppLog
import com.waterfountainmachine.app.core.utils.ErrorScreenUtil
import com.waterfountainmachine.app.core.utils.FullScreenUtils
import com.waterfountainmachine.app.core.utils.UserErrorMessages
import java.util.concurrent.TimeUnit

/**
 * Base activity for every screen that runs in the kiosk window.
 *
 * Owns the chrome that used to be copy/pasted across MainActivity,
 * SMSActivity, SMSVerifyActivity, VendingAnimationActivity, ErrorActivity,
 * AdminPanelActivity, AdminAuthActivity and PINActivity:
 *
 *   1. Sets a solid window background BEFORE the subclass inflates its layout
 *      (prevents a white flash during transitions). Override
 *      [kioskWindowBackground] to use a different drawable -- e.g. the
 *      vending gradient.
 *   2. Routes hardware volume buttons to the media stream so the system
 *      volume HUD never appears on a customer-facing screen.
 *   3. Hides system bars (immersive) and re-applies the immersive flags
 *      whenever the window regains focus -- previously identical
 *      [onWindowFocusChanged] overrides in every activity.
 *
 * Subclass contract:
 *   - In `onCreate`, after calling `super.onCreate()` and `setContentView()`,
 *     call [applyFullScreen]. Pass the inflated view via [fullScreenRoot].
 *   - Customer-facing flows should call [bailIfMachineDisabled] very early
 *     in `onCreate` and `return` immediately if it returns `true`.
 *
 * This class deliberately does NOT take ownership of [InactivityTimer],
 * [SoundManager] or [AnalyticsManager] -- each subclass uses those with
 * different timeouts / sound sets / screen names, so consolidating would
 * leak abstraction.
 */
abstract class KioskActivity : AppCompatActivity() {

    /**
     * Root view that immersive flags are applied to. Default returns
     * `window.decorView` which works before the subclass has inflated its
     * binding. Subclasses should override to return `binding.root` once it
     * exists; the getter is re-evaluated on every focus change.
     */
    protected open val fullScreenRoot: View
        get() = window.decorView

    /**
     * Drawable resource id used as the window background. Defaults to solid
     * black to mask the white flash between activity transitions. Override
     * to use a gradient or themed background.
     */
    @get:DrawableRes
    protected open val kioskWindowBackground: Int = android.R.color.black

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(kioskWindowBackground)
        @Suppress("DEPRECATION")
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
    }

    /**
     * Hide system bars. Subclass MUST call this after `setContentView()` so
     * that [fullScreenRoot] resolves to the inflated tree.
     */
    protected fun applyFullScreen() {
        FullScreenUtils.setupFullScreen(window, fullScreenRoot)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            FullScreenUtils.reapplyFullScreen(window, fullScreenRoot)
        }
    }

    /**
     * Show the standard "machine disabled" full-screen error and finish this
     * activity if the health monitor reports the kiosk is out of service.
     *
     * Resolves the monitor through [HealthMonitorModule.getMachineHealthMonitor]
     * which returns the same singleton Hilt injects elsewhere, so the
     * Mock/Real choice picked at app startup is honoured here too.
     *
     * @param reason Short tag added to the log line, e.g. "blocking SMS entry".
     * @return `true` if the activity has shown the error and called
     *     `finish()` -- the caller MUST `return` in this case.
     */
    protected fun bailIfMachineDisabled(reason: String): Boolean {
        val monitor = HealthMonitorModule.getMachineHealthMonitor(this)
        if (!monitor.isMachineDisabled()) return false

        AppLog.w(localClassName, "Machine is DISABLED - $reason")
        ErrorScreenUtil.showError(
            context = this,
            message = UserErrorMessages.MACHINE_DISABLED,
            displayDuration = TimeUnit.HOURS.toMillis(24),
            watchMachineStatus = true
        )
        finish()
        return true
    }
}

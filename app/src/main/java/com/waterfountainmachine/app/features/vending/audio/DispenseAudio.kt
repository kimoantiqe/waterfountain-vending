package com.waterfountainmachine.app.features.vending.audio

import android.content.Context
import androidx.annotation.RawRes
import com.waterfountainmachine.app.core.utils.AppLog
import com.waterfountainmachine.app.core.utils.SoundManager

/**
 * Cue map + playback for the dispense animation soundtrack.
 *
 * The dispense piece is a fixed ~26 s, 3-phase composition (Build →
 * Advertiser hold → Drop). The visual choreography in
 * [com.waterfountainmachine.app.features.vending.ui.VendingAnimationActivity]
 * fires events on its timeline; this class translates those events
 * into audio cues with sensible mixing levels.
 *
 * Each cue is looked up by resource name via `getIdentifier` so the
 * code compiles even when an OGG hasn't been generated yet. Missing
 * cues no-op silently so the visual choreography keeps working while
 * the audio profile is being assembled.
 *
 * Levels are unified here so the mix lives in one file:
 *  - Pad sits low (0.18) as a continuous bed.
 *  - Ripple drops crescendo from 0.35 → 0.55 in lock-step with the
 *    visual cadence's intensity ramp.
 *  - The crest swell is the loudest single event (0.7).
 *  - Brand and ready chimes are soft accents (≤ 0.4) so they don't
 *    overshadow the moments they're punctuating.
 */
class DispenseAudio(private val context: Context) {

    private val sound = SoundManager(context)

    /** Resource id or 0 if the OGG hasn't been added to res/raw yet. */
    private val pad = rawId("pad_ambient")
    private val rippleLow = rawId("ripple_low")
    private val rippleMid = rawId("ripple_mid")
    private val rippleHigh = rawId("ripple_high")
    private val crestSwell = rawId("crest_swell")
    private val brandChime = rawId("brand_chime")
    private val celebration = rawId("celebration")
    private val readyChime = rawId("ready_chime")
    private val pickupCue = rawId("pickup_cue")

    /**
     * Cadence beats (0..N-1) that fire an audible drop. Sparse on
     * purpose — the visual cadence has 8 beats; sounding every beat
     * gets cluttered, three accents (first / middle / late) carry the
     * crescendo cleanly.
     */
    private val rippleCueByBeat: Map<Int, Int> = buildMap {
        if (rippleLow != 0) put(0, rippleLow)
        if (rippleMid != 0) put(3, rippleMid)
        if (rippleHigh != 0) put(6, rippleHigh)
    }

    /** Per-cue mix levels. Tuned together; change here, not at call sites. */
    private object Vol {
        const val PAD = 0.18f
        const val RIPPLE_LOW = 0.35f
        const val RIPPLE_MID = 0.45f
        const val RIPPLE_HIGH = 0.55f
        const val CREST = 0.7f
        const val BRAND = 0.3f
        const val CELEBRATION = 0.65f
        const val READY = 0.4f
        const val PICKUP = 0.3f
    }

    /** Load every short cue into SoundPool. Call once during onCreate. */
    fun prepare() {
        listOf(
            rippleLow, rippleMid, rippleHigh,
            crestSwell, brandChime, celebration, readyChime, pickupCue
        ).filter { it != 0 }.forEach { sound.loadSound(it) }
    }

    /** Start the ambient pad as a continuous looping bed. */
    fun startPad() {
        if (pad == 0) return
        sound.playLongSound(pad, volume = Vol.PAD, looping = true)
    }

    /** Stop the pad. Idempotent. */
    fun stopPad() {
        sound.stopLongSound()
    }

    /**
     * Per-beat hook from [com.waterfountainmachine.app.ui.views.RipplePondView]
     * cadence. [beat] is 0-indexed (0 = first ripple). Plays a drop only
     * on beats mapped in [rippleCueByBeat]; other beats are silent.
     */
    fun onRippleEmit(beat: Int) {
        val res = rippleCueByBeat[beat] ?: return
        val vol = when (res) {
            rippleLow -> Vol.RIPPLE_LOW
            rippleMid -> Vol.RIPPLE_MID
            rippleHigh -> Vol.RIPPLE_HIGH
            else -> Vol.RIPPLE_LOW
        }
        sound.playSound(res, volume = vol, enableThrottle = false)
    }

    fun onCrest() = play(crestSwell, Vol.CREST)
    fun onBrandReveal() = play(brandChime, Vol.BRAND)
    fun onDrop() = play(celebration, Vol.CELEBRATION)
    fun onReady() = play(readyChime, Vol.READY)
    fun onPickupHint() = play(pickupCue, Vol.PICKUP)

    /** Release all underlying audio resources. Call in onDestroy. */
    fun release() {
        sound.release()
    }

    private fun play(@RawRes res: Int, volume: Float) {
        if (res == 0) return
        sound.playSound(res, volume = volume, enableThrottle = false)
    }

    private fun rawId(name: String): Int {
        val id = context.resources.getIdentifier(name, "raw", context.packageName)
        if (id == 0) AppLog.w(TAG, "Audio cue '$name' missing — silently skipping at runtime")
        return id
    }

    private companion object {
        const val TAG = "DispenseAudio"
    }
}

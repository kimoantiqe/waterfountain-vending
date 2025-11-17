package com.waterfountainmachine.app.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import androidx.annotation.RawRes

/**
 * Manages sound effects for the vending machine app.
 * Uses SoundPool for low-latency sound playback with anti-spam protection.
 */
class SoundManager(private val context: Context) {
    private var soundPool: SoundPool? = null
    private val soundMap = mutableMapOf<Int, Int>() // Resource ID to Sound ID mapping
    private val lastPlayTime = mutableMapOf<Int, Long>() // Track last play time for each sound
    private var mediaPlayer: MediaPlayer? = null
    
    companion object {
        private const val TAG = "SoundManager"
        private const val MAX_STREAMS = 8 // Increased for multiple simultaneous sounds
        private const val MIN_PLAY_INTERVAL_MS = 50 // Minimum time between same sound plays (smooth keyboard feel)
    }
    
    init {
        initializeSoundPool()
    }
    
    private fun initializeSoundPool() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        
        soundPool = SoundPool.Builder()
            .setMaxStreams(MAX_STREAMS)
            .setAudioAttributes(audioAttributes)
            .build()
        
        AppLog.i(TAG, "SoundPool initialized with max streams: $MAX_STREAMS")
    }
    
    /**
     * Load a sound effect from raw resources.
     * @param resourceId The R.raw resource ID
     * @return The sound ID, or null if loading failed
     */
    fun loadSound(@RawRes resourceId: Int): Int? {
        return try {
            val soundId = soundPool?.load(context, resourceId, 1)
            if (soundId != null && soundId > 0) {
                soundMap[resourceId] = soundId
                AppLog.d(TAG, "Sound loaded: resourceId=$resourceId, soundId=$soundId")
                soundId
            } else {
                AppLog.e(TAG, "Failed to load sound: resourceId=$resourceId")
                null
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Error loading sound: resourceId=$resourceId", e)
            null
        }
    }
    
    /**
     * Play a short sound effect with anti-spam protection.
     * Perfect for UI clicks and taps.
     * @param resourceId The R.raw resource ID
     * @param volume Volume level (0.0 to 1.0)
     * @param enableThrottle Enable anti-spam protection (default: true)
     */
    fun playSound(@RawRes resourceId: Int, volume: Float = 1.0f, enableThrottle: Boolean = true) {
        // Check if we should throttle this sound
        if (enableThrottle) {
            val now = System.currentTimeMillis()
            val lastPlay = lastPlayTime[resourceId] ?: 0L
            
            if (now - lastPlay < MIN_PLAY_INTERVAL_MS) {
                AppLog.d(TAG, "Throttled sound play: resourceId=$resourceId (too soon)")
                return
            }
            
            lastPlayTime[resourceId] = now
        }
        
        val soundId = soundMap[resourceId]
        if (soundId != null) {
            soundPool?.play(soundId, volume, volume, 1, 0, 1.0f)
            AppLog.d(TAG, "Playing sound: resourceId=$resourceId, soundId=$soundId, volume=$volume")
        } else {
            AppLog.w(TAG, "Sound not loaded: resourceId=$resourceId")
        }
    }
    
    /**
     * Play a longer sound effect using MediaPlayer.
     * Perfect for animations and sequences.
     * @param resourceId The R.raw resource ID
     * @param volume Volume level (0.0 to 1.0)
     * @param looping Whether to loop the sound
     */
    fun playLongSound(@RawRes resourceId: Int, volume: Float = 1.0f, looping: Boolean = false) {
        try {
            // Release previous MediaPlayer if exists
            stopLongSound()
            
            // Create and prepare MediaPlayer manually for better control
            mediaPlayer = MediaPlayer().apply {
                // Set audio attributes for high quality playback
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                
                // Set data source
                val afd = context.resources.openRawResourceFd(resourceId)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                
                // Prepare asynchronously to prevent any blocking
                prepareAsync()
                
                // Configure volume
                setVolume(volume, volume)
                
                // Set up completion listener for manual looping (more reliable than isLooping)
                setOnCompletionListener { mp ->
                    if (looping && mp != null) {
                        try {
                            // Immediately seek to start and replay for seamless looping
                            mp.seekTo(0)
                            mp.start()
                            AppLog.d(TAG, "Looping sound: resourceId=$resourceId")
                        } catch (e: Exception) {
                            AppLog.e(TAG, "Error looping sound: resourceId=$resourceId", e)
                        }
                    } else {
                        release()
                        mediaPlayer = null
                    }
                }
                
                // Start playback when prepared
                setOnPreparedListener { mp ->
                    mp.start()
                    AppLog.d(TAG, "Started long sound: resourceId=$resourceId, volume=$volume, looping=$looping, audioSessionId=${mp.audioSessionId}")
                }
                
                // Handle errors
                setOnErrorListener { mp, what, extra ->
                    AppLog.e(TAG, "MediaPlayer error: what=$what, extra=$extra, resourceId=$resourceId")
                    false // Return false to trigger OnCompletionListener
                }
            }
            
            AppLog.d(TAG, "Preparing long sound: resourceId=$resourceId, volume=$volume, looping=$looping")
        } catch (e: Exception) {
            AppLog.e(TAG, "Error playing long sound: resourceId=$resourceId", e)
        }
    }
    
    /**
     * Stop the currently playing long sound (MediaPlayer).
     */
    fun stopLongSound() {
        mediaPlayer?.apply {
            try {
                // Clear completion listener to prevent any loop attempts during shutdown
                setOnCompletionListener(null)
                
                if (isPlaying) {
                    stop()
                }
                reset()
                release()
                AppLog.d(TAG, "Long sound stopped and released")
            } catch (e: Exception) {
                AppLog.e(TAG, "Error stopping long sound", e)
            }
        }
        mediaPlayer = null
    }
    
    /**
     * Set the playback speed/rate for the currently playing long sound.
     * @param rate Playback rate (0.5 = half speed, 1.0 = normal, 2.0 = double speed)
     *             Range: 0.5 to 2.0
     */
    fun setPlaybackRate(rate: Float) {
        try {
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    val clampedRate = rate.coerceIn(0.5f, 2.0f)
                    player.playbackParams = player.playbackParams.setSpeed(clampedRate)
                    AppLog.d(TAG, "Playback rate set to: $clampedRate")
                } else {
                    AppLog.w(TAG, "Cannot set playback rate: MediaPlayer not playing")
                }
            } ?: AppLog.w(TAG, "Cannot set playback rate: MediaPlayer is null")
        } catch (e: Exception) {
            AppLog.e(TAG, "Error setting playback rate: $rate", e)
        }
    }
    
    /**
     * Check if a long sound is currently playing
     */
    fun isLongSoundPlaying(): Boolean {
        return mediaPlayer?.isPlaying ?: false
    }
    
    /**
     * Release all resources.
     * Call this when the sound manager is no longer needed.
     */
    fun release() {
        stopLongSound()
        soundPool?.release()
        soundPool = null
        soundMap.clear()
        lastPlayTime.clear()
        AppLog.i(TAG, "SoundPool released")
    }
}

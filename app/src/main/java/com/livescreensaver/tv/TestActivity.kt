package com.livescreensaver.tv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager as AndroidPreferenceManager

/**
 * TestActivity - Launch screensaver for testing without waiting for system idle
 */
class TestActivity : AppCompatActivity(), SurfaceHolder.Callback, PlayerManager.PlayerEventListener {

    companion object {
        private const val TAG = "TestActivity"
        private const val DEFAULT_VIDEO_URL = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
        
        // Helper to create PreferenceCache from SharedPreferences
        private fun createPreferenceCache(prefs: android.content.SharedPreferences): PreferenceCache {
            return PreferenceCache(
                audioEnabled = prefs.getBoolean("audio_enabled", true),
                audioVolume = prefs.getString("audio_volume", "100")?.toIntOrNull() ?: 100,
                videoScalingMode = prefs.getString("video_scaling_mode", "fit") ?: "fit",
                speedEnabled = prefs.getBoolean("speed_enabled", false),
                playbackSpeed = prefs.getString("playback_speed", "1.0")?.toFloatOrNull() ?: 1.0f,
                introEnabled = prefs.getBoolean("intro_enabled", false),
                introDuration = prefs.getString("intro_duration", "0")?.toLongOrNull() ?: 0L,
                skipBeginningEnabled = prefs.getBoolean("skip_beginning_enabled", false),
                skipBeginningDuration = prefs.getString("skip_beginning_duration", "0")?.toLongOrNull() ?: 0L,
                randomSeekEnabled = prefs.getBoolean("random_seek_enabled", false),
                scheduleEnabled = prefs.getBoolean("schedule_enabled", false),
                scheduleRandomMode = prefs.getBoolean("schedule_random_mode", false),
                clockEnabled = prefs.getBoolean("clock_enabled", false),
                clockPosition = prefs.getString("clock_position", "top_right") ?: "top_right",
                clockSize = prefs.getString("clock_size", "medium") ?: "medium",
                timeFormat = prefs.getString("time_format", "12") ?: "12",
                pixelShiftInterval = prefs.getString("pixel_shift_interval", "0")?.toLongOrNull() ?: 0L,
                statsEnabled = prefs.getBoolean("stats_enabled", false)
            )
        }
    }

    private lateinit var playerManager: PlayerManager
    private lateinit var uiOverlayManager: UIOverlayManager
    private lateinit var containerLayout: FrameLayout
    private lateinit var surfaceView: SurfaceView

    private val handler = Handler(Looper.getMainLooper())
    private var surfaceReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setupFullscreen()

        // Create container layout
        containerLayout = FrameLayout(this)
        setContentView(containerLayout)

        // Create surface view
        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)

        containerLayout.addView(surfaceView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Initialize managers
        playerManager = PlayerManager(this, this)
        uiOverlayManager = UIOverlayManager(this, containerLayout, handler)

        // Setup UI overlays using PreferenceCache
        val prefs = AndroidPreferenceManager.getDefaultSharedPreferences(this)
        val cache = createPreferenceCache(prefs)

        if (cache.clockEnabled) {
            uiOverlayManager.setupClock(cache)
        }
        if (cache.statsEnabled) {
            uiOverlayManager.setupStats(cache)
        }

        Log.d(TAG, "Test activity created")
    }

    private fun setupFullscreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        supportActionBar?.hide()
        hideSystemUI()
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }

    // SurfaceHolder.Callback
    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d(TAG, "Surface created")
        surfaceReady = true
        startPlayback(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(TAG, "Surface destroyed")
        surfaceReady = false
        playerManager.release()
    }

    private fun startPlayback(surface: android.view.Surface) {
        try {
            playerManager.initialize(surface)

            // Get URL from preferences
            val prefs = AndroidPreferenceManager.getDefaultSharedPreferences(this)
            val url = prefs.getString("video_url", DEFAULT_VIDEO_URL) ?: DEFAULT_VIDEO_URL

            Log.d(TAG, "Starting playback: $url")
            playerManager.playStream(url)

            // Set volume
            val audioEnabled = prefs.getBoolean("audio_enabled", true)
            val audioVolume = prefs.getString("audio_volume", "100")?.toIntOrNull() ?: 100

            if (audioEnabled) {
                playerManager.setVolume(audioVolume / 100f)
            } else {
                playerManager.setVolume(0f)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error starting playback", e)
            finish()
        }
    }

    // PlayerEventListener
    override fun onPlaybackStateChanged(state: Int) {
        Log.d(TAG, "Playback state: $state")
    }

    override fun onPlayerError(error: Exception) {
        Log.e(TAG, "Player error", error)
        finish()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        if (surfaceReady) {
            playerManager.resume()
        }
    }

    override fun onPause() {
        super.onPause()
        playerManager.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        uiOverlayManager.cleanup()
        playerManager.release()
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Test activity destroyed")
    }

    // Exit on any key press
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Log.d(TAG, "Key pressed, exiting")
        finish()
        return true
    }

    // Exit on any touch
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event?.action == MotionEvent.ACTION_DOWN) {
            Log.d(TAG, "Touch detected, exiting")
            finish()
            return true
        }
        return super.onTouchEvent(event)
    }
}

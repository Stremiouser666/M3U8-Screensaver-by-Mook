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
import androidx.preference.PreferenceManager

/**
 * TestActivity - Launch screensaver for testing without waiting for system idle
 */
class TestActivity : AppCompatActivity(), SurfaceHolder.Callback, PlayerManager.PlayerEventListener {

    companion object {
        private const val TAG = "TestActivity"
        private const val DEFAULT_VIDEO_URL = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
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
        
        // Setup UI overlays
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val cache = com.livescreensaver.tv.PreferenceCache(
            clockEnabled = prefs.getBoolean("clock_enabled", true),
            clockPosition = prefs.getString("clock_position", "top_right") ?: "top_right",
            clockSize = prefs.getString("clock_size", "64")?.toIntOrNull() ?: 64,
            timeFormat = prefs.getString("time_format", "12h") ?: "12h",
            pixelShiftInterval = prefs.getString("pixel_shift_interval", "300000")?.toLongOrNull() ?: 300000L,
            statsEnabled = prefs.getBoolean("stats_enabled", false),
            statsPosition = prefs.getString("stats_position", "top_left") ?: "top_left",
            statsInterval = prefs.getString("stats_interval", "1000")?.toLongOrNull() ?: 1000L,
            audioEnabled = prefs.getBoolean("audio_enabled", true),
            audioVolume = prefs.getString("audio_volume", "100")?.toIntOrNull() ?: 100
        )
        
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
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
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

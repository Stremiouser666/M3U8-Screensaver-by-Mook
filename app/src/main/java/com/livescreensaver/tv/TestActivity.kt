package com.livescreensaver.tv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity

/**
 * TestActivity - Allows testing screensaver functionality without waiting for system idle
 * 
 * This activity mimics the behavior of LiveScreensaverService but runs as a normal activity
 * that can be launched on demand. It uses the same PlayerManager and rendering logic.
 */
class TestActivity : AppCompatActivity(), PlayerManager.PlayerEventListener {

    private lateinit var playerManager: PlayerManager
    private lateinit var uiOverlayManager: UIOverlayManager
    private lateinit var containerLayout: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Make fullscreen and keep screen on
        setupFullscreen()
        
        // Create container layout
        containerLayout = FrameLayout(this)
        setContentView(containerLayout)
        
        // Create surface view for video playback
        surfaceView = SurfaceView(this).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    initializePlayback(holder.surface)
                }
                
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    playerManager.release()
                }
            })
        }
        
        containerLayout.addView(surfaceView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        
        // Initialize managers
        playerManager = PlayerManager(this, this)
        uiOverlayManager = UIOverlayManager(this, containerLayout, handler)
    }

    private fun initializePlayback(surface: android.view.Surface) {
        playerManager.initialize(surface)
        playerManager.startPlayback()
        uiOverlayManager.start(playerManager.getExoPlayer())
    }

    private fun setupFullscreen() {
        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Hide action bar
        supportActionBar?.hide()
        
        // Hide system UI (navigation and status bars)
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

    override fun onResume() {
        super.onResume()
        hideSystemUI()
    }

    override fun onPause() {
        super.onPause()
        playerManager.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        uiOverlayManager.stop()
        playerManager.release()
        handler.removeCallbacksAndMessages(null)
    }

    // PlayerEventListener implementation
    override fun onPlaybackStateChanged(state: Int) {
        // Handle playback state changes if needed
    }

    override fun onPlayerError(error: Exception) {
        // Handle errors if needed
        finish()
    }

    // Exit on any key press
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        finish()
        return true
    }

    // Exit on any touch
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event?.action == MotionEvent.ACTION_DOWN) {
            finish()
            return true
        }
        return super.onTouchEvent(event)
    }
}

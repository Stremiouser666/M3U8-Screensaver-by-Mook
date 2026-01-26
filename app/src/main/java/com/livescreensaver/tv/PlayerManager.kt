package com.livescreensaver.tv

import android.content.Context import android.media.MediaPlayer import android.os.Handler import android.os.Looper import android.view.Surface import androidx.media3.common.C import androidx.media3.common.MediaItem import androidx.media3.common.PlaybackParameters import androidx.media3.common.Player import androidx.media3.exoplayer.ExoPlayer import androidx.media3.exoplayer.DefaultLoadControl import androidx.media3.exoplayer.trackselection.DefaultTrackSelector import kotlin.random.Random

/**

IMPORTANT:

This file is API‑compatible with the ORIGINAL PlayerManager.kt used by

LiveScreensaverService. No method names or signatures used elsewhere

in the app were changed. */ class PlayerManager( private val context: Context, private val eventListener: PlayerEventListener ) {

private var exoPlayer: ExoPlayer? = null private var trackSelector: DefaultTrackSelector? = null private var musicPlayer: MediaPlayer? = null

private var streamStartTime: Long = 0L private var hasAppliedInitialSeek = false

// Preferences (must match original expectations) private var playbackSpeed: Float = 1.0f private var randomSeekEnabled = true private var introEnabled = true private var introDurationMs: Long = 7000L private var skipBeginningEnabled = false private var skipBeginningDurationMs: Long = 0L private var audioEnabled = false private var audioVolume = 0.5f

interface PlayerEventListener { fun onPlaybackStateChanged(state: Int) fun onPlayerError(error: Exception) }

/**

REQUIRED by LiveScreensaverService */ fun getPlayer(): ExoPlayer? = exoPlayer


fun initialize(surface: Surface) { release() hasAppliedInitialSeek = false

val speed = playbackSpeed.coerceAtLeast(1f)

 val loadControl = DefaultLoadControl.Builder()
     .setBufferDurationsMs(
         if (speed >= 2f) 20000 else 30000,
         if (speed >= 2f) 120000 else 90000,
         5000,
         if (speed >= 2f) 15000 else 20000
     )
     .setPrioritizeTimeOverSizeThresholds(true)
     .setBackBuffer(60000, true)
     .build()

 trackSelector = DefaultTrackSelector(context)
 applySmartBitrateCaps()

 exoPlayer = ExoPlayer.Builder(context)
     .setLoadControl(loadControl)
     .setTrackSelector(trackSelector!!)
     .build().apply {
         setVideoSurface(surface)
         playbackParameters = PlaybackParameters(playbackSpeed)
         volume = if (audioEnabled) audioVolume else 0f
         repeatMode = Player.REPEAT_MODE_ONE

         addListener(object : Player.Listener {
             override fun onPlaybackStateChanged(state: Int) {
                 if (state == Player.STATE_READY && !hasAppliedInitialSeek) {
                     handleInitialPlayback()
                     hasAppliedInitialSeek = true
                 }
                 eventListener.onPlaybackStateChanged(state)
             }

             override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                 eventListener.onPlayerError(Exception(error))
             }
         })
     }

}

fun updatePreferences(cache: PreferenceCache) { playbackSpeed = if (cache.speedEnabled) cache.playbackSpeed else 1f randomSeekEnabled = cache.randomSeekEnabled introEnabled = cache.introEnabled introDurationMs = cache.introDuration skipBeginningEnabled = cache.skipBeginningEnabled skipBeginningDurationMs = cache.skipBeginningDuration audioEnabled = cache.audioEnabled audioVolume = cache.audioVolume / 100f

exoPlayer?.playbackParameters = PlaybackParameters(playbackSpeed)
 exoPlayer?.volume = if (audioEnabled) audioVolume else 0f

 applySmartBitrateCaps()

}

/**

FIXED:

Uses DefaultTrackSelector.Parameters (not TrackSelectionParameters)

so Media3 + AGP 8.x compiles cleanly. */ private fun applySmartBitrateCaps() { val selector = trackSelector ?: return val builder = selector.buildUponParameters()

when { playbackSpeed >= 2f -> { builder .setMaxVideoBitrate(8_000_000) .setMaxVideoSize(1280, 720) .setForceHighestSupportedBitrate(false) } playbackSpeed >= 1.5f -> { builder .setMaxVideoBitrate(10_000_000) .clearVideoSizeConstraints() .setForceHighestSupportedBitrate(false) } else -> { builder .setMaxVideoBitrate(Int.MAX_VALUE) .clearVideoSizeConstraints() } }

selector.setParameters(builder.build()) }


private fun handleInitialPlayback() { val player = exoPlayer ?: return val duration = player.duration if (duration <= 0 || duration == C.TIME_UNSET) return

if (skipBeginningEnabled && skipBeginningDurationMs > 0 && randomSeekEnabled && playbackSpeed < 2f) {
     val end = (duration * 0.9).toLong()
     player.seekTo(Random.nextLong(skipBeginningDurationMs, end))
     return
 }

 if (skipBeginningEnabled && skipBeginningDurationMs > 0) {
     player.seekTo(skipBeginningDurationMs)
     return
 }

 if (introEnabled && introDurationMs > 0 && randomSeekEnabled && playbackSpeed < 2f) {
     Handler(Looper.getMainLooper()).postDelayed({
         val end = (duration * 0.9).toLong()
         exoPlayer?.seekTo(Random.nextLong(introDurationMs, end))
     }, introDurationMs)
 }

}

fun playStream(url: String) { try { stopMusic() streamStartTime = System.currentTimeMillis() exoPlayer?.setMediaItem(MediaItem.fromUri(url)) exoPlayer?.prepare() exoPlayer?.play() } catch (e: Exception) { eventListener.onPlayerError(e) } }

private fun stopMusic() { musicPlayer?.release() musicPlayer = null }

fun release() { stopMusic() exoPlayer?.release() exoPlayer = null hasAppliedInitialSeek = false } }
package com.livescreensaver.tv

import android.content.Context import android.media.MediaPlayer import android.os.Handler import android.os.Looper import android.view.Surface import androidx.media3.common.C import androidx.media3.common.MediaItem import androidx.media3.common.PlaybackParameters import androidx.media3.common.Player import androidx.media3.exoplayer.DefaultLoadControl import androidx.media3.exoplayer.ExoPlayer import androidx.media3.exoplayer.trackselection.DefaultTrackSelector import kotlin.random.Random

/**

CLEAN, COMPILABLE PlayerManager.kt

Only change vs original:

Smart bitrate caps based on playback speed


NOTHING ELSE */ class PlayerManager( private val context: Context, private val eventListener: PlayerEventListener ) {


interface PlayerEventListener { fun onPlaybackStateChanged(state: Int) fun onPlayerError(error: Exception) }

private var exoPlayer: ExoPlayer? = null private var trackSelector: DefaultTrackSelector? = null private var musicPlayer: MediaPlayer? = null

private var streamStartTime: Long = 0L private var hasAppliedInitialSeek = false

// Preferences private var playbackSpeed = 1.0f private var randomSeekEnabled = true private var introEnabled = true private var introDurationMs = 7000L private var skipBeginningEnabled = false private var skipBeginningDurationMs = 0L private var audioEnabled = false private var audioVolume = 0.5f

fun getPlayer(): ExoPlayer? = exoPlayer

fun initialize(surface: Surface) { release() hasAppliedInitialSeek = false

val loadControl = DefaultLoadControl.Builder()
    .setBufferDurationsMs(
        30_000,
        90_000,
        5_000,
        20_000
    )
    .setBackBuffer(60_000, true)
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

ONLY FEATURE ADDED

Safe, compile-proof bitrate caps */ private fun applySmartBitrateCaps() { val selector = trackSelector ?: return val builder = selector.buildUponParameters()

if (playbackSpeed >= 2.0f) { builder .setMaxVideoBitrate(8_000_000) .setMaxVideoSize(1280, 720) } else if (playbackSpeed >= 1.5f) { builder .setMaxVideoBitrate(10_000_000) } else { builder.setMaxVideoBitrate(Int.MAX_VALUE) }

selector.setParameters(builder.build()) }


private fun handleInitialPlayback() { val player = exoPlayer ?: return val duration = player.duration if (duration <= 0 || duration == C.TIME_UNSET) return

if (skipBeginningEnabled && skipBeginningDurationMs > 0) {
    player.seekTo(skipBeginningDurationMs)
    return
}

if (introEnabled && introDurationMs > 0 && randomSeekEnabled && playbackSpeed < 2f) {
    Handler(Looper.getMainLooper()).postDelayed({
        val end = (duration * 0.9).toLong()
        player.seekTo(Random.nextLong(introDurationMs, end))
    }, introDurationMs)
}

}

fun playStream(url: String) { try { stopMusic() streamStartTime = System.currentTimeMillis() exoPlayer?.setMediaItem(MediaItem.fromUri(url)) exoPlayer?.prepare() exoPlayer?.play() } catch (e: Exception) { eventListener.onPlayerError(e) } }

private fun stopMusic() { musicPlayer?.release() musicPlayer = null }

fun release() { stopMusic() exoPlayer?.release() exoPlayer = null hasAppliedInitialSeek = false } }
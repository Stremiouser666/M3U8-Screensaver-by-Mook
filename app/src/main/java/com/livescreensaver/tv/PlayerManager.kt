package com.livescreensaver.tv

import android.content.Context
import android.media.MediaPlayer
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.datasource.DefaultHttpDataSource
import kotlin.random.Random

class PlayerManager(
    private val context: Context,
    private val eventListener: PlayerEventListener
) {
    private var exoPlayer: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var musicPlayer: MediaPlayer? = null
    private var streamStartTime: Long = 0

    // Playback preferences
    private var playbackSpeed: Float = 1.0f
    private var randomSeekEnabled: Boolean = true
    private var introEnabled: Boolean = true
    private var introDurationMs: Long = 7000L
    private var skipBeginningEnabled: Boolean = false
    private var skipBeginningDurationMs: Long = 0
    private var audioEnabled: Boolean = false
    private var audioVolume: Float = 0.5f

    private var hasAppliedInitialSeek = false

    interface PlayerEventListener {
        fun onPlaybackStateChanged(state: Int)
        fun onPlayerError(error: Exception)
    }

    fun initialize(surface: Surface) {
        release()
        hasAppliedInitialSeek = false

        // Buffer tuning
        val speedMultiplier = playbackSpeed.coerceAtLeast(1.0f)
        val minBuffer = when {
            playbackSpeed >= 2.0f -> 20000
            playbackSpeed >= 1.5f -> 25000
            else -> (30000 * speedMultiplier).toInt()
        }
        val maxBuffer = (120000 * speedMultiplier).toInt()
        val playbackBuffer = 5000
        val rebufferThreshold = minOf((12000 * speedMultiplier).toInt(), 20000)

        FileLogger.log(
            "🔧 Buffer config for ${playbackSpeed}x: min=${minBuffer}ms max=${maxBuffer}ms rebuffer=${rebufferThreshold}ms",
            "PlayerManager"
        )

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBuffer, maxBuffer, playbackBuffer, rebufferThreshold)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setTargetBufferBytes(-1)
            .setBackBuffer(60000, true)
            .build()

        trackSelector = DefaultTrackSelector(context).apply {
            setParameters(buildSmartTrackParameters())
        }

        exoPlayer = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector!!)
            .build()
            .apply {
                setVideoSurface(surface)
                playbackParameters = PlaybackParameters(playbackSpeed)
                volume = if (audioEnabled) audioVolume else 0f
                repeatMode = Player.REPEAT_MODE_ONE

                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY && streamStartTime > 0) {
                            val latency = System.currentTimeMillis() - streamStartTime
                            FileLogger.log("⚡ PLAYBACK STARTED in ${latency}ms", "PlayerManager")
                            streamStartTime = 0

                            if (!hasAppliedInitialSeek) {
                                handleInitialPlayback()
                                hasAppliedInitialSeek = true
                            }
                        }
                        eventListener.onPlaybackStateChanged(playbackState)
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        eventListener.onPlayerError(Exception(error))
                    }
                })
            }
    }

    fun updatePreferences(cache: PreferenceCache) {
        playbackSpeed = if (cache.speedEnabled) cache.playbackSpeed else 1.0f
        randomSeekEnabled = cache.randomSeekEnabled
        introEnabled = cache.introEnabled
        introDurationMs = cache.introDuration
        skipBeginningEnabled = cache.skipBeginningEnabled
        skipBeginningDurationMs = cache.skipBeginningDuration
        audioEnabled = cache.audioEnabled
        audioVolume = cache.audioVolume / 100f

        exoPlayer?.let { player ->
            player.playbackParameters = PlaybackParameters(playbackSpeed)
            player.volume = if (audioEnabled) audioVolume else 0f
        }

        // Apply smart bitrate caps dynamically
        trackSelector?.setParameters(buildSmartTrackParameters())

        FileLogger.log(
            "⚙️ Prefs updated - Speed=$playbackSpeed Audio=${if (audioEnabled) "ON" else "OFF"}",
            "PlayerManager"
        )
    }

    private fun buildSmartTrackParameters(): DefaultTrackSelector.Parameters {
        val builder = trackSelector?.buildUponParameters() ?: DefaultTrackSelector.ParametersBuilder(context)

        when {
            playbackSpeed >= 2.0f -> {
                // 2x: prefer stability
                builder
                    .setMaxVideoBitrate(8_000_000)      // 1080p-safe
                    .setMaxVideoSize(1280, 720)         // force 720p at 2x
                    .setForceHighestSupportedBitrate(false)
            }
            playbackSpeed >= 1.5f -> {
                // 1.5x: near-transparent cap for 1080p
                builder
                    .setMaxVideoBitrate(10_000_000)     // visually lossless at 1.5x
                    .setForceHighestSupportedBitrate(false)
            }
            else -> {
                // 1x: no caps
                builder
                    .setMaxVideoBitrate(Int.MAX_VALUE)
                    .clearVideoSizeConstraints()
            }
        }

        return builder.build()
    }

    private fun handleInitialPlayback() {
        val player = exoPlayer ?: return
        val duration = player.duration

        if (duration <= 0 || duration == C.TIME_UNSET) return

        if (skipBeginningEnabled && skipBeginningDurationMs > 0 && randomSeekEnabled && playbackSpeed < 2.0f) {
            val safeEndPosition = (duration * 0.9).toLong()
            val seekPosition = Random.nextLong(skipBeginningDurationMs, safeEndPosition)
            player.seekTo(seekPosition)
            return
        }

        if (skipBeginningEnabled && skipBeginningDurationMs > 0) {
            player.seekTo(skipBeginningDurationMs)
            return
        }

        if (introEnabled && introDurationMs > 0 && randomSeekEnabled && playbackSpeed < 2.0f) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val safeEndPosition = (duration * 0.9).toLong()
                val seekPosition = Random.nextLong(introDurationMs, safeEndPosition)
                exoPlayer?.seekTo(seekPosition)
            }, introDurationMs)
        }
    }

    fun playStream(url: String) {
        val player = exoPlayer ?: return
        try {
            stopMusic()
            streamStartTime = System.currentTimeMillis()
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
            player.play()
        } catch (e: Exception) {
            eventListener.onPlayerError(e)
        }
    }

    private fun stopMusic() {
        musicPlayer?.release()
        musicPlayer = null
    }

    fun release() {
        stopMusic()
        exoPlayer?.release()
        exoPlayer = null
        trackSelector = null
        hasAppliedInitialSeek = false
    }
}

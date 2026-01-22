package com.livescreensaver.tv

import android.content.Context
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.datasource.DefaultHttpDataSource

class PlayerManager(
    private val context: Context,
    private val eventListener: PlayerEventListener
) {
    private var exoPlayer: ExoPlayer? = null

    interface PlayerEventListener {
        fun onPlaybackStateChanged(state: Int)
        fun onPlayerError(error: Exception)
    }

    fun initialize(surface: Surface) {
        release()

        // AGGRESSIVE buffering for merged streams - prioritize START SPEED
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                2000,   // min buffer (2s) - very low
                15000,  // max buffer (15s) - reasonable
                500,    // START PLAYBACK at 500ms! (was 1500ms)
                1000    // rebuffer threshold (1s)
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setTargetBufferBytes(-1)  // No size limit, focus on time
            .build()

        exoPlayer = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
            .apply {
                setVideoSurface(surface)
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        eventListener.onPlaybackStateChanged(playbackState)
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        eventListener.onPlayerError(Exception(error))
                    }
                })
            }
    }

    /**
     * Play a stream URL
     * Supports:
     * - Regular URLs (HLS, DASH, MP4)
     * - Dual URLs in format: "video_url|||audio_url" for merging
     */
    fun playStream(url: String) {
        val player = exoPlayer ?: return

        try {
            // Check if this is a dual-stream URL (video|||audio)
            if (url.contains("|||")) {
                val parts = url.split("|||")
                if (parts.size == 2) {
                    val videoUrl = parts[0]
                    val audioUrl = parts[1]

                    FileLogger.log("🎬 Merging video + audio streams", "PlayerManager")
                    playMergedStream(videoUrl, audioUrl)
                    return
                }
            }

            // Single URL - play normally
            FileLogger.log("🎬 Loading single stream: ${url.take(100)}...", "PlayerManager")
            val mediaItem = MediaItem.fromUri(url)
            player.setMediaItem(mediaItem)
            player.prepare()
            player.play()

        } catch (e: Exception) {
            FileLogger.log("❌ Error loading stream: ${e.message}", "PlayerManager")
            eventListener.onPlayerError(e)
        }
    }

    /**
     * Merge video and audio using MergingMediaSource
     * With AGGRESSIVE buffering settings, should start in 2-5 seconds
     */
    private fun playMergedStream(videoUrl: String, audioUrl: String) {
        val player = exoPlayer ?: return

        try {
            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(5000)
                .setReadTimeoutMs(5000)

            val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(videoUrl))

            val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(audioUrl))

            val merged = MergingMediaSource(videoSource, audioSource)

            FileLogger.log("✅ Merged source created (buffering ~2-5s)", "PlayerManager")

            player.setMediaSource(merged)
            player.prepare()
            player.play()

        } catch (e: Exception) {
            FileLogger.log("❌ Error merging: ${e.message}", "PlayerManager")
            eventListener.onPlayerError(e)
        }
    }

    fun pause() {
        exoPlayer?.pause()
    }

    fun resume() {
        exoPlayer?.play()
    }

    fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
    }

    fun getCurrentPosition(): Long {
        return exoPlayer?.currentPosition ?: 0
    }

    fun getDuration(): Long {
        return exoPlayer?.duration ?: 0
    }

    fun release() {
        exoPlayer?.release()
        exoPlayer = null
    }

    fun setVolume(volume: Float) {
        exoPlayer?.volume = volume
    }

    fun getPlayer(): ExoPlayer? = exoPlayer
}
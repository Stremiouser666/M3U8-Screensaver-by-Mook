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
    private var videoPlayer: ExoPlayer? = null
    private var audioPlayer: ExoPlayer? = null
    private var isSyncing = false

    interface PlayerEventListener {
        fun onPlaybackStateChanged(state: Int)
        fun onPlayerError(error: Exception)
    }

    fun initialize(surface: Surface) {
        release()

        // Configure load control for instant startup
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                5000,   // min buffer before playback starts (5s)
                30000,  // max buffer (30s)
                500,    // buffer for playback to start (0.5s) - INSTANT START
                2000    // buffer for playback to resume (2s)
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // Video player (with surface)
        videoPlayer = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
            .apply {
                setVideoSurface(surface)
                volume = 0f  // Video player is muted
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        eventListener.onPlaybackStateChanged(playbackState)
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        eventListener.onPlayerError(Exception(error))
                    }
                })
            }

        // Audio player (no surface, audio only)
        audioPlayer = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
            .apply {
                volume = 1f  // Ensure audio is enabled
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        FileLogger.log("🔊 Audio state: $playbackState", "PlayerManager")
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        FileLogger.log("❌ Audio error: ${error.message}", "PlayerManager")
                    }
                })
            }
    }

    /**
     * Play a stream URL
     * Supports:
     * - Regular URLs (HLS, DASH, MP4)
     * - Dual URLs in format: "video_url|||audio_url" for parallel playback
     */
    fun playStream(url: String) {
        try {
            // Check if this is a dual-stream URL (video|||audio)
            if (url.contains("|||")) {
                val parts = url.split("|||")
                if (parts.size == 2) {
                    val videoUrl = parts[0]
                    val audioUrl = parts[1]

                    FileLogger.log("🎬 Loading dual-stream PARALLEL (video + audio)", "PlayerManager")
                    FileLogger.log("📹 Video: ${videoUrl.take(100)}...", "PlayerManager")
                    FileLogger.log("🔊 Audio: ${audioUrl.take(100)}...", "PlayerManager")

                    playDualStreamParallel(videoUrl, audioUrl)
                    return
                }
            }

            // Single URL - play on video player only
            FileLogger.log("🎬 Loading single stream: ${url.take(100)}...", "PlayerManager")
            val mediaItem = MediaItem.fromUri(url)
            videoPlayer?.apply {
                volume = 1f  // Enable audio for single stream
                setMediaItem(mediaItem)
                prepare()
                play()
            }

        } catch (e: Exception) {
            FileLogger.log("❌ Error loading stream: ${e.message}", "PlayerManager")
            eventListener.onPlayerError(e)
        }
    }

    /**
     * Play video and audio streams in parallel (no merging)
     * Video player shows video (muted), audio player plays audio
     */
    private fun playDualStreamParallel(videoUrl: String, audioUrl: String) {
        try {
            val videoItem = MediaItem.fromUri(videoUrl)
            val audioItem = MediaItem.fromUri(audioUrl)

            // Prepare both players
            videoPlayer?.apply {
                volume = 0f  // Mute video
                setMediaItem(videoItem)
                prepare()
            }

            audioPlayer?.apply {
                volume = 1f  // Full audio volume
                setMediaItem(audioItem)
                prepare()
            }

            FileLogger.log("✅ Both players prepared", "PlayerManager")

            // Start video first, then audio (helps with sync)
            videoPlayer?.play()
            
            // Small delay to help sync (optional, can remove if not needed)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                audioPlayer?.play()
                FileLogger.log("✅ Audio started", "PlayerManager")
            }, 50)

            FileLogger.log("✅ Starting parallel playback", "PlayerManager")

        } catch (e: Exception) {
            FileLogger.log("❌ Error in parallel playback: ${e.message}", "PlayerManager")
            eventListener.onPlayerError(e)
        }
    }

    fun pause() {
        videoPlayer?.pause()
        audioPlayer?.pause()
    }

    fun resume() {
        videoPlayer?.play()
        audioPlayer?.play()
    }

    fun seekTo(positionMs: Long) {
        videoPlayer?.seekTo(positionMs)
        audioPlayer?.seekTo(positionMs)
    }

    fun getCurrentPosition(): Long {
        return videoPlayer?.currentPosition ?: 0
    }

    fun getDuration(): Long {
        return videoPlayer?.duration ?: 0
    }

    fun release() {
        videoPlayer?.release()
        audioPlayer?.release()
        videoPlayer = null
        audioPlayer = null
    }

    fun setVolume(volume: Float) {
        audioPlayer?.volume = volume
    }

    fun getPlayer(): ExoPlayer? = videoPlayer
}
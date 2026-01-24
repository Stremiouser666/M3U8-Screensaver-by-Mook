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
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.datasource.DefaultHttpDataSource
import kotlin.random.Random

class PlayerManager(
    private val context: Context,
    private val eventListener: PlayerEventListener
) {
    private var exoPlayer: ExoPlayer? = null
    private var musicPlayer: MediaPlayer? = null
    private var streamStartTime: Long = 0
    
    // Playback preferences
    private var playbackSpeed: Float = 1.0f
    private var randomSeekEnabled: Boolean = true
    private var introEnabled: Boolean = true
    private var introDuration: Long = 7000L  // Store in milliseconds
    private var skipBeginningEnabled: Boolean = false
    private var skipBeginningDuration: Long = 0
    private var videoScalingMode: String = "scale_to_fit"
    private var audioEnabled: Boolean = false
    private var audioVolume: Float = 0.5f

    interface PlayerEventListener {
        fun onPlaybackStateChanged(state: Int)
        fun onPlayerError(error: Exception)
    }

    fun initialize(surface: Surface) {
        release()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                5000,
                20000,
                500,
                2000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setTargetBufferBytes(-1)
            .build()

        exoPlayer = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
            .apply {
                setVideoSurface(surface)
                
                playbackParameters = PlaybackParameters(playbackSpeed)
                volume = if (audioEnabled) audioVolume else 0f
                
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY && streamStartTime > 0) {
                            val latency = System.currentTimeMillis() - streamStartTime
                            FileLogger.log("⚡ PLAYBACK STARTED in ${latency}ms", "PlayerManager")
                            streamStartTime = 0
                            
                            handleInitialPlayback()
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
        introDuration = cache.introDuration.toLong() * 1000L  // Convert to ms
        skipBeginningEnabled = cache.skipBeginningEnabled
        skipBeginningDuration = cache.skipBeginningDuration
        audioEnabled = cache.audioEnabled
        audioVolume = cache.audioVolume / 100f
        videoScalingMode = cache.videoScalingMode
        
        exoPlayer?.let { player ->
            player.playbackParameters = PlaybackParameters(playbackSpeed)
            player.volume = if (audioEnabled) audioVolume else 0f
        }
        
        FileLogger.log("⚙️ Preferences updated - Speed: $playbackSpeed, Audio: ${if (audioEnabled) "${(audioVolume * 100).toInt()}%" else "OFF"}, Scaling: $videoScalingMode", "PlayerManager")
    }

    private fun handleInitialPlayback() {
        val player = exoPlayer ?: return
        val duration = player.duration
        
        if (duration <= 0 || duration == C.TIME_UNSET) {
            FileLogger.log("⚠️ Duration unknown, skipping initial playback setup", "PlayerManager")
            return
        }

        var seekPosition = 0L

        if (skipBeginningEnabled && skipBeginningDuration > 0) {
            seekPosition = skipBeginningDuration * 1000
            FileLogger.log("⏩ Skip beginning: ${skipBeginningDuration}s", "PlayerManager")
        }
        else if (randomSeekEnabled) {
            val safeEndPosition = (duration * 0.9).toLong()
            
            if (safeEndPosition > introDuration) {
                seekPosition = Random.nextLong(introDuration, safeEndPosition)
                FileLogger.log("🎲 Random seek to: ${seekPosition / 1000}s", "PlayerManager")
            }
        }
        else if (introEnabled && introDuration > 0) {
            seekPosition = 0
            FileLogger.log("▶️ Playing intro: ${introDuration / 1000}s", "PlayerManager")
        }

        if (seekPosition > 0) {
            player.seekTo(seekPosition)
        }
    }

    fun playStream(url: String) {
        val player = exoPlayer ?: return

        try {
            stopMusic()

            if (url.startsWith("VIDEO_ONLY|||")) {
                val videoUrl = url.substringAfter("VIDEO_ONLY|||")
                FileLogger.log("🎬 Loading video-only stream with music", "PlayerManager")
                playVideoOnly(videoUrl)
                return
            }

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

            FileLogger.log("🎬 Loading single stream: ${url.take(100)}...", "PlayerManager")
            streamStartTime = System.currentTimeMillis()
            val mediaItem = MediaItem.fromUri(url)
            player.setMediaItem(mediaItem)
            player.prepare()
            player.play()

        } catch (e: Exception) {
            FileLogger.log("❌ Error loading stream: ${e.message}", "PlayerManager")
            eventListener.onPlayerError(e)
        }
    }

    private fun playVideoOnly(videoUrl: String) {
        val player = exoPlayer ?: return

        try {
            streamStartTime = System.currentTimeMillis()
            val mediaItem = MediaItem.fromUri(videoUrl)
            player.setMediaItem(mediaItem)
            player.volume = 0f
            player.prepare()
            player.play()

            FileLogger.log("✅ Video-only stream loaded (muted)", "PlayerManager")
            startMusic()

        } catch (e: Exception) {
            FileLogger.log("❌ Error loading video-only stream: ${e.message}", "PlayerManager")
            eventListener.onPlayerError(e)
        }
    }

    private fun startMusic() {
        try {
            val musicResId = context.resources.getIdentifier("ambient_music", "raw", context.packageName)

            if (musicResId == 0) {
                FileLogger.log("ℹ️ No background music file found (res/raw/ambient_music.mp3)", "PlayerManager")
                return
            }

            musicPlayer = MediaPlayer.create(context, musicResId)?.apply {
                isLooping = true
                setVolume(0.3f, 0.3f)
                start()
                FileLogger.log("🎵 Background music started", "PlayerManager")
            }

        } catch (e: Exception) {
            FileLogger.log("⚠️ Could not start background music: ${e.message}", "PlayerManager")
        }
    }

    private fun stopMusic() {
        musicPlayer?.apply {
            if (isPlaying) {
                stop()
            }
            release()
        }
        musicPlayer = null
    }

    private fun playMergedStream(videoUrl: String, audioUrl: String) {
        val player = exoPlayer ?: return

        try {
            streamStartTime = System.currentTimeMillis()
            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(5000)
                .setReadTimeoutMs(5000)

            val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(videoUrl))

            val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(audioUrl))

            val merged = MergingMediaSource(videoSource, audioSource)

            FileLogger.log("✅ Merged source created (buffering ~5s before start)", "PlayerManager")

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
        stopMusic()
        exoPlayer?.release()
        exoPlayer = null
    }

    fun setVolume(volume: Float) {
        exoPlayer?.volume = volume
    }

    fun getPlayer(): ExoPlayer? = exoPlayer
}
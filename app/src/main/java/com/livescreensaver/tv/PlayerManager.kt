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
    
    // Playback preferences - will be set via updatePreferences()
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

        // Adaptive buffer based on playback speed
        val speedMultiplier = playbackSpeed.coerceAtLeast(1.0f)
        val minBuffer = (5000 * speedMultiplier).toInt()  // 5s base, scales with speed
        val maxBuffer = (30000 * speedMultiplier).toInt() // 30s base, scales with speed
        val playbackBuffer = 1000  // Still start quickly at 1s
        val rebufferThreshold = (3000 * speedMultiplier).toInt() // 3s base
        
        FileLogger.log("🔧 Buffer config for speed ${playbackSpeed}x: min=${minBuffer}ms, max=${maxBuffer}ms, rebuffer=${rebufferThreshold}ms", "PlayerManager")

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                minBuffer,
                maxBuffer,
                playbackBuffer,
                rebufferThreshold
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setTargetBufferBytes(-1)
            .setBackBuffer(15000, true)  // Keep 15s back buffer for smoother seeking
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
                            
                            // Apply initial seek/intro logic when ready
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
        
        FileLogger.log("⚙️ Preferences updated - Speed: $playbackSpeed, Audio: ${if (audioEnabled) "${(audioVolume * 100).toInt()}%" else "OFF"}, RandomSeek: $randomSeekEnabled, Intro: $introEnabled (${introDurationMs}ms), Skip: $skipBeginningEnabled (${skipBeginningDurationMs}ms)", "PlayerManager")
    }

    private fun handleInitialPlayback() {
        val player = exoPlayer ?: return
        val duration = player.duration
        
        FileLogger.log("🎯 handleInitialPlayback - duration: ${duration}ms, skipEnabled: $skipBeginningEnabled, randomEnabled: $randomSeekEnabled, introEnabled: $introEnabled", "PlayerManager")
        
        if (duration <= 0 || duration == C.TIME_UNSET) {
            FileLogger.log("⚠️ Duration unknown, skipping initial playback setup", "PlayerManager")
            return
        }

        // Priority 1: Skip beginning (takes precedence over everything)
        if (skipBeginningEnabled && skipBeginningDurationMs > 0) {
            player.seekTo(skipBeginningDurationMs)
            FileLogger.log("⏩ Skip beginning: ${skipBeginningDurationMs / 1000}s", "PlayerManager")
            return
        }
        
        // Priority 2: Intro + Random seek (play intro THEN seek randomly)
        if (introEnabled && introDurationMs > 0 && randomSeekEnabled) {
            FileLogger.log("▶️ Playing intro: ${introDurationMs / 1000}s, then will random seek", "PlayerManager")
            // Start from beginning (no seek)
            // Schedule random seek after intro duration
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING) {
                    val safeEndPosition = (duration * 0.9).toLong()
                    if (safeEndPosition > introDurationMs) {
                        val seekPosition = Random.nextLong(introDurationMs, safeEndPosition)
                        player.seekTo(seekPosition)
                        FileLogger.log("🎲 After intro, random seek to: ${seekPosition / 1000}s", "PlayerManager")
                    }
                }
            }, introDurationMs)
            return
        }
        
        // Priority 3: Random seek only (no intro)
        if (randomSeekEnabled) {
            val safeEndPosition = (duration * 0.9).toLong()
            val startPosition = 0L
            
            if (safeEndPosition > startPosition) {
                val seekPosition = Random.nextLong(startPosition, safeEndPosition)
                player.seekTo(seekPosition)
                FileLogger.log("🎲 Random seek to: ${seekPosition / 1000}s (from ${startPosition / 1000}s to ${safeEndPosition / 1000}s)", "PlayerManager")
            }
            return
        }
        
        // Priority 4: Intro play only (no random seek)
        if (introEnabled && introDurationMs > 0) {
            FileLogger.log("▶️ Playing intro: ${introDurationMs / 1000}s (no random seek)", "PlayerManager")
            // Start from beginning - no seek needed
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
        hasAppliedInitialSeek = false
    }

    fun setVolume(volume: Float) {
        exoPlayer?.volume = volume
    }

    fun getPlayer(): ExoPlayer? = exoPlayer
}
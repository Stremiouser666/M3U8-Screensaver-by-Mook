package com.livescreensaver.tv

import android.content.Context
import android.view.Surface
import android.view.View
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.datasource.DefaultHttpDataSource
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import kotlin.random.Random

class PlayerManager(
    private val context: Context,
    private val eventListener: PlayerEventListener,
    private val youtubePlayerView: YouTubePlayerView
) {
    private var exoPlayer: ExoPlayer? = null
    private var youtubePlayer: YouTubePlayer? = null
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
    private var currentResolution: Int = 1080

    // Track which player is active
    private var isUsingYouTubePlayer = false
    
    // Track video IDs for YouTube player
    private var currentVideoId: String? = null
    private var pendingVideoId: String? = null

    private val youtubeDataSourceFactory = DefaultHttpDataSource.Factory()
        .setConnectTimeoutMs(15000)
        .setReadTimeoutMs(15000)
        .setUserAgent("com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 11) gzip")

    interface PlayerEventListener {
        fun onPlaybackStateChanged(state: Int)
        fun onPlayerError(error: Exception)
    }

    fun initialize(surface: Surface) {
        release()
        hasAppliedInitialSeek = false
        isUsingYouTubePlayer = false
        currentVideoId = null
        pendingVideoId = null

        // Initialize ExoPlayer (for Rutube and 360p YouTube)
        val speedMultiplier = playbackSpeed.coerceAtLeast(1.0f)
        val minBuffer = (30000 * speedMultiplier).toInt()
        val maxBuffer = (120000 * speedMultiplier).toInt()
        val playbackBuffer = 5000
        val rebufferThreshold = (20000 * speedMultiplier).toInt()

        FileLogger.log("🔧 Buffer config for speed ${playbackSpeed}x: min=${minBuffer}ms, max=${maxBuffer}ms, playback=${playbackBuffer}ms, rebuffer=${rebufferThreshold}ms", "PlayerManager")

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBuffer, maxBuffer, playbackBuffer, rebufferThreshold)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setTargetBufferBytes(-1)
            .setBackBuffer(60000, true)
            .build()

        exoPlayer = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
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

                            if (!hasAppliedInitialSeek && !isUsingYouTubePlayer) {
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

        // Initialize YouTube Player with auto-play configuration
        val iFramePlayerOptions = IFramePlayerOptions.Builder()
            .controls(0)  // Hide all controls
            .autoplay(1)  // Auto-play without requiring click
            .rel(0)       // Don't show related videos at end
            .ivLoadPolicy(3)  // Hide video annotations
            .build()

        youtubePlayerView.enableAutomaticInitialization = false
        youtubePlayerView.initialize(object : AbstractYouTubePlayerListener() {
            override fun onReady(player: YouTubePlayer) {
                youtubePlayer = player
                
                FileLogger.log("✅ YouTube Player ready", "PlayerManager")
                
                // If there's a pending video to load, load it now
                if (pendingVideoId != null) {
                    FileLogger.log("▶️ Loading pending video: $pendingVideoId", "PlayerManager")
                    currentVideoId = pendingVideoId
                    player.loadVideo(pendingVideoId!!, 0f)
                    if (!audioEnabled) {
                        player.mute()
                    } else {
                        player.unMute()
                    }
                    pendingVideoId = null
                }
            }

            override fun onStateChange(
                player: YouTubePlayer,
                state: com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants.PlayerState
            ) {
                when (state) {
                    com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants.PlayerState.PLAYING -> {
                        if (streamStartTime > 0) {
                            val latency = System.currentTimeMillis() - streamStartTime
                            FileLogger.log("⚡ YOUTUBE PLAYBACK STARTED in ${latency}ms", "PlayerManager")
                            streamStartTime = 0
                        }
                        eventListener.onPlaybackStateChanged(Player.STATE_READY)
                    }
                    com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants.PlayerState.ENDED -> {
                        // Loop the video (screensaver behavior)
                        if (currentVideoId != null) {
                            FileLogger.log("🔁 Video ended, looping: $currentVideoId", "PlayerManager")
                            player.loadVideo(currentVideoId!!, 0f)
                        }
                    }
                    else -> {
                        // Other states (buffering, paused, etc.)
                    }
                }
            }

            override fun onError(
                player: YouTubePlayer,
                error: com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants.PlayerError
            ) {
                FileLogger.log("❌ YouTube Player error: $error", "PlayerManager")
                eventListener.onPlayerError(Exception("YouTube Player error: $error"))
            }
        }, iFramePlayerOptions)
    }

    private fun applyBitrateLimits() {
        val player = exoPlayer ?: return

        val maxBitrate = calculateMaxBitrate(currentResolution, playbackSpeed)

        if (maxBitrate > 0) {
            val trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setMaxVideoBitrate(maxBitrate)
                .build()

            player.trackSelectionParameters = trackSelectionParameters
            FileLogger.log("🎯 Smart bitrate limit applied: ${maxBitrate / 1_000_000f} Mbps for ${currentResolution}p at ${playbackSpeed}x speed", "PlayerManager")
        } else {
            FileLogger.log("🎯 No bitrate limit (unlimited) for ${currentResolution}p at ${playbackSpeed}x speed", "PlayerManager")
        }
    }

    private fun calculateMaxBitrate(resolution: Int, speed: Float): Int {
        return when {
            resolution == 1080 && speed == 1.5f -> 6_000_000
            resolution == 1080 && speed == 2.0f -> 2_500_000
            resolution == 720 && speed == 2.0f -> 1_500_000
            else -> 0
        }
    }

    fun setResolution(resolution: Int) {
        if (resolution != 720 && resolution != 1080 && resolution != 360) {
            FileLogger.log("⚠️ Invalid resolution: $resolution. Must be 360, 720 or 1080", "PlayerManager")
            return
        }

        currentResolution = resolution
        FileLogger.log("📺 Resolution set to: ${resolution}p", "PlayerManager")
        applyBitrateLimits()
    }

    fun updatePreferences(cache: PreferenceCache) {
        val oldSpeed = playbackSpeed

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

            if (oldSpeed != playbackSpeed) {
                applyBitrateLimits()
            }
        }

        youtubePlayer?.let { player ->
            if (!audioEnabled) {
                player.mute()
            } else {
                player.unMute()
            }
        }

        FileLogger.log("⚙️ Preferences updated - Speed: $playbackSpeed, Audio: ${if (audioEnabled) "${(audioVolume * 100).toInt()}%" else "OFF"}, RandomSeek: $randomSeekEnabled, Intro: $introEnabled (${introDurationMs}ms), Skip: $skipBeginningEnabled (${skipBeginningDurationMs}ms)", "PlayerManager")
    }

    private fun handleInitialPlayback() {
        val player = exoPlayer ?: return
        val duration = player.duration

        FileLogger.log("🎯 handleInitialPlayback - duration: ${duration}ms, skipEnabled: $skipBeginningEnabled (${skipBeginningDurationMs}ms), randomEnabled: $randomSeekEnabled, introEnabled: $introEnabled (${introDurationMs}ms)", "PlayerManager")

        if (duration <= 0 || duration == C.TIME_UNSET) {
            FileLogger.log("⚠️ Duration unknown, skipping initial playback setup", "PlayerManager")
            return
        }

        if (skipBeginningEnabled && skipBeginningDurationMs > 0 && randomSeekEnabled) {
            val safeEndPosition = (duration * 0.9).toLong()
            if (safeEndPosition > skipBeginningDurationMs) {
                val seekPosition = Random.nextLong(skipBeginningDurationMs, safeEndPosition)
                player.seekTo(seekPosition)
                FileLogger.log("⏩🎲 Skip + Random: ${seekPosition / 1000}s", "PlayerManager")
            } else {
                player.seekTo(skipBeginningDurationMs)
                FileLogger.log("⏩ Skip beginning: ${skipBeginningDurationMs / 1000}s", "PlayerManager")
            }
            return
        }

        if (skipBeginningEnabled && skipBeginningDurationMs > 0) {
            player.seekTo(skipBeginningDurationMs)
            FileLogger.log("⏩ Skip beginning: ${skipBeginningDurationMs / 1000}s", "PlayerManager")
            return
        }

        if (randomSeekEnabled) {
            val safeEndPosition = (duration * 0.9).toLong()
            val startPosition = 0L

            if (safeEndPosition > startPosition) {
                val seekPosition = Random.nextLong(startPosition, safeEndPosition)
                player.seekTo(seekPosition)
                FileLogger.log("🎲 Random seek to: ${seekPosition / 1000}s", "PlayerManager")
            }
        }
    }

    fun playStream(url: String) {
        FileLogger.log("🎬 playStream() called with: ${url.take(100)}...", "PlayerManager")

        if (url.startsWith("youtube_embed://")) {
            playWithYouTubePlayer(url)
        } else {
            playWithExoPlayer(url)
        }
    }

    private fun playWithYouTubePlayer(embedUrl: String) {
        val videoId = embedUrl.removePrefix("youtube_embed://")
        
        FileLogger.log("🎬 Loading YouTube video in embed player: $videoId", "PlayerManager")
        streamStartTime = System.currentTimeMillis()
        isUsingYouTubePlayer = true
        currentVideoId = videoId

        // Hide ExoPlayer, show YouTube player
        youtubePlayerView.visibility = View.VISIBLE
        exoPlayer?.pause()

        // Load video
        youtubePlayer?.let { player ->
            player.loadVideo(videoId, 0f)
            
            if (!audioEnabled) {
                player.mute()
            } else {
                player.unMute()
            }
        } ?: run {
            // Player not ready yet - save video ID for later
            FileLogger.log("⚠️ YouTube player not ready yet, queuing video...", "PlayerManager")
            pendingVideoId = videoId
        }
    }

    private fun playWithExoPlayer(url: String) {
        val player = exoPlayer ?: return

        FileLogger.log("🎬 Loading in ExoPlayer: ${url.take(100)}...", "PlayerManager")
        streamStartTime = System.currentTimeMillis()
        isUsingYouTubePlayer = false
        currentVideoId = null

        // Show ExoPlayer, hide YouTube player
        youtubePlayerView.visibility = View.GONE

        try {
            if (url.contains("googlevideo.com")) {
                val mediaSource = ProgressiveMediaSource.Factory(youtubeDataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(url))
                player.setMediaSource(mediaSource)
            } else {
                player.setMediaItem(MediaItem.fromUri(url))
            }

            player.prepare()
            player.play()

        } catch (e: Exception) {
            FileLogger.log("❌ Error loading in ExoPlayer: ${e.message}", "PlayerManager")
            eventListener.onPlayerError(e)
        }
    }

    fun pause() {
        exoPlayer?.pause()
        youtubePlayer?.pause()
    }

    fun resume() {
        exoPlayer?.play()
        youtubePlayer?.play()
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
        youtubePlayerView.visibility = View.GONE
        youtubePlayer?.pause()
        currentVideoId = null
        pendingVideoId = null
        exoPlayer?.release()
        exoPlayer = null
        hasAppliedInitialSeek = false
    }

    fun setVolume(volume: Float) {
        exoPlayer?.volume = volume
    }

    fun getPlayer(): ExoPlayer? = exoPlayer
}

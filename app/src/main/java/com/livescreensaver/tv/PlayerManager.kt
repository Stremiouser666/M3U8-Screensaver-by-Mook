package com.livescreensaver.tv

import android.content.Context
import android.view.Surface
import android.view.View
import android.webkit.WebView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.datasource.DefaultHttpDataSource
import kotlin.random.Random

class PlayerManager(
    private val context: Context,
    private val eventListener: PlayerEventListener,
    private val webView: WebView  // WebView for YouTube proxy
) {
    private var exoPlayer: ExoPlayer? = null
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
    private var isUsingWebView = false

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
        isUsingWebView = false

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

                            if (!hasAppliedInitialSeek && !isUsingWebView) {
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

        // Configure WebView for YouTube proxy
        webView.settings.apply {
            javaScriptEnabled = true
            mediaPlaybackRequiresUserGesture = false
            domStorageEnabled = true
        }
        
        // Add WebChromeClient to handle fullscreen requests
        webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onShowCustomView(view: android.view.View?, callback: CustomViewCallback?) {
                super.onShowCustomView(view, callback)
            }
        }
        
        // Add WebViewClient to inject fullscreen after page loads
        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Force iframe into fullscreen mode to reduce UI chrome
                view?.evaluateJavascript(
                    """
                    (function() {
                        var iframe = document.querySelector('iframe');
                        if (iframe) {
                            iframe.style.position = 'fixed';
                            iframe.style.top = '0';
                            iframe.style.left = '0';
                            iframe.style.width = '100vw';
                            iframe.style.height = '100vh';
                            iframe.style.border = 'none';
                            iframe.style.zIndex = '9999';
                        }
                    })();
                    """.trimIndent(),
                    null
                )
            }
        }
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

        if (url.startsWith("webview://")) {
            playWithWebView(url)
        } else {
            playWithExoPlayer(url)
        }
    }

    private fun playWithWebView(embedUrl: String) {
        val proxyUrl = embedUrl.removePrefix("webview://")
        
        // Add audio parameter based on preference
        val finalUrl = if (audioEnabled) {
            proxyUrl.replace("&mute=0", "&mute=0")
        } else {
            proxyUrl.replace("&mute=0", "&mute=1")
        }
        
        FileLogger.log("🎬 Loading YouTube video in WebView proxy: ${finalUrl.take(100)}...", "PlayerManager")
        streamStartTime = System.currentTimeMillis()
        isUsingWebView = true

        // Hide ExoPlayer, show WebView
        webView.visibility = View.VISIBLE
        exoPlayer?.pause()

        // Load URL - auto-plays because of autoplay=1 parameter
        webView.loadUrl(finalUrl)
        
        // Simulate playback started for event listener
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (streamStartTime > 0) {
                val latency = System.currentTimeMillis() - streamStartTime
                FileLogger.log("⚡ WEBVIEW PLAYBACK STARTED in ${latency}ms", "PlayerManager")
                streamStartTime = 0
                eventListener.onPlaybackStateChanged(Player.STATE_READY)
            }
        }, 2000) // Give WebView 2s to load
    }

    private fun playWithExoPlayer(url: String) {
        val player = exoPlayer ?: return

        FileLogger.log("🎬 Loading in ExoPlayer: ${url.take(100)}...", "PlayerManager")
        streamStartTime = System.currentTimeMillis()
        isUsingWebView = false

        // Show ExoPlayer, hide WebView
        webView.visibility = View.GONE

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
        webView.onPause()
    }

    fun resume() {
        exoPlayer?.play()
        webView.onResume()
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
        webView.visibility = View.GONE
        webView.loadUrl("about:blank")
        exoPlayer?.release()
        exoPlayer = null
        hasAppliedInitialSeek = false
    }

    fun setVolume(volume: Float) {
        exoPlayer?.volume = volume
    }

    fun getPlayer(): ExoPlayer? = exoPlayer
}

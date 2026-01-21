package com.livescreensaver.tv

import android.content.ComponentCallbacks2
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.service.dreams.DreamService
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.Player
import androidx.preference.PreferenceManager
import kotlinx.coroutines.*

class LiveScreensaverService : DreamService(), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "LiveScreensaverService"
        private const val PREF_VIDEO_URL = "video_url"
        const val DEFAULT_VIDEO_URL = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
        private const val CACHE_DURATION = 300L
        private const val MAX_RETRIES = 3
        private const val STALL_TIMEOUT_MS = 10_000L
        private const val STALL_CHECK_INTERVAL_MS = 1_000L
        private const val PREFS_NAME = "stream_cache_prefs"
        private const val RESUME_CACHE_PREFS = "resume_cache"
        private const val STATS_PREFS = "usage_stats"
        private const val BANDWIDTH_PREFS = "bandwidth_stats"
        private const val EXTRACTION_TIMEOUT_MS = 25_000L
    }

    private var prefCache: PreferenceCache? = null
    private var surfaceView: SurfaceView? = null
    private var containerLayout: FrameLayout? = null
    private var loadingOverlay: LoadingAnimationOverlay? = null
    private var loadingTextView: TextView? = null

    private lateinit var streamExtractor: StreamExtractor
    private lateinit var preferences: SharedPreferences
    private lateinit var cachePrefs: SharedPreferences
    private lateinit var resumeCache: SharedPreferences
    private lateinit var statsPrefs: SharedPreferences
    private lateinit var bandwidthPrefs: SharedPreferences
    private lateinit var preferenceManager: AppPreferenceManager
    private lateinit var scheduleManager: ScheduleManager
    private lateinit var resumeManager: ResumeManager
    private lateinit var uiOverlayManager: UIOverlayManager
    private lateinit var usageStatsTracker: UsageStatsTracker
    private lateinit var bandwidthTracker: BandwidthTracker
    private lateinit var playerManager: PlayerManager

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val extractionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    private var retryCount = 0
    private var currentSourceUrl: String? = null
    private var stallDetectionTime: Long = 0
    private var surfaceReady = false
    private var isRetrying = false
    private var activeExtractionJob: Job? = null
    private var hasProcessedPlayback = false

    private val stallCheckRunnable = object : Runnable {
        override fun run() {
            checkForStall()
            handler.postDelayed(this, STALL_CHECK_INTERVAL_MS)
        }
    }

    private val statsUpdateRunnable = object : Runnable {
        override fun run() {
            val cache = prefCache ?: return
            val usageStats = usageStatsTracker.getUsageStats()
            val bandwidthStats = bandwidthTracker.getBandwidthStats()
            uiOverlayManager.updateStats(playerManager.getPlayer(), usageStats, bandwidthStats)
            usageStatsTracker.trackPlaybackUsage()
            bandwidthTracker.trackBandwidth(playerManager.getPlayer())
            handler.postDelayed(this, cache.statsInterval)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        try {
            if (BuildConfig.DEBUG) {
                StrictMode.setThreadPolicy(
                    StrictMode.ThreadPolicy.Builder()
                        .detectAll()
                        .penaltyLog()
                        .penaltyFlashScreen()
                        .build()
                )
                StrictMode.setVmPolicy(
                    StrictMode.VmPolicy.Builder()
                        .detectAll()
                        .penaltyLog()
                        .build()
                )
            }

            isInteractive = false
            isFullscreen = true
            isScreenBright = true

            FileLogger.enable(this)
            FileLogger.log("🚀 LiveScreensaverService starting...")

            preferences = PreferenceManager.getDefaultSharedPreferences(this)
            cachePrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            resumeCache = getSharedPreferences(RESUME_CACHE_PREFS, MODE_PRIVATE)
            statsPrefs = getSharedPreferences(STATS_PREFS, MODE_PRIVATE)
            bandwidthPrefs = getSharedPreferences(BANDWIDTH_PREFS, MODE_PRIVATE)

            preferenceManager = AppPreferenceManager(preferences)
            scheduleManager = ScheduleManager(preferences, DEFAULT_VIDEO_URL)
            resumeManager = ResumeManager(resumeCache)
            usageStatsTracker = UsageStatsTracker(statsPrefs)
            bandwidthTracker = BandwidthTracker(bandwidthPrefs)

            prefCache = preferenceManager.loadPreferenceCache()
            streamExtractor = StreamExtractor(this, cachePrefs)

            refreshUrlIfNeeded()
            setupSurface()

            Log.d(TAG, "✅ Service initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Fatal startup error - falling back to safe state", e)
            try {
                preferences.edit().putString(PREF_VIDEO_URL, DEFAULT_VIDEO_URL).apply()
                setupSurface()
                initializePlayer()
            } catch (fallbackError: Exception) {
                Log.e(TAG, "Fallback initialization also failed", fallbackError)
            }
        }
    }

    private fun refreshUrlIfNeeded() {
        val originalUrl = streamExtractor.getCachedOriginalUrl()
        val urlType = streamExtractor.getCachedUrlType()

        if (originalUrl == null || urlType == null) {
            Log.d(TAG, "No cached URL - will extract on first playback")
            return
        }

        val currentMainUrl = preferences.getString(PREF_VIDEO_URL, DEFAULT_VIDEO_URL) ?: DEFAULT_VIDEO_URL

        if (currentMainUrl.contains(".m3u8")) {
            Log.d(TAG, "✅ Direct HLS URL - no refresh needed")
            return
        }

        Log.d(TAG, "🔄 Refreshing $urlType URL on startup...")

        extractionScope.launch {
            try {
                when (urlType) {
                    "rutube" -> {
                        val refreshedUrl = withTimeout(EXTRACTION_TIMEOUT_MS) {
                            streamExtractor.extractRutubeUrl(originalUrl)
                        }
                        if (refreshedUrl != null) {
                            preferences.edit().putString(PREF_VIDEO_URL, refreshedUrl).apply()
                            Log.d(TAG, "✅ Rutube URL refreshed")
                        } else {
                            Log.w(TAG, "⚠️ Rutube refresh failed - will try cached URL")
                        }
                    }
                    "youtube" -> {
                        Log.d(TAG, "YouTube URL will be extracted by NewPipe on playback")
                    }
                    else -> {
                        Log.d(TAG, "Unknown URL type: $urlType")
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "URL refresh timed out - will try cached/original URL")
            } catch (e: Exception) {
                Log.e(TAG, "URL refresh error - will try cached/original URL", e)
            }
        }
    }

    private fun setupSurface() {
        containerLayout = FrameLayout(this)

        surfaceView = SurfaceView(this).apply {
            holder.addCallback(this@LiveScreensaverService)
        }

        containerLayout?.addView(surfaceView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        containerLayout?.let { container ->
            uiOverlayManager = UIOverlayManager(this, container, handler)

            prefCache?.let { cache ->
                if (cache.clockEnabled) {
                    uiOverlayManager.setupClock(cache)
                }

                if (cache.statsEnabled) {
                    uiOverlayManager.setupStats(cache)
                    handler.postDelayed(statsUpdateRunnable, cache.statsInterval)
                }
            }
        }

        setContentView(containerLayout)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        FileLogger.log("🖥️ Surface created - surfaceReady = true")
        surfaceReady = true
        initializePlayer()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        releasePlayer()
    }

    private fun initializePlayer() {
        FileLogger.log("🎮 initializePlayer() called - surfaceReady=$surfaceReady, playerExists=${::playerManager.isInitialized}")

        if (!surfaceReady || ::playerManager.isInitialized) return

        try {
            FileLogger.log("⏳ Starting player initialization...")
            showLoadingAnimation()

            val videoUrl = getVideoUrl()
            FileLogger.log("📺 Video URL to load: $videoUrl")

            playerManager = PlayerManager(
                context = this,
                eventListener = object : PlayerManager.PlayerEventListener {
                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_READY -> handlePlayerReady()
                            Player.STATE_IDLE, Player.STATE_ENDED -> {
                                if (stallDetectionTime == 0L) {
                                    stallDetectionTime = System.currentTimeMillis()
                                }
                            }
                        }
                        
                        val isPlaying = state == Player.STATE_READY && playerManager.getPlayer()?.playWhenReady == true
                        handlePlayingChanged(isPlaying)
                    }
                    
                    override fun onPlayerError(error: Exception) {
                        hideLoadingAnimation()
                        handlePlaybackFailure()
                    }
                }
            )

            // Initialize player with surface
            val surface = surfaceView?.holder?.surface
            if (surface != null) {
                playerManager.initialize(surface)
                loadStream(videoUrl)
                handler.post(stallCheckRunnable)
            } else {
                Log.e(TAG, "Surface is null, cannot initialize player")
            }

        } catch (e: Exception) {
            hideLoadingAnimation()
            Log.e(TAG, "Player initialization failed", e)
            handler.postDelayed({ initializePlayer() }, 5000)
        }
    }

    private fun handlePlayerReady() {
        hideLoadingAnimation()
        if (!hasProcessedPlayback) {
            stallDetectionTime = 0
            retryCount = 0

            val cache = prefCache ?: return
            val player = playerManager.getPlayer() ?: return
            val duration = player.duration
            val skipDuration = if (cache.skipBeginningEnabled) cache.skipBeginningDuration else 0L

            val resumedPosition = resumeManager.attemptResume(cache, currentSourceUrl, duration, skipDuration)

            // Apply skip/resume logic
            if (resumedPosition != null && resumedPosition > 0) {
                player.seekTo(resumedPosition)
            }
            
            hasProcessedPlayback = true
        }
    }

    private fun handlePlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            stallDetectionTime = 0
        } else if (playerManager.getPlayer()?.playbackState == Player.STATE_READY && stallDetectionTime == 0L) {
            stallDetectionTime = System.currentTimeMillis()
        }
    }

    private fun getVideoUrl(): String {
        val cache = prefCache ?: return preferences.getString(PREF_VIDEO_URL, DEFAULT_VIDEO_URL) ?: DEFAULT_VIDEO_URL
        return scheduleManager.getScheduledUrl(cache)
    }

    private fun loadStream(sourceUrl: String) {
        currentSourceUrl = sourceUrl
        FileLogger.log("🔄 loadStream() called with: $sourceUrl", TAG)
        Log.d(TAG, "🔄 Loading stream: $sourceUrl")

        activeExtractionJob?.cancel()

        activeExtractionJob = extractionScope.launch {
            try {
                FileLogger.log("🚀 Coroutine started for stream loading", TAG)

                val streamUrl = withTimeout(EXTRACTION_TIMEOUT_MS) {
                    if (streamExtractor.needsExtraction(sourceUrl)) {
                        FileLogger.log("✅ Needs extraction: true", TAG)
                        val cachedUrl = streamExtractor.getCachedUrl()
                        if (cachedUrl != null && cachedUrl.isNotEmpty()) {
                            FileLogger.log("✅ Trying cached extracted URL first: $cachedUrl", TAG)
                            Log.d(TAG, "✅ Trying cached extracted URL first")
                            cachedUrl
                        } else {
                            FileLogger.log("⚠️ No cached URL, extracting...", TAG)
                            streamExtractor.extractStreamUrl(sourceUrl, false, CACHE_DURATION)
                                ?: streamExtractor.extractStreamUrl(sourceUrl, true, CACHE_DURATION)
                        }
                    } else {
                        FileLogger.log("✅ Direct URL, no extraction needed", TAG)
                        sourceUrl
                    }
                }

                withContext(Dispatchers.Main) {
                    if (streamUrl != null) {
                        FileLogger.log("✅ Stream URL resolved: $streamUrl", TAG)
                        Log.d(TAG, "✅ Stream URL resolved successfully")
                        playStream(streamUrl)
                    } else {
                        FileLogger.log("❌ Stream URL is null - using default", TAG)
                        Log.e(TAG, "Failed to load stream - using default")
                        playStream(DEFAULT_VIDEO_URL)
                    }
                }
            } catch (e: TimeoutCancellationException) {
                FileLogger.logError("Stream extraction timed out after ${EXTRACTION_TIMEOUT_MS}ms", e, TAG)
                Log.e(TAG, "Stream extraction timed out", e)
                withContext(Dispatchers.Main) {
                    playStream(DEFAULT_VIDEO_URL)
                }
            } catch (e: CancellationException) {
                FileLogger.log("⚠️ Stream extraction cancelled (service stopped)", TAG)
            } catch (e: Exception) {
                FileLogger.logError("Stream loading error", e, TAG)
                Log.e(TAG, "Stream loading error", e)
                withContext(Dispatchers.Main) {
                    playStream(DEFAULT_VIDEO_URL)
                }
            }
        }
    }

    private fun playStream(streamUrl: String) {
        hideLoadingAnimation()
        playerManager.playStream(streamUrl)
    }

    private fun checkForStall() {
        if (stallDetectionTime > 0 && System.currentTimeMillis() - stallDetectionTime > STALL_TIMEOUT_MS) {
            handlePlaybackFailure()
        }
    }

    private fun handlePlaybackFailure() {
        if (isRetrying || retryCount >= MAX_RETRIES) return

        isRetrying = true
        retryCount++

        Log.w(TAG, "⚠️ Playback failed - attempt $retryCount of $MAX_RETRIES")

        if (retryCount == 1) {
            Log.w(TAG, "🔄 Attempting URL refresh...")

            extractionScope.launch {
                try {
                    val originalUrl = streamExtractor.getCachedOriginalUrl()
                    val urlType = streamExtractor.getCachedUrlType()

                    if (originalUrl != null && urlType == "rutube") {
                        val refreshedUrl = withTimeout(EXTRACTION_TIMEOUT_MS) {
                            streamExtractor.extractRutubeUrl(originalUrl)
                        }
                        if (refreshedUrl != null) {
                            currentSourceUrl = refreshedUrl
                            Log.d(TAG, "✅ URL refreshed after playback failure")
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    Log.e(TAG, "URL refresh timed out on failure")
                } catch (e: Exception) {
                    Log.e(TAG, "URL refresh on failure error", e)
                }
            }
        }

        handler.postDelayed({
            retryPlayback()
        }, (Math.pow(2.0, (retryCount - 1).toDouble()) * 1000).toLong())
    }

    private fun retryPlayback() {
        stallDetectionTime = 0
        isRetrying = false
        hasProcessedPlayback = false
        playerManager.release()
        
        // Reinitialize player for retry
        val surface = surfaceView?.holder?.surface
        if (surface != null) {
            playerManager.initialize(surface)
            currentSourceUrl?.let { loadStream(it) }
        }
    }

    private fun setupLoadingAnimation() {
        try {
            val animationType = when (preferenceManager.getLoadingAnimationType()) {
                "pulsing" -> LoadingAnimationOverlay.AnimationType.PULSING
                "progress_bar" -> LoadingAnimationOverlay.AnimationType.PROGRESS_BAR
                "wave" -> LoadingAnimationOverlay.AnimationType.WAVE
                else -> LoadingAnimationOverlay.AnimationType.SPINNING_DOTS
            }

            val customText = preferenceManager.getLoadingAnimationText()

            loadingOverlay = LoadingAnimationOverlay(this)
            loadingTextView = loadingOverlay?.createLoadingView(animationType, customText)

            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = Gravity.CENTER
            }

            containerLayout?.addView(loadingTextView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup loading animation", e)
        }
    }

    private fun hideLoadingAnimation() {
        loadingOverlay?.stop()
        containerLayout?.removeView(loadingTextView)
        loadingTextView = null
        loadingOverlay = null
    }

    private fun showLoadingAnimation() {
        handler.post {
            setupLoadingAnimation()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> {
                Log.w(TAG, "🔶 Moderate memory pressure detected")
                handler.removeCallbacks(statsUpdateRunnable)
                if (prefCache?.statsEnabled == true) {
                    handler.postDelayed(statsUpdateRunnable, 2000)
                }
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                Log.e(TAG, "🔴 Critical memory pressure - reducing features")
                handler.removeCallbacks(uiOverlayManager.getPixelShiftRunnable())
                uiOverlayManager.hideStats()
                handler.removeCallbacks(statsUpdateRunnable)
            }
        }
    }

    private fun releasePlayer() {
        try {
            hideLoadingAnimation()
            if (::playerManager.isInitialized) {
                resumeManager.savePlaybackPosition(playerManager.getPlayer(), currentSourceUrl)
            }
            if (::bandwidthTracker.isInitialized) {
                bandwidthTracker.saveDailyBandwidth()
            }
            handler.removeCallbacksAndMessages(null)
            if (::playerManager.isInitialized) {
                playerManager.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing player", e)
        }
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        FileLogger.log("🛑 LiveScreensaverService stopping...")
        releasePlayer()
        FileLogger.disable()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        try {
            serviceScope.cancel()
            releasePlayer()
            if (::uiOverlayManager.isInitialized) {
                uiOverlayManager.cleanup()
            }
            prefCache = null
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDetachedFromWindow", e)
        }
    }
}
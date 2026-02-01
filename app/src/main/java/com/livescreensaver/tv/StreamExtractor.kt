package com.livescreensaver.tv

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File
import java.util.concurrent.TimeUnit

class StreamExtractor(
    private val context: Context,
    private val cachePrefs: SharedPreferences
) {
    companion object {
        private const val TAG = "StreamExtractor"
        private const val CACHE_DIR = "stream_cache"
        private const val KEY_ORIGINAL_URL = "original_url"
        private const val KEY_EXTRACTED_URL = "extracted_url"
        private const val KEY_URL_TYPE = "url_type"
        private const val KEY_QUALITY_MODE = "quality_mode"
    }

    private val cacheDir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val standaloneExtractor = YouTubeStandaloneExtractor(context, httpClient)
    private val embedExtractor = YouTubeEmbedExtractor()

    fun needsExtraction(url: String): Boolean {
        return !url.contains(".m3u8") && 
               (url.contains("youtube.com") || url.contains("youtu.be") || url.contains("rutube.ru"))
    }

    private fun isNetworkAvailable(): Boolean {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking network availability", e)
            return false
        }
    }

    private fun getQualityMode(): String {
        val prefs = context.getSharedPreferences("com.livescreensaver.tv_preferences", Context.MODE_PRIVATE)
        return prefs.getString("youtube_quality_mode", "360_progressive") ?: "360_progressive"
    }

    private fun getTargetHeight(): Int {
        return when (getQualityMode()) {
            "360_progressive" -> 360
            "480_video_only" -> 480
            "720_video_only" -> 720
            "1080_video_only" -> 1080
            "1440_video_only" -> 1440
            "2160_video_only" -> 2160
            else -> 720
        }
    }

    /**
     * Clears the cached extracted URL so that the next call to extractStreamUrl
     * (or getCachedUrl) will force a fresh extraction from YouTube/Rutube.
     *
     * Call this after a playback failure (e.g. 403) so that retries don't keep
     * hitting the same broken cached URL.
     */
    fun invalidateCache() {
        cachePrefs.edit()
            .remove(KEY_EXTRACTED_URL)
            .remove(KEY_URL_TYPE)
            .apply()

        FileLogger.log("🗑️ Cache invalidated - next extraction will be fresh", TAG)
    }

    suspend fun extractStreamUrl(sourceUrl: String, forceRefresh: Boolean, cacheExpirationSeconds: Long): String? = withContext(Dispatchers.IO) {
        try {
            FileLogger.log("🎬 Starting extraction for: $sourceUrl", TAG)

            if (!isNetworkAvailable()) {
                FileLogger.log("📵 No network available - using cached URL", TAG)
                Log.w(TAG, "📵 No network available - using cached URL")
                return@withContext cachePrefs.getString(KEY_EXTRACTED_URL, null) ?: sourceUrl
            }

            // If forceRefresh is set, clear the cache before proceeding so we
            // don't fall through to the stale cached URL.
            if (forceRefresh) {
                FileLogger.log("🔄 forceRefresh=true - invalidating cache before extraction", TAG)
                invalidateCache()
            }

            if (sourceUrl.contains("rutube.ru", ignoreCase = true)) {
                FileLogger.log("🎬 Extracting Rutube URL...", TAG)
                Log.d(TAG, "🎬 Extracting Rutube URL...")
                val extractedUrl = extractRutubeUrl(sourceUrl)
                if (extractedUrl != null) {
                    saveToCache(sourceUrl, extractedUrl, "rutube")
                    FileLogger.log("✅ Rutube extraction succeeded: $extractedUrl", TAG)
                } else {
                    FileLogger.log("❌ Rutube extraction failed", TAG)
                }
                return@withContext extractedUrl
            }

            FileLogger.log("🎬 Extracting YouTube URL...", TAG)
            Log.d(TAG, "🎬 Extracting YouTube URL...")

            val qualityMode = getQualityMode()
            FileLogger.log("Quality mode: $qualityMode", TAG)

            // Route based on quality mode
            if (qualityMode == "360_progressive") {
                // Try standalone extractor for 360p progressive (itag=18 with direct URL)
                // Do NOT fall back to embed player for 360p
                FileLogger.log("🔧 Trying standalone extractor for 360p progressive...", TAG)
                try {
                    val standaloneResult = standaloneExtractor.extractStream(sourceUrl)
                    if (standaloneResult.success && standaloneResult.streamUrl != null) {
                        FileLogger.log("✅ Standalone extractor succeeded: ${standaloneResult.quality}", TAG)
                        FileLogger.log("📺 Stream URL: ${standaloneResult.streamUrl}", TAG)
                        Log.d(TAG, "✅ Standalone extractor succeeded: ${standaloneResult.quality}")
                        saveToCache(sourceUrl, standaloneResult.streamUrl, "youtube")
                        return@withContext standaloneResult.streamUrl
                    } else {
                        FileLogger.log("❌ Standalone extractor failed for 360p: ${standaloneResult.errorMessage}", TAG)
                        FileLogger.log("❌ 360p mode does not use embed player - returning null", TAG)
                        Log.e(TAG, "❌ 360p extraction failed - no fallback to embed player")
                        return@withContext null
                    }
                } catch (e: Exception) {
                    FileLogger.logError("Standalone extractor exception", e, TAG)
                    Log.e(TAG, "❌ Standalone extractor exception", e)
                    return@withContext null
                }
            }

            // For 480p+ ONLY, use embed player
            FileLogger.log("🔧 Using YouTube Embed Player for $qualityMode...", TAG)
            Log.d(TAG, "🔧 Using YouTube Embed Player for $qualityMode...")
            
            try {
                val embedResult = embedExtractor.extractStream(sourceUrl)
                if (embedResult != null) {
                    FileLogger.log("✅ YouTube Embed extraction succeeded", TAG)
                    FileLogger.log("📺 Embed URL: ${embedResult.first}", TAG)
                    Log.d(TAG, "✅ YouTube Embed extraction succeeded")
                    saveToCache(sourceUrl, embedResult.first, "youtube")
                    return@withContext embedResult.first
                } else {
                    FileLogger.log("❌ YouTube Embed extraction failed", TAG)
                    Log.e(TAG, "❌ YouTube Embed extraction failed")
                }
            } catch (e: Exception) {
                FileLogger.logError("YouTube Embed extraction exception", e, TAG)
                Log.e(TAG, "❌ YouTube Embed extraction exception", e)
            }

            // Fallback to NewPipe
            try {
                FileLogger.log("🔄 Trying NewPipe as fallback...", TAG)
                Log.d(TAG, "🔄 Trying NewPipe as fallback...")
                NewPipe.init(DownloaderImpl())
                val info = StreamInfo.getInfo(sourceUrl)
                val extractedUrl = info.hlsUrl

                if (extractedUrl != null) {
                    FileLogger.log("✅ NewPipe extraction succeeded", TAG)
                    FileLogger.log("📺 Stream URL: $extractedUrl", TAG)
                    Log.d(TAG, "✅ NewPipe extraction succeeded")
                    saveToCache(sourceUrl, extractedUrl, "youtube")
                    return@withContext extractedUrl
                } else {
                    FileLogger.log("❌ NewPipe returned null HLS URL", TAG)
                    Log.e(TAG, "❌ NewPipe returned null HLS URL")
                }
            } catch (e: Exception) {
                FileLogger.logError("NewPipe extraction exception", e, TAG)
                Log.e(TAG, "❌ NewPipe extraction exception", e)
            }

            // Both methods failed
            FileLogger.log("❌ All YouTube extraction methods failed for: $sourceUrl", TAG)
            Log.e(TAG, "❌ All YouTube extraction methods failed")
            null
        } catch (e: Exception) {
            FileLogger.logError("Extraction failed", e, TAG)
            Log.e(TAG, "Extraction failed: ${e.message}", e)
            null
        }
    }

    suspend fun extractRutubeUrl(rutubeUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val videoId = extractRutubeVideoId(rutubeUrl) ?: return@withContext null

            val apiUrl = "https://rutube.ru/api/play/options/$videoId/?no_404=true&referer=https%3A%2F%2Frutube.ru"
            val request = Request.Builder()
                .url(apiUrl)
                .addHeader("Referer", "https://rutube.ru")
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Rutube API failed: ${response.code}")
                return@withContext null
            }

            val json = response.body?.string()
            if (json.isNullOrEmpty()) {
                Log.e(TAG, "Empty Rutube response")
                return@withContext null
            }

            val jsonObject = JSONObject(json)
            val videoBalancer = jsonObject.optJSONObject("video_balancer")
                ?: return@withContext null

            val m3u8Url = videoBalancer.optString("m3u8")
                .ifEmpty { videoBalancer.optString("default") }

            if (m3u8Url.isEmpty()) {
                Log.e(TAG, "No M3U8 URL found in Rutube response")
                return@withContext null
            }

            FileLogger.log("📥 Got Rutube master playlist: ${m3u8Url.take(100)}...", TAG)

            // Parse the HLS manifest to get specific quality
            val specificQualityUrl = parseRutubeManifest(m3u8Url)

            if (specificQualityUrl != null) {
                Log.d(TAG, "✅ Rutube quality-specific URL extracted")
                FileLogger.log("✅ Rutube quality-specific URL extracted", TAG)
                specificQualityUrl
            } else {
                Log.d(TAG, "⚠️ Could not parse manifest, using master playlist")
                FileLogger.log("⚠️ Using master playlist (adaptive quality)", TAG)
                m3u8Url
            }

        } catch (e: Exception) {
            Log.e(TAG, "Rutube extraction error", e)
            null
        }
    }

    private suspend fun parseRutubeManifest(masterPlaylistUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            FileLogger.log("📋 Parsing Rutube HLS manifest for quality selection...", TAG)

            val request = Request.Builder()
                .url(masterPlaylistUrl)
                .addHeader("Referer", "https://rutube.ru")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                FileLogger.log("❌ Failed to download manifest: ${response.code}", TAG)
                return@withContext null
            }

            val manifestContent = response.body?.string()
            if (manifestContent.isNullOrEmpty()) {
                FileLogger.log("❌ Empty manifest content", TAG)
                return@withContext null
            }

            // Parse HLS manifest
            val lines = manifestContent.lines()
            val variants = mutableListOf<RutubeVariant>()

            var i = 0
            while (i < lines.size) {
                val line = lines[i].trim()

                // Look for #EXT-X-STREAM-INF lines
                if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    // Extract resolution
                    val resolutionMatch = Regex("RESOLUTION=(\\d+)x(\\d+)").find(line)
                    val bandwidth = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0

                    if (resolutionMatch != null && i + 1 < lines.size) {
                        val width = resolutionMatch.groupValues[1].toInt()
                        val height = resolutionMatch.groupValues[2].toInt()
                        val variantUrl = lines[i + 1].trim()

                        if (variantUrl.isNotEmpty() && !variantUrl.startsWith("#")) {
                            // Make absolute URL if needed
                            val absoluteUrl = if (variantUrl.startsWith("http")) {
                                variantUrl
                            } else {
                                val baseUrl = masterPlaylistUrl.substringBeforeLast("/")
                                "$baseUrl/$variantUrl"
                            }

                            variants.add(RutubeVariant(width, height, bandwidth, absoluteUrl))
                            FileLogger.log("  Found variant: ${width}x${height} @ ${bandwidth/1000}kbps", TAG)
                        }
                    }
                }
                i++
            }

            if (variants.isEmpty()) {
                FileLogger.log("⚠️ No variants found in manifest", TAG)
                return@withContext null
            }

            // Select best matching quality
            val targetHeight = getTargetHeight()
            FileLogger.log("🎯 Target quality: ${targetHeight}p", TAG)

            // Sort by closest to target height, then by bandwidth
            val bestVariant = variants
                .sortedWith(compareBy(
                    { kotlin.math.abs(it.height - targetHeight) },  // Closest to target
                    { -it.bandwidth }  // Higher bandwidth if tie
                ))
                .firstOrNull()

            if (bestVariant != null) {
                FileLogger.log("✅ Selected: ${bestVariant.width}x${bestVariant.height} @ ${bestVariant.bandwidth/1000}kbps", TAG)
                bestVariant.url
            } else {
                null
            }

        } catch (e: Exception) {
            FileLogger.log("❌ Error parsing manifest: ${e.message}", TAG)
            Log.e(TAG, "Error parsing Rutube manifest", e)
            null
        }
    }

    private data class RutubeVariant(
        val width: Int,
        val height: Int,
        val bandwidth: Int,
        val url: String
    )

    private fun extractRutubeVideoId(url: String): String? {
        val regex = "rutube\\.ru/video/([a-f0-9]+)".toRegex(RegexOption.IGNORE_CASE)
        return regex.find(url)?.groupValues?.get(1)
    }

    private fun saveToCache(originalUrl: String, extractedUrl: String, urlType: String) {
        val qualityMode = getQualityMode()
        cachePrefs.edit()
            .putString(KEY_ORIGINAL_URL, originalUrl)
            .putString(KEY_EXTRACTED_URL, extractedUrl)
            .putString(KEY_URL_TYPE, urlType)
            .putString(KEY_QUALITY_MODE, qualityMode)
            .apply()

        FileLogger.log("💾 Cached URL with quality mode: $qualityMode", TAG)
    }

    fun getCachedUrl(): String? {
        val currentQuality = getQualityMode()
        val cachedQuality = cachePrefs.getString(KEY_QUALITY_MODE, null)

        // Only use cache if quality mode matches
        return if (currentQuality == cachedQuality) {
            val cachedUrl = cachePrefs.getString(KEY_EXTRACTED_URL, null)
            if (cachedUrl != null) {
                FileLogger.log("✅ Cache hit - quality mode matches: $currentQuality", TAG)
            } else {
                FileLogger.log("⚠️ Cache miss - no extracted URL stored (was invalidated?)", TAG)
            }
            cachedUrl
        } else {
            FileLogger.log("⚠️ Cache miss - quality changed from $cachedQuality to $currentQuality", TAG)
            null // Force re-extraction if quality changed
        }
    }

    fun getCachedOriginalUrl(): String? = cachePrefs.getString(KEY_ORIGINAL_URL, null)
    fun getCachedUrlType(): String? = cachePrefs.getString(KEY_URL_TYPE, null)
}

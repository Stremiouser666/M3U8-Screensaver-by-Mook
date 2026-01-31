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

    fun needsExtraction(url: String): Boolean {
        return !url.contains(".m3u8") &&
                (url.contains("youtube.com") || url.contains("youtu.be") || url.contains("rutube.ru"))
    }

    private fun isNetworkAvailable(): Boolean {
        try {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
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

    private fun isProgressiveMode(): Boolean {
        return getQualityMode() == "360_progressive"
    }

    suspend fun extractStreamUrl(sourceUrl: String, forceRefresh: Boolean, cacheExpirationSeconds: Long): String? =
        withContext(Dispatchers.IO) {
            try {
                FileLogger.log("🎬 Starting extraction for: $sourceUrl", TAG)

                if (!isNetworkAvailable()) {
                    FileLogger.log("📵 No network - using cached URL", TAG)
                    return@withContext cachePrefs.getString(KEY_EXTRACTED_URL, null) ?: sourceUrl
                }

                if (sourceUrl.contains("rutube.ru", ignoreCase = true)) {
                    FileLogger.log("🎬 Extracting Rutube URL...", TAG)
                    val extractedUrl = extractRutubeUrl(sourceUrl)
                    if (extractedUrl != null) saveToCache(sourceUrl, extractedUrl, "rutube")
                    return@withContext extractedUrl
                }

                FileLogger.log("🎬 Extracting YouTube URL...", TAG)

                // Standalone extractor first
                try {
                    FileLogger.log("🔧 Trying standalone extractor...", TAG)
                    val result = standaloneExtractor.extractStream(sourceUrl, isProgressiveMode())
                    if (result.success && result.streamUrl != null) {
                        FileLogger.log("✅ Standalone extractor succeeded: ${result.quality}", TAG)
                        saveToCache(sourceUrl, result.streamUrl, "youtube")
                        return@withContext result.streamUrl
                    } else {
                        FileLogger.log("⚠️ Standalone extractor failed: ${result.errorMessage}", TAG)
                    }
                } catch (e: Exception) {
                    FileLogger.logError("Standalone extractor exception", e, TAG)
                }

                // Fallback NewPipe
                try {
                    FileLogger.log("🔄 Trying NewPipe fallback...", TAG)
                    NewPipe.init(DownloaderImpl())
                    val info = StreamInfo.getInfo(sourceUrl)

                    val streams = if (isProgressiveMode()) {
                        info.streams.filter { it.isProgressive && it.videoHeight == getTargetHeight() }
                    } else {
                        info.streams.filter { !it.isAudioOnly && it.videoHeight == getTargetHeight() }
                    }

                    val selectedStream = streams.maxByOrNull { it.bitrate }

                    selectedStream?.let {
                        FileLogger.log("✅ Selected stream: ${it.videoHeight}p, progressive=${it.isProgressive}", TAG)
                        saveToCache(sourceUrl, it.url, "youtube")
                        return@withContext it.url
                    } ?: run {
                        FileLogger.log("⚠️ No suitable stream found, using default HLS", TAG)
                        saveToCache(sourceUrl, info.hlsUrl ?: sourceUrl, "youtube")
                        return@withContext info.hlsUrl
                    }

                } catch (e: Exception) {
                    FileLogger.logError("NewPipe fallback exception", e, TAG)
                }

                FileLogger.log("❌ All YouTube extraction failed for $sourceUrl", TAG)
                null

            } catch (e: Exception) {
                FileLogger.logError("Extraction failed", e, TAG)
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
            if (!response.isSuccessful) return@withContext null

            val json = response.body?.string() ?: return@withContext null
            val jsonObject = JSONObject(json)
            val videoBalancer = jsonObject.optJSONObject("video_balancer") ?: return@withContext null
            val m3u8Url = videoBalancer.optString("m3u8").ifEmpty { videoBalancer.optString("default") }
            if (m3u8Url.isEmpty()) return@withContext null

            // Parse manifest for target quality
            parseRutubeManifest(m3u8Url)

        } catch (e: Exception) {
            Log.e(TAG, "Rutube extraction error", e)
            null
        }
    }

    private suspend fun parseRutubeManifest(masterPlaylistUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(masterPlaylistUrl).addHeader("Referer", "https://rutube.ru").build()
            val response = httpClient.newCall(request).execute()
            val content = response.body?.string() ?: return@withContext null

            val lines = content.lines()
            val variants = mutableListOf<RutubeVariant>()

            var i = 0
            while (i < lines.size) {
                val line = lines[i].trim()
                if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    val resMatch = Regex("RESOLUTION=(\\d+)x(\\d+)").find(line)
                    val bandwidth = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    if (resMatch != null && i + 1 < lines.size) {
                        val height = resMatch.groupValues[2].toInt()
                        val url = lines[i + 1].trim()
                        val absUrl = if (url.startsWith("http")) url else "${masterPlaylistUrl.substringBeforeLast("/")}/$url"
                        variants.add(RutubeVariant(resMatch.groupValues[1].toInt(), height, bandwidth, absUrl))
                    }
                }
                i++
            }

            if (variants.isEmpty()) return@withContext masterPlaylistUrl

            val targetHeight = getTargetHeight()
            val selected = variants
                .sortedWith(compareBy({ kotlin.math.abs(it.height - targetHeight) }, { -it.bandwidth }))
                .firstOrNull()

            selected?.url ?: masterPlaylistUrl

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Rutube manifest", e)
            masterPlaylistUrl
        }
    }

    private data class RutubeVariant(val width: Int, val height: Int, val bandwidth: Int, val url: String)

    private fun extractRutubeVideoId(url: String): String? =
        "rutube\\.ru/video/([a-f0-9]+)".toRegex(RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)

    private fun saveToCache(originalUrl: String, extractedUrl: String, urlType: String) {
        val qualityMode = getQualityMode()
        cachePrefs.edit()
            .putString(KEY_ORIGINAL_URL, originalUrl)
            .putString(KEY_EXTRACTED_URL, extractedUrl)
            .putString(KEY_URL_TYPE, urlType)
            .putString(KEY_QUALITY_MODE, qualityMode)
            .apply()
        FileLogger.log("💾 Cached URL ($qualityMode): $extractedUrl", TAG)
    }

    fun getCachedUrl(): String? {
        val currentQuality = getQualityMode()
        val cachedQuality = cachePrefs.getString(KEY_QUALITY_MODE, null)
        return if (currentQuality == cachedQuality) cachePrefs.getString(KEY_EXTRACTED_URL, null) else null
    }

    fun getCachedOriginalUrl(): String? = cachePrefs.getString(KEY_ORIGINAL_URL, null)
    fun getCachedUrlType(): String? = cachePrefs.getString(KEY_URL_TYPE, null)
}
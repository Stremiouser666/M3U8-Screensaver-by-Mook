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
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking network availability", e)
            return false
        }
    }

    suspend fun extractStreamUrl(sourceUrl: String, forceRefresh: Boolean, cacheExpirationSeconds: Long): String? = withContext(Dispatchers.IO) {
        try {
            FileLogger.log("🎬 Starting extraction for: $sourceUrl", TAG)
            
            if (!isNetworkAvailable()) {
                FileLogger.log("📵 No network available - using cached URL", TAG)
                Log.w(TAG, "📵 No network available - using cached URL")
                return@withContext cachePrefs.getString(KEY_EXTRACTED_URL, null) ?: sourceUrl
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
            
            // Try standalone extractor first
            try {
                FileLogger.log("🔧 Trying standalone extractor...", TAG)
                val standaloneResult = standaloneExtractor.extractStream(sourceUrl)
                if (standaloneResult.success && standaloneResult.streamUrl != null) {
                    FileLogger.log("✅ Standalone extractor succeeded: ${standaloneResult.quality}", TAG)
                    FileLogger.log("📺 Stream URL: ${standaloneResult.streamUrl}", TAG)
                    Log.d(TAG, "✅ Standalone extractor succeeded: ${standaloneResult.quality}")
                    saveToCache(sourceUrl, standaloneResult.streamUrl, "youtube")
                    return@withContext standaloneResult.streamUrl
                } else {
                    FileLogger.log("⚠️ Standalone extractor failed: ${standaloneResult.errorMessage}", TAG)
                    Log.w(TAG, "⚠️ Standalone extractor failed: ${standaloneResult.errorMessage}")
                }
            } catch (e: Exception) {
                FileLogger.logError("Standalone extractor exception", e, TAG)
                Log.e(TAG, "❌ Standalone extractor exception", e)
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

            if (m3u8Url.isNotEmpty()) {
                Log.d(TAG, "✅ Rutube M3U8 extracted successfully")
            } else {
                Log.e(TAG, "No M3U8 URL found in Rutube response")
            }

            m3u8Url.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.e(TAG, "Rutube extraction error", e)
            null
        }
    }

    private fun extractRutubeVideoId(url: String): String? {
        val regex = "rutube\\.ru/video/([a-f0-9]+)".toRegex(RegexOption.IGNORE_CASE)
        return regex.find(url)?.groupValues?.get(1)
    }

    private fun saveToCache(originalUrl: String, extractedUrl: String, urlType: String) {
        cachePrefs.edit()
            .putString(KEY_ORIGINAL_URL, originalUrl)
            .putString(KEY_EXTRACTED_URL, extractedUrl)
            .putString(KEY_URL_TYPE, urlType)
            .apply()
    }

    fun getCachedUrl(): String? = cachePrefs.getString(KEY_EXTRACTED_URL, null)
    fun getCachedOriginalUrl(): String? = cachePrefs.getString(KEY_ORIGINAL_URL, null)
    fun getCachedUrlType(): String? = cachePrefs.getString(KEY_URL_TYPE, null)
}
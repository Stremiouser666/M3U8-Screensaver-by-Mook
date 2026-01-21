package com.livescreensaver.tv

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class YouTubeStandaloneExtractor(private val context: Context) {

    private val TAG = "YTExtractor"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * STEP 1: Download watch page HTML
     */
    suspend fun fetchWatchHtml(videoId: String): String? = withContext(Dispatchers.IO) {
        try {
            val url = "https://www.youtube.com/watch?v=$videoId"
            Log.d(TAG, "🌐 Fetching watch page")

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.string()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Watch page fetch failed", e)
            null
        }
    }

    /**
     * STEP 2: Extract /s/player/.../base.js from HTML
     */
    fun extractPlayerJsPath(html: String): String? {
        val pattern = Pattern.compile("/s/player/[^\"']+?/base\\.js")
        val matcher = pattern.matcher(html)
        return if (matcher.find()) {
            matcher.group()
        } else null
    }

    /**
     * STEP 3: Download and cache player JS
     */
    suspend fun getPlayerJs(videoId: String): String? = withContext(Dispatchers.IO) {
        try {
            val html = fetchWatchHtml(videoId) ?: return@withContext null
            val jsPath = extractPlayerJsPath(html) ?: return@withContext null

            val fullUrl = "https://www.youtube.com$jsPath"
            Log.d(TAG, "🎮 Player JS: $fullUrl")

            val cacheFile = File(context.cacheDir, "yt_player_base.js")

            // Reuse cached version if present
            if (cacheFile.exists() && cacheFile.length() > 500_000) {
                Log.d(TAG, "📦 Using cached player JS")
                return@withContext cacheFile.readText()
            }

            val request = Request.Builder()
                .url(fullUrl)
                .header("User-Agent", "Mozilla/5.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val js = response.body?.string()
                if (!js.isNullOrEmpty()) {
                    cacheFile.writeText(js)
                }
                js
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Player JS fetch failed", e)
            null
        }
    }
}
package com.livescreensaver.tv

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Simplified YouTube extractor - ONLY handles 360p progressive (itag=18).
 * 
 * The 360p progressive format (itag=18) is a single muxed video+audio file
 * that usually comes with a direct URL (no signature cipher).
 * 
 * For anything higher quality (480p+), use YouTubeEmbedExtractor instead.
 */
class YouTubeStandaloneExtractor(
    private val context: Context,
    httpClient: OkHttpClient? = null
) {

    companion object {
        private const val TAG = "YouTubeExtractor"
        private const val ANDROID_API_KEY = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w"
        private const val WEB_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        private const val PLAYER_ENDPOINT = "https://www.youtube.com/youtubei/v1/player"
    }

    private val httpClient = httpClient ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class ExtractionResult(
        val success: Boolean,
        val streamUrl: String? = null,
        val quality: String? = null,
        val errorMessage: String? = null
    )

    /**
     * Extract 360p progressive stream ONLY.
     * Returns null if not available - caller should use YouTubeEmbedExtractor instead.
     */
    suspend fun extractStream(youtubeUrl: String): ExtractionResult = withContext(Dispatchers.IO) {
        try {
            debugLog("==== YouTube 360p Extraction Started ====")
            debugLog("Input URL: $youtubeUrl")

            val videoId = extractVideoId(youtubeUrl)

            if (videoId == null) {
                debugLog("❌ Failed to extract video ID from URL")
                return@withContext ExtractionResult(
                    success = false,
                    errorMessage = "Could not extract video ID from YouTube URL"
                )
            }

            debugLog("✓ Extracted video ID: $videoId")

            // Try ANDROID_VR client first
            debugLog(">>> Attempting Method 1: Android VR InnerTube API")
            val androidVrResult = tryInnerTubeExtraction(
                videoId,
                clientName = "ANDROID_VR",
                clientVersion = "1.60.19",
                clientId = "28",
                apiKey = ANDROID_API_KEY,
                androidSdkVersion = 11
            )

            if (androidVrResult != null) {
                debugLog("✅ SUCCESS via Android VR InnerTube!")
                return@withContext ExtractionResult(
                    success = true,
                    streamUrl = androidVrResult.first,
                    quality = androidVrResult.second
                )
            }

            debugLog("⚠️ Android VR client failed, trying Web client...")

            // Try WEB client as fallback
            debugLog(">>> Attempting Method 2: Web InnerTube API")
            val webResult = tryInnerTubeExtraction(
                videoId,
                clientName = "WEB",
                clientVersion = "2.20240304.00.00",
                clientId = "1",
                apiKey = WEB_API_KEY,
                androidSdkVersion = null
            )

            if (webResult != null) {
                debugLog("✅ SUCCESS via Web InnerTube!")
                return@withContext ExtractionResult(
                    success = true,
                    streamUrl = webResult.first,
                    quality = webResult.second
                )
            }

            debugLog("❌ All extraction methods failed for 360p")
            ExtractionResult(
                success = false,
                errorMessage = "360p progressive format not available"
            )

        } catch (e: Exception) {
            debugLog("❌ Exception: ${e.message}")
            Log.e(TAG, "YouTube extraction error", e)
            ExtractionResult(
                success = false,
                errorMessage = "Exception: ${e.message}"
            )
        }
    }

    private suspend fun tryInnerTubeExtraction(
        videoId: String,
        clientName: String,
        clientVersion: String,
        clientId: String,
        apiKey: String,
        androidSdkVersion: Int?
    ): Pair<String, String>? = withContext(Dispatchers.IO) {
        try {
            debugLog("Building $clientName client request...")

            val clientContext = JSONObject().apply {
                put("clientName", clientName)
                put("clientVersion", clientVersion)
                put("hl", "en")
                put("gl", "US")
                put("utcOffsetMinutes", 0)

                if (androidSdkVersion != null) {
                    put("androidSdkVersion", androidSdkVersion)
                    put("osName", "Android")
                    put("osVersion", "11")
                }
            }

            val contextJson = JSONObject().apply {
                put("client", clientContext)
            }

            val requestBody = JSONObject().apply {
                put("videoId", videoId)
                put("context", contextJson)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }

            val userAgent = when (clientName) {
                "ANDROID_VR" -> "com.google.android.apps.youtube.vr.oculus/$clientVersion (Linux; U; Android 11) gzip"
                else -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
            }

            val request = Request.Builder()
                .url("$PLAYER_ENDPOINT?key=$apiKey")
                .post(requestBody.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", userAgent)
                .addHeader("X-YouTube-Client-Name", clientId)
                .addHeader("X-YouTube-Client-Version", clientVersion)
                .build()

            debugLog("Sending $clientName API request...")
            val response = httpClient.newCall(request).execute()
            debugLog("Response code: ${response.code}")

            if (!response.isSuccessful) {
                debugLog("❌ API request failed with HTTP ${response.code}")
                return@withContext null
            }

            val json = response.body?.string()
            if (json.isNullOrEmpty()) {
                debugLog("❌ Empty response body")
                return@withContext null
            }

            val jsonObject = JSONObject(json)

            // Check playability status
            if (jsonObject.has("playabilityStatus")) {
                val playability = jsonObject.getJSONObject("playabilityStatus")
                val status = playability.optString("status", "UNKNOWN")
                val reason = playability.optString("reason", "No reason provided")

                debugLog("Playability status: $status")
                if (status != "OK") {
                    debugLog("Reason: $reason")
                    return@withContext null
                }
            }

            // Parse streaming data - look for 360p progressive ONLY
            parseStreamingData(jsonObject)

        } catch (e: Exception) {
            debugLog("❌ $clientName exception: ${e.message}")
            null
        }
    }

    private fun parseStreamingData(jsonObject: JSONObject): Pair<String, String>? {
        try {
            if (!jsonObject.has("streamingData")) {
                debugLog("⚠️ No streamingData in response")
                return null
            }

            val streamingData = jsonObject.getJSONObject("streamingData")
            
            debugLog("--- Searching for 360p progressive format ---")
            
            return tryProgressiveFormat(streamingData)

        } catch (e: Exception) {
            debugLog("❌ Error parsing streamingData: ${e.message}")
            return null
        }
    }

    private fun tryProgressiveFormat(streamingData: JSONObject): Pair<String, String>? {
        debugLog("Attempting to find 360p progressive format (itag=18) with direct URL...")

        val formats = streamingData.optJSONArray("formats") ?: return null

        for (i in 0 until formats.length()) {
            val format = formats.getJSONObject(i)
            
            // We ONLY want formats with direct URLs (no signatureCipher)
            val url = format.optString("url", "")
            
            if (url.isEmpty()) {
                // Has signatureCipher - skip it, we're not doing decryption anymore
                continue
            }

            val itag = format.optInt("itag", 0)
            val height = format.optInt("height", 0)
            val mimeType = format.optString("mimeType", "")
            val hasAudio = format.has("audioQuality") || mimeType.contains("mp4a")

            // Look for itag=18 (360p progressive) with audio and direct URL
            if (itag == 18 || (height == 360 && hasAudio && mimeType.contains("video"))) {
                debugLog("✅ Found 360p progressive format with direct URL (itag=$itag)")
                debugLog("📹 URL: ${url.take(100)}...")
                return Pair(url, "360p progressive (video+audio)")
            }
        }

        debugLog("⚠️ No 360p progressive format with direct URL found")
        return null
    }

    private fun extractVideoId(url: String): String? {
        val patterns = listOf(
            "(?:youtube\\.com/watch\\?v=|youtu\\.be/|youtube\\.com/embed/|m\\.youtube\\.com/watch\\?v=)([a-zA-Z0-9_-]{11})",
            "v=([a-zA-Z0-9_-]{11})"
        )

        for (patternStr in patterns) {
            val pattern = Pattern.compile(patternStr)
            val matcher = pattern.matcher(url)
            if (matcher.find()) {
                return matcher.group(1)
            }
        }
        return null
    }

    private fun debugLog(message: String) {
        Log.d(TAG, message)
        FileLogger.log(message, TAG)

        try {
            val file = File(context.getExternalFilesDir(null), "youtube_extraction_log.txt")
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            file.appendText("[$timestamp] $message\n")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write debug log", e)
        }
    }
}

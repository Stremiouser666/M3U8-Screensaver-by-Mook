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
        val videoUrl: String? = null,
        val audioUrl: String? = null,
        val quality: String? = null,
        val hasAudio: Boolean = false,
        val isLive: Boolean = false,
        val errorMessage: String? = null
    )

    suspend fun extractStream(youtubeUrl: String): ExtractionResult = withContext(Dispatchers.IO) {
        try {
            debugLog("==== YouTube Extraction Started ====")
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

            // Try WEB client FIRST (better quality options including DASH/HLS)
            debugLog(">>> Attempting Method 1: Web InnerTube API")
            val webResult = tryInnerTubeExtraction(
                videoId,
                clientName = "WEB",
                clientVersion = "2.20240304.00.00",
                apiKey = WEB_API_KEY,
                androidSdkVersion = null
            )

            if (webResult != null) {
                debugLog("✅ SUCCESS via Web InnerTube!")
                return@withContext ExtractionResult(
                    success = true,
                    streamUrl = webResult.first,
                    quality = webResult.second,
                    hasAudio = true
                )
            }

            debugLog("⚠️ Web InnerTube failed, trying Android InnerTube...")

            // Fallback to Android client
            debugLog(">>> Attempting Method 2: Android InnerTube API (fallback)")
            val androidResult = tryInnerTubeExtraction(
                videoId,
                clientName = "ANDROID",
                clientVersion = "19.09.37",
                apiKey = ANDROID_API_KEY,
                androidSdkVersion = 33
            )

            if (androidResult != null) {
                debugLog("✅ SUCCESS via Android InnerTube!")
                return@withContext ExtractionResult(
                    success = true,
                    streamUrl = androidResult.first,
                    quality = androidResult.second,
                    hasAudio = true
                )
            }

            debugLog("❌ All extraction methods failed")
            ExtractionResult(
                success = false,
                errorMessage = "All InnerTube methods failed. Video may be age-restricted or geo-blocked."
            )

        } catch (e: Exception) {
            debugLog("❌ Exception: ${e.message}")
            debugLog("Stack trace: ${e.stackTraceToString()}")
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
                put("params", "8AEB")  // Request all formats including manifests
            }

            val userAgent = if (clientName == "ANDROID") {
                "com.google.android.youtube/$clientVersion (Linux; U; Android 13) gzip"
            } else {
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
            }

            val requestBuilder = Request.Builder()
                .url("$PLAYER_ENDPOINT?key=$apiKey")
                .post(requestBody.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", userAgent)
                .addHeader("X-YouTube-Client-Name", if (clientName == "ANDROID") "3" else "1")
                .addHeader("X-YouTube-Client-Version", clientVersion)

            // Add additional headers for Web client
            if (clientName == "WEB") {
                requestBuilder
                    .addHeader("Origin", "https://www.youtube.com")
                    .addHeader("Referer", "https://www.youtube.com/watch?v=$videoId")
            }

            val request = requestBuilder.build()

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

            // Parse streaming data
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
            debugLog("--- Analyzing streaming data ---")

            // Priority 1: HLS manifest (best for live streams, adaptive up to 1080p+)
            val hlsUrl = streamingData.optString("hlsManifestUrl", "")
            if (hlsUrl.isNotEmpty()) {
                debugLog("✓ Found HLS manifest URL (adaptive, live streaming)")
                debugLog("HLS URL: ${hlsUrl.take(100)}...")
                return Pair(hlsUrl, "HLS manifest")
            }

            // Priority 2: DASH manifest (adaptive streaming, up to 4K)
            val dashUrl = streamingData.optString("dashManifestUrl", "")
            if (dashUrl.isNotEmpty()) {
                debugLog("✓ Found DASH manifest URL (adaptive, up to 4K)")
                debugLog("DASH URL: ${dashUrl.take(100)}...")
                return Pair(dashUrl, "DASH manifest")
            }

            // Priority 3: Adaptive formats (video-only, highest quality)
            val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
            if (adaptiveFormats != null && adaptiveFormats.length() > 0) {
                debugLog("Checking ${adaptiveFormats.length()} adaptive formats...")
                
                var bestVideoUrl: String? = null
                var bestHeight = 0
                var bestQuality = ""

                for (i in 0 until adaptiveFormats.length()) {
                    val format = adaptiveFormats.getJSONObject(i)
                    val mimeType = format.optString("mimeType", "")
                    
                    if (mimeType.startsWith("video/")) {
                        val url = format.optString("url")
                        val height = format.optInt("height", 0)
                        val width = format.optInt("width", 0)
                        val quality = format.optString("qualityLabel", "${width}x${height}")
                        val bitrate = format.optInt("bitrate", 0)
                        
                        debugLog("  - Adaptive video: $quality @ ${bitrate/1000}kbps (${mimeType})")
                        
                        if (url.isNotEmpty() && height > bestHeight) {
                            bestVideoUrl = url
                            bestHeight = height
                            bestQuality = quality
                        }
                    }
                }

                if (bestVideoUrl != null) {
                    debugLog("✓ Selected best adaptive format: $bestQuality")
                    debugLog("⚠️ Note: This is video-only, may need DASH manifest for audio")
                    debugLog("Adaptive URL: ${bestVideoUrl.take(100)}...")
                    return Pair(bestVideoUrl, "Adaptive $bestQuality (video-only)")
                }
            }

            // Priority 4: Progressive formats (combined video+audio, typically max 720p)
            val formats = streamingData.optJSONArray("formats")
            if (formats != null && formats.length() > 0) {
                debugLog("Checking ${formats.length()} progressive formats...")
                
                var bestUrl: String? = null
                var bestHeight = 0
                var bestQuality = ""

                for (i in 0 until formats.length()) {
                    val format = formats.getJSONObject(i)
                    var url = format.optString("url", "")
                    
                    // If no direct URL, check for signatureCipher (YouTube obfuscation)
                    if (url.isEmpty() && format.has("signatureCipher")) {
                        debugLog("⚠️ Format has signatureCipher instead of direct URL (signature decryption needed)")
                        // For now, skip these formats - they require signature decryption
                        continue
                    }
                    
                    val height = format.optInt("height", 0)
                    val width = format.optInt("width", 0)
                    val quality = format.optString("qualityLabel", "${width}x${height}")
                    val mimeType = format.optString("mimeType", "")
                    
                    debugLog("  - Progressive: $quality (${mimeType}) [hasURL: ${url.isNotEmpty()}]")

                    // Progressive formats have both video AND audio in mimeType
                    if (url.isNotEmpty() && mimeType.contains("video")) {
                        if (height > bestHeight) {
                            bestUrl = url
                            bestHeight = height
                            bestQuality = quality
                            debugLog("    → New best: $quality")
                        }
                    }
                }

                if (!bestUrl.isNullOrEmpty()) {
                    debugLog("✓ Found progressive format: $bestQuality (video+audio combined)")
                    debugLog("Progressive URL: ${bestUrl.take(100)}...")
                    return Pair(bestUrl, "Progressive $bestQuality")
                } else {
                    debugLog("⚠️ No valid progressive URL found")
                }
            }

            debugLog("❌ No playable formats found in response")
            return null

        } catch (e: Exception) {
            debugLog("❌ Error parsing streamingData: ${e.message}")
            return null
        }
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

    fun isYouTubeUrl(url: String): Boolean {
        return url.contains("youtube.com", ignoreCase = true) ||
               url.contains("youtu.be", ignoreCase = true)
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
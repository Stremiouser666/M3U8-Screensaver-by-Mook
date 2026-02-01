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
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class YouTubeStandaloneExtractor(
    private val context: Context,
    httpClient: OkHttpClient? = null
) {

    companion object {
        private const val TAG = "YouTubeExtractor"
        private const val TV_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        private const val ANDROID_API_KEY = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w"
        private const val WEB_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        private const val PLAYER_ENDPOINT = "https://www.youtube.com/youtubei/v1/player"
    }

    private val httpClient = httpClient ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val signatureDecryptor = YouTubeSignatureDecryptor(context, this.httpClient)

    /**
     * Extract YouTube stream URL for 360p progressive format
     * Returns the direct video URL or null if extraction fails
     */
    suspend fun extract360pUrl(youtubeUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            debugLog("==== YouTube 360p Extraction Started ====")
            debugLog("Input URL: $youtubeUrl")

            val videoId = extractVideoId(youtubeUrl)
            if (videoId == null) {
                debugLog("❌ Failed to extract video ID")
                return@withContext null
            }

            debugLog("✓ Extracted video ID: $videoId")

            // Method 1: TV Client (best for avoiding bot detection)
            debugLog(">>> Attempting Method 1: TV Client (TVHTML5_SIMPLY_EMBEDDED_PLAYER)")
            val tvResult = tryTVClient(videoId)
            if (tvResult != null) {
                debugLog("✅ SUCCESS via TV Client!")
                return@withContext tvResult
            }

            debugLog("⚠️ TV client failed, trying Android client...")

            // Method 2: Android Client
            debugLog(">>> Attempting Method 2: Android Client")
            val androidResult = tryAndroidClient(videoId)
            if (androidResult != null) {
                debugLog("✅ SUCCESS via Android Client!")
                return@withContext androidResult
            }

            debugLog("⚠️ Android client failed, trying Web client...")

            // Method 3: Web Client
            debugLog(">>> Attempting Method 3: Web Client")
            val webResult = tryWebClient(videoId)
            if (webResult != null) {
                debugLog("✅ SUCCESS via Web Client!")
                return@withContext webResult
            }

            debugLog("❌ All extraction methods failed for 360p")
            null

        } catch (e: Exception) {
            debugLog("❌ Exception: ${e.message}")
            Log.e(TAG, "360p extraction error", e)
            null
        }
    }

    private suspend fun tryTVClient(videoId: String): String? = withContext(Dispatchers.IO) {
        try {
            debugLog("Building TVHTML5_SIMPLY_EMBEDDED_PLAYER request...")

            val requestBody = JSONObject().apply {
                put("videoId", videoId)
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "TVHTML5_SIMPLY_EMBEDDED_PLAYER")
                        put("clientVersion", "2.0")
                        put("hl", "en")
                        put("gl", "US")
                        put("clientScreen", "EMBED")
                    })
                    put("thirdParty", JSONObject().apply {
                        put("embedUrl", "https://www.youtube.com/watch?v=$videoId")
                    })
                })
                put("playbackContext", JSONObject().apply {
                    put("contentPlaybackContext", JSONObject().apply {
                        put("html5Preference", "HTML5_PREF_WANTS")
                        put("signatureTimestamp", 20458)
                    })
                })
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }

            val request = Request.Builder()
                .url("$PLAYER_ENDPOINT?key=$TV_API_KEY")
                .post(requestBody.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version")
                .addHeader("X-YouTube-Client-Name", "85")
                .addHeader("X-YouTube-Client-Version", "2.0")
                .addHeader("Origin", "https://www.youtube.com")
                .addHeader("Referer", "https://www.youtube.com/watch?v=$videoId")
                .build()

            debugLog("Sending TV API request...")
            val response = httpClient.newCall(request).execute()
            debugLog("Response code: ${response.code}")

            if (!response.isSuccessful) {
                debugLog("❌ TV client request failed")
                return@withContext null
            }

            val json = response.body?.string()
            if (json.isNullOrEmpty()) {
                debugLog("❌ Empty response")
                return@withContext null
            }

            parseStreamUrl(JSONObject(json), videoId)

        } catch (e: Exception) {
            debugLog("❌ TV client exception: ${e.message}")
            null
        }
    }

    private suspend fun tryAndroidClient(videoId: String): String? = withContext(Dispatchers.IO) {
        try {
            debugLog("Building ANDROID client request...")

            val requestBody = JSONObject().apply {
                put("videoId", videoId)
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "ANDROID")
                        put("clientVersion", "19.09.37")
                        put("hl", "en")
                        put("gl", "US")
                        put("androidSdkVersion", 30)
                        put("osName", "Android")
                        put("osVersion", "11")
                    })
                })
                put("playbackContext", JSONObject().apply {
                    put("contentPlaybackContext", JSONObject().apply {
                        put("html5Preference", "HTML5_PREF_WANTS")
                    })
                })
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }

            val request = Request.Builder()
                .url("$PLAYER_ENDPOINT?key=$ANDROID_API_KEY")
                .post(requestBody.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "com.google.android.youtube/19.09.37 (Linux; U; Android 11) gzip")
                .addHeader("X-YouTube-Client-Name", "3")
                .addHeader("X-YouTube-Client-Version", "19.09.37")
                .build()

            debugLog("Sending ANDROID API request...")
            val response = httpClient.newCall(request).execute()
            debugLog("Response code: ${response.code}")

            if (!response.isSuccessful) {
                debugLog("❌ Android client request failed")
                return@withContext null
            }

            val json = response.body?.string()
            if (json.isNullOrEmpty()) {
                debugLog("❌ Empty response")
                return@withContext null
            }

            parseStreamUrl(JSONObject(json), videoId)

        } catch (e: Exception) {
            debugLog("❌ Android client exception: ${e.message}")
            null
        }
    }

    private suspend fun tryWebClient(videoId: String): String? = withContext(Dispatchers.IO) {
        try {
            debugLog("Building WEB client request...")

            val requestBody = JSONObject().apply {
                put("videoId", videoId)
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB")
                        put("clientVersion", "2.20240304.00.00")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("playbackContext", JSONObject().apply {
                    put("contentPlaybackContext", JSONObject().apply {
                        put("html5Preference", "HTML5_PREF_WANTS")
                    })
                })
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }

            val request = Request.Builder()
                .url("$PLAYER_ENDPOINT?key=$WEB_API_KEY")
                .post(requestBody.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .addHeader("X-YouTube-Client-Name", "1")
                .addHeader("X-YouTube-Client-Version", "2.20240304.00.00")
                .addHeader("Origin", "https://www.youtube.com")
                .addHeader("Referer", "https://www.youtube.com/watch?v=$videoId")
                .build()

            debugLog("Sending WEB API request...")
            val response = httpClient.newCall(request).execute()
            debugLog("Response code: ${response.code}")

            if (!response.isSuccessful) {
                debugLog("❌ Web client request failed")
                return@withContext null
            }

            val json = response.body?.string()
            if (json.isNullOrEmpty()) {
                debugLog("❌ Empty response")
                return@withContext null
            }

            parseStreamUrl(JSONObject(json), videoId)

        } catch (e: Exception) {
            debugLog("❌ Web client exception: ${e.message}")
            null
        }
    }

    private suspend fun parseStreamUrl(jsonObject: JSONObject, videoId: String): String? {
        try {
            // Check playability status
            if (jsonObject.has("playabilityStatus")) {
                val playability = jsonObject.getJSONObject("playabilityStatus")
                val status = playability.optString("status", "UNKNOWN")
                val reason = playability.optString("reason", "No reason provided")

                debugLog("Playability status: $status")
                if (status != "OK") {
                    debugLog("Reason: $reason")
                    return null
                }
            }

            if (!jsonObject.has("streamingData")) {
                debugLog("⚠️ No streamingData in response")
                return null
            }

            val streamingData = jsonObject.getJSONObject("streamingData")

            // Try progressive formats first (360p with audio)
            val progressiveResult = tryProgressiveFormat(streamingData, videoId)
            if (progressiveResult != null) {
                debugLog("✅ Found 360p progressive format")
                return progressiveResult
            }

            debugLog("⚠️ No 360p progressive format found")
            return null

        } catch (e: Exception) {
            debugLog("❌ Error parsing stream URL: ${e.message}")
            return null
        }
    }

    private suspend fun tryProgressiveFormat(streamingData: JSONObject, videoId: String): String? {
        debugLog("Looking for 360p progressive format...")

        val formats = streamingData.optJSONArray("formats") ?: return null
        debugLog("Found ${formats.length()} progressive formats")

        for (i in 0 until formats.length()) {
            val format = formats.getJSONObject(i)
            var url = format.optString("url", "")

            // Handle signature cipher if present
            if (url.isEmpty() && format.has("signatureCipher")) {
                debugLog("Format $i has signatureCipher, attempting decryption...")
                val cipher = format.getString("signatureCipher")
                url = signatureDecryptor.decryptSignature(cipher, videoId) ?: ""

                if (url.isEmpty()) {
                    debugLog("Decryption failed for format $i")
                    continue
                } else {
                    debugLog("✅ Successfully decrypted format $i")
                }
            }

            if (url.isEmpty()) continue

            val height = format.optInt("height", 0)
            val width = format.optInt("width", 0)
            val mimeType = format.optString("mimeType", "")
            val hasAudio = format.has("audioQuality") || mimeType.contains("mp4a")

            debugLog("Format $i: ${width}x${height}, hasAudio=$hasAudio, mimeType=${mimeType.take(50)}")

            // Look for 360p with audio
            if (height == 360 && hasAudio && mimeType.contains("video")) {
                debugLog("✅ Found 360p progressive format with audio!")
                debugLog("📹 URL: ${url.take(100)}...")
                return url
            }
        }

        debugLog("⚠️ 360p progressive format not available")
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
    }
}

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
        private const val TV_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        private const val PLAYER_ENDPOINT = "https://www.youtube.com/youtubei/v1/player"
    }

    private val httpClient = httpClient ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    
    private val signatureDecryptor = YouTubeSignatureDecryptor(context, httpClient)

    data class ExtractionResult(
        val success: Boolean,
        val streamUrl: String? = null,
        val videoUrl: String? = null,
        val audioUrl: String? = null,
        val quality: String? = null,
        val hasAudio: Boolean = false,
        val isLive: Boolean = false,
        val isDashManifest: Boolean = false,
        val errorMessage: String? = null
    )

    data class VideoFormat(
        val url: String,
        val width: Int,
        val height: Int,
        val bitrate: Int,
        val mimeType: String,
        val codecs: String,
        val fps: Int = 30
    )

    data class AudioFormat(
        val url: String,
        val bitrate: Int,
        val mimeType: String,
        val codecs: String,
        val sampleRate: Int = 44100
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

            // Try TV/Android SDK-less client FIRST (bypasses SABR, provides direct URLs)
            debugLog(">>> Attempting Method 1: TV Client (Android SDK-less)")
            val tvResult = tryInnerTubeExtraction(
                videoId,
                clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
                clientVersion = "2.0",
                apiKey = TV_API_KEY,
                androidSdkVersion = null,
                embedUrl = "https://www.youtube.com/watch?v=$videoId"
            )

            if (tvResult != null) {
                debugLog("✅ SUCCESS via TV Client!")
                return@withContext ExtractionResult(
                    success = true,
                    streamUrl = tvResult.first,
                    quality = tvResult.second,
                    hasAudio = true,
                    isDashManifest = tvResult.second.contains("DASH")
                )
            }

            debugLog("⚠️ TV client failed, trying Android client...")

            // Try Android client as fallback
            debugLog(">>> Attempting Method 2: Android InnerTube API")
            val androidResult = tryInnerTubeExtraction(
                videoId,
                clientName = "ANDROID",
                clientVersion = "20.10.38",
                apiKey = ANDROID_API_KEY,
                androidSdkVersion = 11,
                embedUrl = null
            )

            if (androidResult != null) {
                debugLog("✅ SUCCESS via Android InnerTube!")
                return@withContext ExtractionResult(
                    success = true,
                    streamUrl = androidResult.first,
                    quality = androidResult.second,
                    hasAudio = true,
                    isDashManifest = androidResult.second.contains("DASH")
                )
            }

            debugLog("⚠️ Android client failed, trying Web client...")

            // Try WEB client as last resort
            debugLog(">>> Attempting Method 3: Web InnerTube API")
            val webResult = tryInnerTubeExtraction(
                videoId,
                clientName = "WEB",
                clientVersion = "2.20240304.00.00",
                apiKey = WEB_API_KEY,
                androidSdkVersion = null,
                embedUrl = null
            )

            if (webResult != null) {
                debugLog("✅ SUCCESS via Web InnerTube!")
                return@withContext ExtractionResult(
                    success = true,
                    streamUrl = webResult.first,
                    quality = webResult.second,
                    hasAudio = true,
                    isDashManifest = webResult.second.contains("DASH")
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
        androidSdkVersion: Int?,
        embedUrl: String? = null
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
                
                // TV client needs additional fields
                if (clientName.contains("TV")) {
                    put("clientScreen", "EMBED")
                }
            }

            val contextJson = JSONObject().apply {
                put("client", clientContext)
                
                // TV/Embed client needs thirdParty context
                if (clientName.contains("TV") && embedUrl != null) {
                    put("thirdParty", JSONObject().apply {
                        put("embedUrl", embedUrl)
                    })
                }
            }

            val requestBody = JSONObject().apply {
                put("videoId", videoId)
                put("context", contextJson)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
                
                // Add playbackContext with signatureTimestamp (required for direct URLs)
                put("playbackContext", JSONObject().apply {
                    put("contentPlaybackContext", JSONObject().apply {
                        put("html5Preference", "HTML5_PREF_WANTS")
                        put("signatureTimestamp", 20458) // Current player signature timestamp
                    })
                })
            }

            val userAgent = when {
                clientName == "ANDROID" -> "com.google.android.youtube/$clientVersion (Linux; U; Android 11) gzip"
                clientName.contains("TV") -> "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version,gzip(gfe)"
                else -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
            }

            val requestBuilder = Request.Builder()
                .url("$PLAYER_ENDPOINT?key=$apiKey")
                .post(requestBody.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", userAgent)
                .addHeader("X-YouTube-Client-Name", when {
                    clientName == "ANDROID" -> "3"
                    clientName.contains("TV") -> "85"
                    else -> "1"
                })
                .addHeader("X-YouTube-Client-Version", clientVersion)

            // Add additional headers for Web/TV clients
            if (clientName == "WEB" || clientName.contains("TV")) {
                requestBuilder
                    .addHeader("Origin", "https://www.youtube.com")
                    .addHeader("Referer", embedUrl ?: "https://www.youtube.com/watch?v=$videoId")
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

            // Priority 3: Build custom DASH manifest from adaptive formats (1080p capable)
            debugLog("--- Attempting to build custom DASH manifest ---")
            val dashResult = buildCustomDashManifest(streamingData)
            if (dashResult != null) {
                return dashResult
            }

            // Priority 4: Progressive formats (combined video+audio, fallback to 360p-720p)
            val formats = streamingData.optJSONArray("formats")
            if (formats != null && formats.length() > 0) {
                debugLog("Checking ${formats.length()} progressive formats...")
                
                var bestUrl: String? = null
                var bestHeight = 0
                var bestQuality = ""

                for (i in 0 until formats.length()) {
                    val format = formats.getJSONObject(i)
                    var url = format.optString("url", "")
                    
                    // If no direct URL, try to decrypt signatureCipher
                    if (url.isEmpty() && format.has("signatureCipher")) {
                        debugLog("⚠️ Format has signatureCipher, attempting decryption...")
                        val cipher = format.getString("signatureCipher")
                        val videoId = extractVideoId(streamingData.toString()) ?: ""
                        
                        url = kotlinx.coroutines.runBlocking {
                            signatureDecryptor.decryptSignature(cipher, videoId)
                        } ?: ""
                        
                        if (url.isNotEmpty()) {
                            debugLog("✅ Successfully decrypted signatureCipher!")
                        } else {
                            debugLog("❌ Signature decryption failed, skipping format")
                            continue
                        }
                    }
                    
                    val height = format.optInt("height", 0)
                    val width = format.optInt("width", 0)
                    val quality = format.optString("qualityLabel", "${width}x${height}")
                    val mimeType = format.optString("mimeType", "")
                    val hasAudio = format.has("audioQuality") || mimeType.contains("mp4a") || mimeType.contains("opus")
                    
                    debugLog("  - Progressive: $quality (${mimeType}) [URL:${url.isNotEmpty()}] [Audio:$hasAudio]")

                    if (url.isNotEmpty() && hasAudio && mimeType.contains("video")) {
                        if (bestUrl == null || height > bestHeight) {
                            bestUrl = url
                            bestHeight = height
                            bestQuality = if (quality.isEmpty()) "${width}x${height}" else quality
                            debugLog("    → Selected: $bestQuality (has audio)")
                        }
                    }
                }

                if (!bestUrl.isNullOrEmpty()) {
                    debugLog("✓ Using progressive format: $bestQuality (video+audio combined)")
                    debugLog("Progressive URL: ${bestUrl.take(100)}...")
                    return Pair(bestUrl, "Progressive $bestQuality")
                } else {
                    debugLog("⚠️ No progressive format with audio found")
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
        // Try to extract from videoDetails first (from API response)
        try {
            if (url.contains("videoDetails") || url.contains("videoId")) {
                val videoIdPattern = Pattern.compile("\"videoId\"\\s*:\\s*\"([a-zA-Z0-9_-]{11})\"")
                val matcher = videoIdPattern.matcher(url)
                if (matcher.find()) {
                    return matcher.group(1)
                }
            }
        } catch (e: Exception) {
            // Fallthrough to URL extraction
        }
        
        // Extract from URL patterns
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

    private fun buildCustomDashManifest(streamingData: JSONObject): Pair<String, String>? {
        try {
            val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats") ?: return null
            
            debugLog("Building custom DASH manifest from ${adaptiveFormats.length()} adaptive formats...")

            // Extract video ID for signature decryption
            val videoId = extractVideoId(streamingData.toString()) ?: ""

            // Find best video format (prefer H.264/AVC for compatibility, then VP9)
            var bestVideo: VideoFormat? = null
            var bestVideoHeight = 0

            // Find best audio format (prefer AAC/M4A for compatibility, then Opus)
            var bestAudio: AudioFormat? = null
            var bestAudioBitrate = 0

            for (i in 0 until adaptiveFormats.length()) {
                val format = adaptiveFormats.getJSONObject(i)
                var url = format.optString("url", "")
                
                // Try to decrypt signatureCipher if no direct URL
                if (url.isEmpty() && format.has("signatureCipher")) {
                    debugLog("  - Format $i has signatureCipher, attempting decryption...")
                    val cipher = format.getString("signatureCipher")
                    
                    url = kotlinx.coroutines.runBlocking {
                        signatureDecryptor.decryptSignature(cipher, videoId)
                    } ?: ""
                    
                    if (url.isEmpty()) {
                        debugLog("  - Decryption failed for format $i")
                        continue
                    } else {
                        debugLog("  - ✅ Successfully decrypted format $i")
                    }
                }
                
                if (url.isEmpty()) {
                    debugLog("  - Format $i has no URL after decryption attempt")
                    continue
                }
                
                val mimeType = format.optString("mimeType", "")
                if (mimeType.isEmpty()) {
                    debugLog("  - Format $i has no mimeType")
                    continue
                }
                
                val bitrate = format.optInt("bitrate", 0)
                
                // Parse codecs from mimeType (e.g., "video/mp4; codecs=\"avc1.640028\"")
                val codecs = extractCodecs(mimeType)
                
                if (mimeType.startsWith("video/")) {
                    val height = format.optInt("height", 0)
                    val width = format.optInt("width", 0)
                    val fps = format.optInt("fps", 30)
                    
                    if (height == 0 || width == 0) {
                        debugLog("  - Video format has invalid dimensions: ${width}x${height}")
                        continue
                    }
                    
                    // Prefer H.264 (avc1) over VP9 for better compatibility
                    val isH264 = codecs.contains("avc1")
                    val isVP9 = codecs.contains("vp9")
                    
                    // Select best video: prioritize 1080p H.264, then 720p H.264, then VP9
                    val shouldSelect = when {
                        bestVideo == null -> true
                        isH264 && !bestVideo.codecs.contains("avc1") -> true // Prefer H.264
                        isH264 && bestVideo.codecs.contains("avc1") && height > bestVideoHeight -> true
                        !isH264 && !bestVideo.codecs.contains("avc1") && height > bestVideoHeight -> true
                        else -> false
                    }
                    
                    if (shouldSelect && height <= 1920) { // Cap at 1080p for performance
                        bestVideo = VideoFormat(url, width, height, bitrate, mimeType, codecs, fps)
                        bestVideoHeight = height
                        debugLog("  ✓ Video candidate: ${width}x${height} ($codecs) @ ${bitrate/1000}kbps")
                    }
                    
                } else if (mimeType.startsWith("audio/")) {
                    val sampleRate = format.optInt("audioSampleRate", 44100)
                    
                    // Prefer AAC (mp4a) over Opus for better compatibility
                    val isAAC = codecs.contains("mp4a")
                    val isOpus = codecs.contains("opus")
                    
                    val shouldSelect = when {
                        bestAudio == null -> true
                        isAAC && !bestAudio.codecs.contains("mp4a") -> true // Prefer AAC
                        isAAC && bestAudio.codecs.contains("mp4a") && bitrate > bestAudioBitrate -> true
                        !isAAC && !bestAudio.codecs.contains("mp4a") && bitrate > bestAudioBitrate -> true
                        else -> false
                    }
                    
                    if (shouldSelect) {
                        bestAudio = AudioFormat(url, bitrate, mimeType, codecs, sampleRate)
                        bestAudioBitrate = bitrate
                        debugLog("  ✓ Audio candidate: $codecs @ ${bitrate/1000}kbps")
                    }
                }
            }

            if (bestVideo == null || bestAudio == null) {
                debugLog("❌ Could not find both video and audio formats")
                debugLog("   Video: ${bestVideo != null}, Audio: ${bestAudio != null}")
                if (bestVideo == null) debugLog("   → No video formats had valid URLs after decryption")
                if (bestAudio == null) debugLog("   → No audio formats had valid URLs after decryption")
                return null
            }

            debugLog("✓ Selected video: ${bestVideo.width}x${bestVideo.height} (${bestVideo.codecs})")
            debugLog("✓ Selected audio: ${bestAudio.codecs} @ ${bestAudio.bitrate/1000}kbps")

            // Return both URLs separated by ||| for PlayerManager to merge
            val dualStreamUrl = "${bestVideo.url}|||${bestAudio.url}"
            debugLog("✅ Returning dual-stream URL for merging")
            debugLog("📹 Video: ${bestVideo.url.take(150)}...")
            debugLog("🔊 Audio: ${bestAudio.url.take(150)}...")
            return Pair(dualStreamUrl, "1080p with audio (${bestVideo.width}x${bestVideo.height})")

            // Original DASH manifest code (disabled for now due to playback issues)
            /*
            // Create DASH manifest
            val dashManifest = createDashManifestXml(bestVideo, bestAudio)
            
            // Save manifest to file
            val manifestFile = File(context.getExternalFilesDir(null), "youtube_dash_manifest.mpd")
            manifestFile.writeText(dashManifest)
            
            debugLog("✓ DASH manifest created at: ${manifestFile.absolutePath}")
            debugLog("✅ Using custom DASH manifest for ${bestVideo.width}x${bestVideo.height} playback")
            
            // Return file:// URI for ExoPlayer
            return Pair("file://${manifestFile.absolutePath}", "Custom DASH ${bestVideo.width}x${bestVideo.height}")
            */
            
        } catch (e: Exception) {
            debugLog("❌ Error building DASH manifest: ${e.message}")
            debugLog("Stack trace: ${e.stackTraceToString()}")
            return null
        }
    }

    private fun extractCodecs(mimeType: String): String {
        // Extract codecs from mimeType string like "video/mp4; codecs=\"avc1.640028\""
        val codecsMatch = Regex("codecs=\"([^\"]+)\"").find(mimeType)
        return codecsMatch?.groupValues?.get(1) ?: ""
    }

    private fun createDashManifestXml(video: VideoFormat, audio: AudioFormat): String {
        // Create a valid DASH MPD manifest
        return """<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT0H0M0S" minBufferTime="PT2S" profiles="urn:mpeg:dash:profile:isoff-main:2011">
  <Period>
    <AdaptationSet mimeType="${video.mimeType.substringBefore(';')}" codecs="${video.codecs}" width="${video.width}" height="${video.height}" frameRate="${video.fps}" subsegmentAlignment="true">
      <Representation id="video" bandwidth="${video.bitrate}">
        <BaseURL>${escapeXml(video.url)}</BaseURL>
        <SegmentBase indexRange="0-" />
      </Representation>
    </AdaptationSet>
    <AdaptationSet mimeType="${audio.mimeType.substringBefore(';')}" codecs="${audio.codecs}" audioSamplingRate="${audio.sampleRate}" subsegmentAlignment="true">
      <Representation id="audio" bandwidth="${audio.bitrate}">
        <BaseURL>${escapeXml(audio.url)}</BaseURL>
        <SegmentBase indexRange="0-" />
      </Representation>
    </AdaptationSet>
  </Period>
</MPD>"""
    }

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
package com.livescreensaver.tv

import android.content.Context import android.util.Log import kotlinx.coroutines.Dispatchers import kotlinx.coroutines.withContext import okhttp3.OkHttpClient import okhttp3.Request import org.json.JSONObject import java.net.URLDecoder import java.util.concurrent.TimeUnit

/**

YouTubeStandaloneExtractor

PURPOSE (TV‑SAFE, STABLE):

360p ONLY = progressive video + audio (itag 18)


> 360p     = video‑only MP4 (no audio)




NO DASH parsing


NO JS execution


NO muxing


Signature decipher ONLY if required


Designed specifically for Android TV / DreamService usage. */ class YouTubeStandaloneExtractor( private val context: Context, private val signatureDecryptor: YouTubeSignatureDecryptor, httpClient: OkHttpClient? = null ) {

companion object { private const val TAG = "YTStandaloneExtractor" private const val ITAG_360P_PROGRESSIVE = 18 }

private val client = httpClient ?: OkHttpClient.Builder() .connectTimeout(15, TimeUnit.SECONDS) .readTimeout(15, TimeUnit.SECONDS) .build()

/**

Main entry point

@param videoId YouTube video ID

@param qualityMode value from Settings ("360_progressive", "720", "1080" etc) */ suspend fun extractStreamUrl( videoId: String, qualityMode: String ): String? = withContext(Dispatchers.IO) {

try { log("Starting extraction for $videoId | mode=$qualityMode")

val playerResponse = fetchPlayerResponse(videoId) ?: return@withContext null
 val streamingData = playerResponse.optJSONObject("streamingData") ?: return@withContext null

 val formats = streamingData.optJSONArray("formats") ?: return@withContext null

 // 1️⃣ 360p progressive (video+audio)
 if (qualityMode == "360_progressive") {
     findItag(formats, ITAG_360P_PROGRESSIVE)?.let {
         log("Selected 360p progressive")
         return@withContext resolveUrl(it, videoId)
     }
 }

 // 2️⃣ Higher resolutions = video‑only MP4
 val selected = selectBestVideoOnly(formats)
 if (selected != null) {
     log("Selected video‑only format")
     return@withContext resolveUrl(selected, videoId)
 }

 log("No suitable format found")
 null

} catch (e: Exception) { log("Extraction error: ${e.message}") null } }


/** Fetches YouTube player JSON */ private fun fetchPlayerResponse(videoId: String): JSONObject? { val url = "https://www.youtube.com/watch?v=$videoId&pbj=1" val request = Request.Builder() .url(url) .header("User-Agent", "Mozilla/5.0") .build()

client.newCall(request).execute().use { response ->
     if (!response.isSuccessful) return null

     val body = response.body?.string() ?: return null
     val jsonArray = try { org.json.JSONArray(body) } catch (_: Exception) { return null }

     for (i in 0 until jsonArray.length()) {
         val obj = jsonArray.optJSONObject(i) ?: continue
         if (obj.has("playerResponse")) {
             return obj.getJSONObject("playerResponse")
         }
     }
 }
 return null

}

/** Find exact itag */ private fun findItag(formats: org.json.JSONArray, itag: Int): JSONObject? { for (i in 0 until formats.length()) { val f = formats.getJSONObject(i) if (f.optInt("itag") == itag) return f } return null }

/** Select highest‑resolution MP4 video‑only */ private fun selectBestVideoOnly(formats: org.json.JSONArray): JSONObject? { var best: JSONObject? = null var bestHeight = 0

for (i in 0 until formats.length()) {
     val f = formats.getJSONObject(i)
     val mime = f.optString("mimeType")
     val hasAudio = f.has("audioQuality")
     val height = f.optInt("height", 0)

     if (mime.contains("video/mp4") && !hasAudio && height > bestHeight) {
         best = f
         bestHeight = height
     }
 }
 return best

}

/** Resolve URL (direct or signatureCipher) */ private suspend fun resolveUrl(format: JSONObject, videoId: String): String? { format.optString("url")?.takeIf { it.isNotBlank() }?.let { return it }

val cipher = format.optString("signatureCipher")
 if (cipher.isBlank()) return null

 return signatureDecryptor.decryptSignature(cipher, videoId)

}

private fun log(msg: String) { Log.d(TAG, msg) FileLogger.log(msg, TAG) } }
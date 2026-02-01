package com.livescreensaver.tv

import android.util.Log
import java.util.regex.Pattern

/**
 * YouTube extractor using CodePen proxy to bypass embedding restrictions.
 * Returns webview://CODEPEN_PROXY_URL that PlayerManager loads in a WebView.
 * 
 * This completely bypasses YouTube's embedding restrictions using the CodePen proxy:
 * https://cdpn.io/pen/debug/oNPzxKo?v=VIDEO_ID
 */
class YouTubeEmbedExtractor {
    
    companion object {
        private const val TAG = "YouTubeEmbedExtractor"
        private const val CODEPEN_PROXY = "https://cdpn.io/pen/debug/oNPzxKo"
    }
    
    fun canHandle(url: String): Boolean {
        return url.contains("youtube.com", ignoreCase = true) ||
               url.contains("youtu.be", ignoreCase = true)
    }
    
    fun extractVideoId(url: String): String? {
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
    
    /**
     * Extract stream - returns CodePen proxy URL wrapped in webview:// protocol
     */
    fun extractStream(url: String): Pair<String, String>? {
        try {
            val videoId = extractVideoId(url)
            
            if (videoId == null) {
                Log.e(TAG, "Failed to extract video ID from: $url")
                return null
            }
            
            Log.d(TAG, "✅ Extracted video ID: $videoId for CodePen Proxy")
            FileLogger.log("✅ Using CodePen Proxy for video: $videoId", TAG)
            
            // Build CodePen proxy URL with auto-play parameters
            val proxyUrl = "$CODEPEN_PROXY?v=$videoId&autoplay=1&mute=0&loop=1&controls=0&modestbranding=1"
            
            // Return with webview:// protocol so PlayerManager knows to use WebView
            return Pair("webview://$proxyUrl", "YouTube via CodePen Proxy (adaptive quality)")
            
        } catch (e: Exception) {
            Log.e(TAG, "YouTube embed extraction error", e)
            FileLogger.logError("YouTube embed extraction error", e, TAG)
            return null
        }
    }
}

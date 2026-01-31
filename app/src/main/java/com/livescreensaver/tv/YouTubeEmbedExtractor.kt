package com.livescreensaver.tv

import android.util.Log
import java.util.regex.Pattern

/**
 * YouTube extractor using embedded player (legal, stable, compliant).
 * Returns a special URL (youtube_embed://VIDEO_ID) that signals PlayerManager
 * to use YouTubePlayerView instead of ExoPlayer.
 * 
 * This completely bypasses all the InnerTube API complexity, signature decryption,
 * and client restrictions. YouTube's embed player is their official, stable API
 * that they won't break since it's used everywhere.
 */
class YouTubeEmbedExtractor {
    
    companion object {
        private const val TAG = "YouTubeEmbedExtractor"
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
     * Extract stream - returns special protocol URL for embedded player
     */
    fun extractStream(url: String): Pair<String, String>? {
        try {
            val videoId = extractVideoId(url)
            
            if (videoId == null) {
                Log.e(TAG, "Failed to extract video ID from: $url")
                return null
            }
            
            Log.d(TAG, "✅ Extracted video ID: $videoId for YouTube Embed Player")
            FileLogger.log("✅ Using YouTube Embed Player for video: $videoId", TAG)
            
            // Return special protocol URL that PlayerManager will recognize
            return Pair("youtube_embed://$videoId", "YouTube Embed Player (adaptive quality)")
            
        } catch (e: Exception) {
            Log.e(TAG, "YouTube embed extraction error", e)
            FileLogger.logError("YouTube embed extraction error", e, TAG)
            return null
        }
    }
}

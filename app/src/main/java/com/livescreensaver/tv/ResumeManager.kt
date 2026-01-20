package com.livescreensaver.tv

import android.content.SharedPreferences
import android.util.Log
import androidx.media3.exoplayer.ExoPlayer

class ResumeManager(private val resumeCache: SharedPreferences) {
    companion object {
        private const val TAG = "ResumeManager"
        private const val KEY_RESUME_POSITION = "resume_position"
        private const val KEY_RESUME_URL = "resume_url"
        private const val KEY_RESUME_TIMESTAMP = "resume_timestamp"
        private const val RESUME_TIMEOUT_MS = 5 * 60 * 1000
    }

    fun savePlaybackPosition(player: ExoPlayer?, currentSourceUrl: String?) {
        try {
            player?.let { p ->
                val currentUrl = currentSourceUrl ?: return
                val position = p.currentPosition
                val timestamp = System.currentTimeMillis()
                
                resumeCache.edit()
                    .putLong(KEY_RESUME_POSITION, position)
                    .putString(KEY_RESUME_URL, currentUrl)
                    .putLong(KEY_RESUME_TIMESTAMP, timestamp)
                    .apply()
                
                Log.d(TAG, "💾 Saved position: ${position / 1000}s")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save playback position", e)
        }
    }

    fun attemptResume(
        cache: PreferenceCache,
        currentSourceUrl: String?,
        duration: Long,
        skipOffset: Long
    ): Long? {
        try {
            if (!cache.resumeEnabled) return null
            
            val savedPosition = resumeCache.getLong(KEY_RESUME_POSITION, -1)
            val savedUrl = resumeCache.getString(KEY_RESUME_URL, null)
            val savedTimestamp = resumeCache.getLong(KEY_RESUME_TIMESTAMP, -1)
            
            if (savedPosition < 0 || savedUrl == null || savedTimestamp < 0) return null
            
            val currentUrl = currentSourceUrl ?: return null
            val savedBaseUrl = getBaseUrl(savedUrl)
            val currentBaseUrl = getBaseUrl(currentUrl)
            
            if (savedBaseUrl != currentBaseUrl) return null
            
            val timeSinceStop = System.currentTimeMillis() - savedTimestamp
            if (cache.randomSeekEnabled && timeSinceStop > RESUME_TIMEOUT_MS) return null
            
            resumeCache.edit().clear().apply()
            return savedPosition
        } catch (e: Exception) {
            Log.e(TAG, "Resume attempt failed", e)
            return null
        }
    }

    private fun getBaseUrl(url: String): String {
        return url.split("&sign=")[0]
    }
}
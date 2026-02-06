package com.livescreensaver.tv

import android.content.SharedPreferences

data class PreferenceCache(
    val audioEnabled: Boolean,
    val audioVolume: Int,
    // videoScalingMode removed - not supported with SurfaceView
    val speedEnabled: Boolean,
    val playbackSpeed: Float,
    val introEnabled: Boolean,
    val introDuration: Long,
    val skipBeginningEnabled: Boolean,
    val skipBeginningDuration: Long,
    val randomSeekEnabled: Boolean,
    val scheduleEnabled: Boolean,
    val scheduleRandomMode: Boolean,
    val clockEnabled: Boolean,
    val clockPosition: String,
    val clockSize: Int,
    val timeFormat: String,
    val pixelShiftInterval: Long,
    val statsEnabled: Boolean,
    val statsPosition: String,
    val statsInterval: Long,
    val resumeEnabled: Boolean,
    val preferredResolution: String
)

class AppPreferenceManager(private val preferences: SharedPreferences) {

    companion object {
        private const val PREF_LAST_ACTIVE_URL = "last_active_url"
    }

    fun loadPreferenceCache(): PreferenceCache {
        val cache = PreferenceCache(
            audioEnabled = preferences.getBoolean("audio_enabled", false),
            audioVolume = preferences.getString("audio_volume", "50")?.toIntOrNull() ?: 50,
            speedEnabled = preferences.getBoolean("speed_enabled", false),
            playbackSpeed = preferences.getString("playback_speed", "1.0")?.toFloatOrNull() ?: 1.0f,
            introEnabled = preferences.getBoolean("intro_enabled", true),
            introDuration = (preferences.getString("intro_duration", "7")?.toIntOrNull() ?: 7) * 1000L,
            skipBeginningEnabled = preferences.getBoolean("skip_beginning_enabled", false),
            skipBeginningDuration = (preferences.getString("skip_beginning_duration", "0")?.toIntOrNull() ?: 0) * 1000L,
            randomSeekEnabled = preferences.getBoolean("random_seek_enabled", true),
            scheduleEnabled = preferences.getBoolean("schedule_enabled", false),
            scheduleRandomMode = preferences.getBoolean("schedule_random_mode", false),
            clockEnabled = preferences.getBoolean("clock_enabled", false),
            clockPosition = preferences.getString("clock_position", "top_right") ?: "top_right",
            clockSize = preferences.getString("clock_size", "64")?.toIntOrNull() ?: 64,
            timeFormat = preferences.getString("time_format", "12h") ?: "12h",
            pixelShiftInterval = preferences.getString("pixel_shift_interval", "300000")?.toLongOrNull() ?: 300000,
            statsEnabled = preferences.getBoolean("stats_enabled", false),
            statsPosition = preferences.getString("stats_position", "top_left") ?: "top_left",
            statsInterval = preferences.getString("stats_interval", "1000")?.toLongOrNull() ?: 1000,
            resumeEnabled = preferences.getBoolean("resume_enabled", false),
            preferredResolution = preferences.getString("preferred_resolution", "auto") ?: "auto"
        )
        
        // Debug logging
        android.util.Log.d("AppPreferenceManager", "📋 Loaded preferences:")
        android.util.Log.d("AppPreferenceManager", "  randomSeekEnabled: ${cache.randomSeekEnabled}")
        android.util.Log.d("AppPreferenceManager", "  introEnabled: ${cache.introEnabled} (${cache.introDuration}ms)")
        android.util.Log.d("AppPreferenceManager", "  skipBeginningEnabled: ${cache.skipBeginningEnabled} (${cache.skipBeginningDuration}ms)")
        android.util.Log.d("AppPreferenceManager", "  speedEnabled: ${cache.speedEnabled} (${cache.playbackSpeed}x)")
        
        return cache
    }

    fun getLoadingAnimationType(): String {
        return preferences.getString("loading_animation_type", "spinning_dots") ?: "spinning_dots"
    }

    fun getLoadingAnimationText(): String {
        return preferences.getString("loading_animation_text", "Loading") ?: "Loading"
    }

    fun getLastActiveUrl(): String? {
        return preferences.getString(PREF_LAST_ACTIVE_URL, null)
    }

    fun saveLastActiveUrl(url: String) {
        preferences.edit().putString(PREF_LAST_ACTIVE_URL, url).apply()
    }

    fun clearLastActiveUrl() {
        preferences.edit().remove(PREF_LAST_ACTIVE_URL).apply()
    }
}
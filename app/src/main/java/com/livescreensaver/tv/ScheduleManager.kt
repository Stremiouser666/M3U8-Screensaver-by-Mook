package com.livescreensaver.tv

import android.content.SharedPreferences
import android.util.Log
import java.util.*

class ScheduleManager(
    private val preferences: SharedPreferences,
    private val defaultUrl: String
) {
    companion object {
        private const val TAG = "ScheduleManager"
        private const val PREF_VIDEO_URL = "video_url"
        private val DAYS = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
    }

    /**
     * Gets the appropriate URL based on current settings with proper fallback logic:
     * 1. If schedule disabled: Main URL (if not disabled) → Test URL
     * 2. If schedule enabled (random mode): Random from enabled weekly URLs → Main URL (if not disabled) → Test URL
     * 3. If schedule enabled (today mode): Today's URL (if not disabled) → Main URL (if not disabled) → Test URL
     */
    fun getScheduledUrl(cache: PreferenceCache): String {
        if (!cache.scheduleEnabled) {
            // Schedule disabled: try main URL, fallback to test
            return getMainUrlOrFallback()
        }

        // Schedule enabled: try weekly URLs first
        val weeklyUrl = if (cache.scheduleRandomMode) {
            getRandomScheduledUrl()
        } else {
            getTodayScheduledUrl()
        }

        // If we got a valid weekly URL, use it
        if (weeklyUrl != null) {
            Log.d(TAG, "Using weekly URL: $weeklyUrl")
            return weeklyUrl
        }

        // No valid weekly URL: fallback to main, then test
        Log.d(TAG, "No valid weekly URL found, falling back to main/test")
        return getMainUrlOrFallback()
    }

    /**
     * Returns a random URL from all enabled weekly URLs (does NOT include main URL)
     * Returns null if no valid weekly URLs exist
     */
    private fun getRandomScheduledUrl(): String? {
        val urls = DAYS.mapNotNull { day ->
            val url = preferences.getString("url_$day", null)
            val isDisabled = preferences.getBoolean("disable_url_$day", false)
            url?.takeIf { it.isNotEmpty() && !isDisabled }
        }

        return if (urls.isNotEmpty()) {
            val selectedUrl = urls.random()
            Log.d(TAG, "Random mode selected URL from ${urls.size} enabled weekly URLs")
            selectedUrl
        } else {
            Log.d(TAG, "Random mode: no enabled weekly URLs found")
            null
        }
    }

    /**
     * Returns today's scheduled URL if it exists and is not disabled
     * Returns null if today has no valid URL
     */
    private fun getTodayScheduledUrl(): String? {
        val calendar = Calendar.getInstance()
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)

        val dayIndex = when (dayOfWeek) {
            Calendar.MONDAY -> 0
            Calendar.TUESDAY -> 1
            Calendar.WEDNESDAY -> 2
            Calendar.THURSDAY -> 3
            Calendar.FRIDAY -> 4
            Calendar.SATURDAY -> 5
            Calendar.SUNDAY -> 6
            else -> 0
        }

        val dayName = DAYS[dayIndex]
        val dayUrl = preferences.getString("url_$dayName", null)
        val isDisabled = preferences.getBoolean("disable_url_$dayName", false)

        return if (!dayUrl.isNullOrEmpty() && !isDisabled) {
            Log.d(TAG, "Using today's ($dayName) URL")
            dayUrl
        } else {
            Log.d(TAG, "Today's ($dayName) URL is ${if (isDisabled) "disabled" else "empty"}")
            null
        }
    }

    /**
     * Returns main URL if it's not disabled, otherwise returns test URL
     */
    private fun getMainUrlOrFallback(): String {
        val mainUrl = preferences.getString(PREF_VIDEO_URL, null)
        val isMainDisabled = preferences.getBoolean("disable_video_url", false)

        return if (!mainUrl.isNullOrEmpty() && !isMainDisabled) {
            Log.d(TAG, "Using main URL")
            mainUrl
        } else {
            Log.d(TAG, "Main URL is ${if (isMainDisabled) "disabled" else "empty"}, using test URL: $defaultUrl")
            defaultUrl
        }
    }

    /**
     * Checks if the currently selected URL has changed compared to what was previously used
     * This helps detect when cache should be invalidated
     */
    fun hasUrlChanged(previousUrl: String?, cache: PreferenceCache): Boolean {
        val currentUrl = getScheduledUrl(cache)
        val changed = currentUrl != previousUrl
        if (changed) {
            Log.d(TAG, "URL changed from [$previousUrl] to [$currentUrl]")
        }
        return changed
    }
}
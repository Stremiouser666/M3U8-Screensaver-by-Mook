package com.livescreensaver.tv

import android.content.SharedPreferences
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

class UsageStatsTracker(private val statsPrefs: SharedPreferences) {
    companion object {
        private const val TAG = "UsageStatsTracker"
        private const val KEY_USAGE_PREFIX = "usage_"
        private const val KEY_MIGRATED = "usage_migrated_to_seconds"
    }

    init {
        // Migrate old minute-based data to seconds on first run
        migrateToSeconds()
    }

    private fun migrateToSeconds() {
        try {
            // Check if already migrated
            if (statsPrefs.getBoolean(KEY_MIGRATED, false)) {
                return
            }

            Log.d(TAG, "Migrating usage data from minutes to seconds...")

            val calendar = Calendar.getInstance()
            val editor = statsPrefs.edit()
            var migratedCount = 0

            // Go back 30 days to catch all potential data
            for (i in 0..29) {
                val dateKey = KEY_USAGE_PREFIX + SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(calendar.time)
                val oldValue = statsPrefs.getLong(dateKey, 0)

                if (oldValue > 0) {
                    // Convert minutes to seconds (multiply by 60)
                    val newValue = oldValue * 60
                    editor.putLong(dateKey, newValue)
                    migratedCount++
                }

                calendar.add(Calendar.DAY_OF_MONTH, -1)
            }

            // Mark as migrated
            editor.putBoolean(KEY_MIGRATED, true)
            editor.apply()

            Log.d(TAG, "Migration complete. Converted $migratedCount days of data.")
        } catch (e: Exception) {
            Log.e(TAG, "Migration failed", e)
        }
    }

    fun trackPlaybackUsage() {
        try {
            val dateKey = KEY_USAGE_PREFIX + getTodayDateKey()
            val currentSeconds = statsPrefs.getLong(dateKey, 0)
            statsPrefs.edit().putLong(dateKey, currentSeconds + 1).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to track usage", e)
        }
    }

    fun getUsageStats(): String {
        try {
            val calendar = Calendar.getInstance()
            val sdf = SimpleDateFormat("M/d", Locale.getDefault())
            val stats = StringBuilder()
            var totalSeconds = 0L

            for (i in 0..6) {
                val date = calendar.time
                val dateStr = sdf.format(date)
                val dateKey = KEY_USAGE_PREFIX + SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(date)
                val seconds = statsPrefs.getLong(dateKey, 0)

                totalSeconds += seconds
                val hours = seconds / 3600
                val minutes = (seconds % 3600) / 60

                stats.append(dateStr).append(": ")
                if (hours > 0) stats.append(hours).append("h ")
                stats.append(minutes).append("m\n")

                calendar.add(Calendar.DAY_OF_MONTH, -1)
            }

            stats.insert(0, "--- 7 DAY USAGE ---\n")
            stats.append("--- TOTAL: ")
            val totalHours = totalSeconds / 3600
            val totalMinutes = (totalSeconds % 3600) / 60
            if (totalHours > 0) stats.append(totalHours).append("h ")
            stats.append(totalMinutes).append("m ---")

            return stats.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get usage stats", e)
            return "Usage stats unavailable"
        }
    }

    private fun getTodayDateKey(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return sdf.format(Calendar.getInstance().time)
    }
}

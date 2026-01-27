package com.livescreensaver.tv

import android.content.SharedPreferences
import android.util.Log
import androidx.media3.exoplayer.ExoPlayer
import java.text.SimpleDateFormat
import java.util.*

class BandwidthTracker(private val bandwidthPrefs: SharedPreferences) {
    companion object {
        private const val TAG = "BandwidthTracker"
        private const val KEY_BANDWIDTH_PREFIX = "bandwidth_"
    }

    private var currentSessionBytes = 0L
    private var lastBytesRead = 0L
    private var currentBitrateKbps = 0

    fun trackBandwidth(player: ExoPlayer?) {
        try {
            player?.let { p ->
                // Get actual bytes loaded from the player's analytics
                val analytics = p.analyticsCollector
                val currentBytes = p.bufferedPosition * 1000 // Rough estimate
                
                // Try to get actual bitrate from current track selection
                val videoFormat = p.videoFormat
                if (videoFormat != null && videoFormat.bitrate > 0) {
                    currentBitrateKbps = videoFormat.bitrate / 1000
                } else {
                    // Calculate bitrate from data transfer if format bitrate unavailable
                    if (currentBytes > lastBytesRead && lastBytesRead > 0) {
                        val bytesThisCheck = currentBytes - lastBytesRead
                        // Assuming checks happen every ~1 second, calculate kbps
                        currentBitrateKbps = ((bytesThisCheck * 8) / 1000).toInt()
                    }
                }
                
                if (currentBytes > lastBytesRead) {
                    val bytesThisCheck = currentBytes - lastBytesRead
                    currentSessionBytes += bytesThisCheck
                    lastBytesRead = currentBytes

                    // Save every ~1MB to avoid data loss
                    if (currentSessionBytes > 0 && currentSessionBytes % 1_000_000 < 100_000) {
                        saveDailyBandwidth()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to track bandwidth", e)
        }
    }

    fun getCurrentBitrateKbps(): Int {
        return currentBitrateKbps
    }

    fun saveDailyBandwidth() {
        try {
            if (currentSessionBytes == 0L) return

            val dateKey = KEY_BANDWIDTH_PREFIX + getTodayDateKey()
            val currentTotal = bandwidthPrefs.getLong(dateKey, 0)
            bandwidthPrefs.edit().putLong(dateKey, currentTotal + currentSessionBytes).apply()
            currentSessionBytes = 0L
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save daily bandwidth", e)
        }
    }

    fun getBandwidthStats(): String {
        try {
            val calendar = Calendar.getInstance()
            val sdf = SimpleDateFormat("M/d", Locale.getDefault())
            val stats = StringBuilder("--- 7 DAY BANDWIDTH ---\n")
            var totalBytes = 0L

            for (i in 0..6) {
                val dateStr = sdf.format(calendar.time)
                val dateKey = KEY_BANDWIDTH_PREFIX + SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(calendar.time)
                val bytes = bandwidthPrefs.getLong(dateKey, 0)

                totalBytes += bytes
                val (value, unit) = formatBytes(bytes)

                stats.append(dateStr).append(": ")
                    .append(String.format("%.2f", value))
                    .append(unit)
                    .append('\n')

                calendar.add(Calendar.DAY_OF_MONTH, -1)
            }

            val (totalValue, totalUnit) = formatBytes(totalBytes)
            stats.append("--- TOTAL: ")
                .append(String.format("%.2f", totalValue))
                .append(totalUnit)
                .append(" ---")

            return stats.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get bandwidth stats", e)
            return "Bandwidth stats unavailable"
        }
    }

    fun reset() {
        currentSessionBytes = 0L
        lastBytesRead = 0L
        currentBitrateKbps = 0
    }

    private fun formatBytes(bytes: Long): Pair<Double, String> {
        return when {
            bytes >= 1_073_741_824 -> Pair(bytes.toDouble() / 1_073_741_824, " GB")
            bytes >= 1_048_576 -> Pair(bytes.toDouble() / 1_048_576, " MB")
            bytes >= 1_024 -> Pair(bytes.toDouble() / 1_024, " KB")
            else -> Pair(bytes.toDouble(), " B")
        }
    }

    private fun getTodayDateKey(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return sdf.format(Calendar.getInstance().time)
    }
}

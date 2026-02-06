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
        private const val DEFAULT_BITRATE_KBPS = 3000 // Fallback estimate
    }

    private var currentSessionBytes = 0L
    private var lastPositionMs = 0L
    private var currentBitrateKbps = 0
    private var totalPlayedTimeMs = 0L
    private var hasStarted = false

    fun trackBandwidth(player: ExoPlayer?) {
        try {
            player?.let { p ->
                val currentPositionMs = p.currentPosition
                
                // Initialize on first call
                if (!hasStarted) {
                    lastPositionMs = currentPositionMs
                    hasStarted = true
                    Log.d(TAG, "Bandwidth tracking started")
                    return
                }
                
                // Calculate playback progress
                val playbackProgressMs = currentPositionMs - lastPositionMs
                
                // Only track if position advanced (video is playing)
                if (playbackProgressMs > 100) { // At least 100ms progress
                    totalPlayedTimeMs += playbackProgressMs
                    
                    // Get bitrate from format or use default
                    val videoFormat = p.videoFormat
                    val estimatedBitrateKbps = if (videoFormat != null && videoFormat.bitrate > 0) {
                        videoFormat.bitrate / 1000
                    } else {
                        DEFAULT_BITRATE_KBPS
                    }
                    
                    // Calculate bytes consumed
                    val playbackSeconds = playbackProgressMs / 1000.0
                    val bytesConsumed = ((estimatedBitrateKbps * 1000.0 / 8.0) * playbackSeconds).toLong()
                    currentSessionBytes += bytesConsumed
                    
                    // Calculate average bitrate
                    if (totalPlayedTimeMs > 1000) {
                        val totalSeconds = totalPlayedTimeMs / 1000.0
                        currentBitrateKbps = ((currentSessionBytes * 8.0) / totalSeconds / 1000.0).toInt()
                    } else {
                        currentBitrateKbps = estimatedBitrateKbps
                    }
                    
                    Log.d(TAG, "Progress: ${playbackProgressMs}ms, Bytes: +${bytesConsumed}, Total: ${currentSessionBytes}, Bitrate: ${currentBitrateKbps} kbps")
                    
                    // Save every ~1MB
                    if (currentSessionBytes > 1_000_000) {
                        saveDailyBandwidth()
                    }
                }
                
                lastPositionMs = currentPositionMs
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to track bandwidth", e)
        }
    }

    fun getCurrentBitrateKbps(): Int {
        return currentBitrateKbps
    }

    fun isBitrateUnavailable(): Boolean {
        return false // Always try to calculate
    }

    fun saveDailyBandwidth() {
        try {
            if (currentSessionBytes == 0L) return

            val dateKey = KEY_BANDWIDTH_PREFIX + getTodayDateKey()
            val currentTotal = bandwidthPrefs.getLong(dateKey, 0)
            val newTotal = currentTotal + currentSessionBytes
            bandwidthPrefs.edit().putLong(dateKey, newTotal).apply()
            Log.d(TAG, "Saved ${currentSessionBytes / 1_000_000}MB to daily bandwidth (avg bitrate: $currentBitrateKbps kbps)")
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
        lastPositionMs = 0L
        currentBitrateKbps = 0
        totalPlayedTimeMs = 0L
        hasStarted = false
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

package com.livescreensaver.tv

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.text.SimpleDateFormat
import java.util.*

class UIOverlayManager(
    private val context: Context,
    private val containerLayout: FrameLayout,
    private val handler: Handler
) {
    companion object {
        private const val TAG = "UIOverlayManager"
        private val secureRandom = java.security.SecureRandom()
    }

    private var clockTextView: TextView? = null
    private var statsTextView: TextView? = null
    private var lastClockMinute = -1
    private var clockShiftX = 0f
    private var clockShiftY = 0f
    private val statsBuilder = StringBuilder(256)

    private val clockUpdateRunnable = object : Runnable {
        override fun run() {
            updateClock()
            val now = Calendar.getInstance()
            val secondsUntilNextMinute = 60 - now.get(Calendar.SECOND)
            val msUntilNextMinute = secondsUntilNextMinute * 1000L
            handler.postDelayed(this, msUntilNextMinute)
        }
    }

    private val pixelShiftRunnable = object : Runnable {
        override fun run() {
            shiftClock()
            handler.postDelayed(this, 300000)
        }
    }

    fun setupClock(cache: PreferenceCache) {
        try {
            clockTextView = TextView(context).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, cache.clockSize.toFloat())
                setShadowLayer(8f, 0f, 0f, Color.BLACK)
                setPadding(24, 24, 24, 24)
            }

            val gravity = when (cache.clockPosition) {
                "top_left" -> Gravity.TOP or Gravity.START
                "top_right" -> Gravity.TOP or Gravity.END
                "bottom_left" -> Gravity.BOTTOM or Gravity.START
                "bottom_right" -> Gravity.BOTTOM or Gravity.END
                else -> Gravity.TOP or Gravity.END
            }

            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.gravity = gravity
            }

            containerLayout.addView(clockTextView, params)
            handler.post(clockUpdateRunnable)

            if (cache.pixelShiftInterval > 0) {
                handler.postDelayed(pixelShiftRunnable, cache.pixelShiftInterval)
            }

            Log.d(TAG, "✅ Clock initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Clock initialization failed - disabling overlay", e)
            clockTextView = null
        }
    }

    fun setupStats(cache: PreferenceCache) {
        try {
            statsTextView = TextView(context).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setShadowLayer(8f, 0f, 0f, Color.BLACK)
                setPadding(16, 16, 16, 16)
                typeface = android.graphics.Typeface.MONOSPACE
                setBackgroundColor(Color.argb(128, 0, 0, 0))
            }

            val gravity = when (cache.statsPosition) {
                "top_left" -> Gravity.TOP or Gravity.START
                "top_right" -> Gravity.TOP or Gravity.END
                "bottom_left" -> Gravity.BOTTOM or Gravity.START
                "bottom_right" -> Gravity.BOTTOM or Gravity.END
                else -> Gravity.TOP or Gravity.START
            }

            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.gravity = gravity
            }

            containerLayout.addView(statsTextView, params)

            Log.d(TAG, "✅ Stats initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Stats initialization failed - disabling overlay", e)
            statsTextView = null
        }
    }

    fun updateClock(cache: PreferenceCache) {
        val now = Calendar.getInstance()
        val currentMinute = now.get(Calendar.MINUTE)

        if (currentMinute != lastClockMinute) {
            val pattern = if (cache.timeFormat == "12h") "h:mm a" else "HH:mm"
            val sdf = SimpleDateFormat(pattern, Locale.getDefault())
            clockTextView?.text = sdf.format(now.time)
            lastClockMinute = currentMinute
        }
    }

    private fun updateClock() {
        clockTextView?.let {
            val now = Calendar.getInstance()
            val currentMinute = now.get(Calendar.MINUTE)

            if (currentMinute != lastClockMinute) {
                val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
                it.text = sdf.format(now.time)
                lastClockMinute = currentMinute
            }
        }
    }

    fun updateStats(player: ExoPlayer?, usageStats: String, bandwidthStats: String, bandwidthTracker: BandwidthTracker? = null) {
        player?.let { p ->
            statsBuilder.clear()
            statsBuilder.setLength(0)

            statsBuilder.append(usageStats).append("\n\n")
            statsBuilder.append(bandwidthStats).append("\n\n")

            p.videoFormat?.let { format ->
                statsBuilder.append("Resolution: ")
                    .append(format.width)
                    .append('x')
                    .append(format.height)
                    .append('\n')

                // Get bitrate from BandwidthTracker instead of unreliable format.bitrate
                val bitrateKbps = bandwidthTracker?.getCurrentBitrateKbps() ?: 0
                if (bitrateKbps > 0) {
                    statsBuilder.append("Bitrate: ")
                        .append(bitrateKbps)
                        .append(" kbps\n")
                } else {
                    // Fallback to format bitrate if available
                    if (format.bitrate > 0) {
                        statsBuilder.append("Bitrate: ")
                            .append(format.bitrate / 1000)
                            .append(" kbps\n")
                    } else {
                        statsBuilder.append("Bitrate: Calculating...\n")
                    }
                }

                statsBuilder.append("FPS: ")
                    .append(String.format("%.2f", format.frameRate))
                    .append('\n')
            } ?: run {
                // If no video format available yet
                statsBuilder.append("Resolution: Loading...\n")
                statsBuilder.append("Bitrate: Loading...\n")
                statsBuilder.append("FPS: Loading...\n")
            }

            statsBuilder.append("Buffer: ")
                .append(p.bufferedPercentage)
                .append("%\n")

            val position = p.currentPosition / 1000
            val duration = if (p.duration != C.TIME_UNSET) p.duration / 1000 else 0
            val posMin = position / 60
            val posSec = position % 60
            val durMin = duration / 60
            val durSec = duration % 60

            statsBuilder.append("Position: ")
            if (duration > 0) {
                statsBuilder.append(posMin)
                    .append(':')
                    .append(String.format("%02d", posSec))
                    .append(" / ")
                    .append(durMin)
                    .append(':')
                    .append(String.format("%02d", durSec))
                    .append('\n')
            } else {
                statsBuilder.append(posMin)
                    .append(':')
                    .append(String.format("%02d", posSec))
                    .append(" / LIVE\n")
            }

            statsBuilder.append("State: ")
            val state = when (p.playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN"
            }
            statsBuilder.append(state)

            statsTextView?.text = statsBuilder.toString()
        }
    }

    private fun shiftClock() {
        clockTextView?.let { clock ->
            val shiftX = (secureRandom.nextInt(61) - 30).toFloat()
            val shiftY = (secureRandom.nextInt(61) - 30).toFloat()

            clockShiftX += shiftX
            clockShiftY += shiftY

            clockShiftX = clockShiftX.coerceIn(-60f, 60f)
            clockShiftY = clockShiftY.coerceIn(-60f, 60f)

            clock.animate()
                .translationX(clockShiftX)
                .translationY(clockShiftY)
                .setDuration(500)
                .start()
        }
    }

    fun removeClock() {
        clockTextView?.let { containerLayout.removeView(it) }
        clockTextView = null
    }

    fun removeStats() {
        statsTextView?.let { containerLayout.removeView(it) }
        statsTextView = null
    }

    fun hideStats() {
        statsTextView?.visibility = android.view.View.GONE
    }

    fun cleanup() {
        handler.removeCallbacks(clockUpdateRunnable)
        handler.removeCallbacks(pixelShiftRunnable)
        removeClock()
        removeStats()
        statsBuilder.clear()
        statsBuilder.setLength(0)
    }

    fun getClockUpdateRunnable() = clockUpdateRunnable
    fun getPixelShiftRunnable() = pixelShiftRunnable
}

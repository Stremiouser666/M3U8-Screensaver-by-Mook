package com.livescreensaver.tv

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object FileLogger {
    private const val TAG = "FileLogger"
    private const val LOG_FILENAME = "debug_log.txt"
    private const val MAX_LOG_SIZE = 5 * 1024 * 1024 // 5MB
    
    private var isEnabled = false
    private var logFile: File? = null
    
    fun enable(context: Context) {
        try {
            // Use external files dir - accessible via file manager without special permissions
            val externalFilesDir = context.getExternalFilesDir(null)
            logFile = File(externalFilesDir, LOG_FILENAME)
            
            // Check if file is too large, truncate if needed
            if (logFile?.exists() == true && (logFile?.length() ?: 0) > MAX_LOG_SIZE) {
                logFile?.delete()
            }
            
            isEnabled = true
            log("📝 File logging enabled")
            log("📂 Log file: ${logFile?.absolutePath}")
            log("📂 Accessible via: Android/data/com.livescreensaver.tv/files/debug_log.txt")
            log("⏰ Started: ${getCurrentTimestamp()}")
            log("─".repeat(60))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable file logging", e)
        }
    }
    
    fun disable() {
        if (!isEnabled) return
        log("─".repeat(60))
        log("⏰ Stopped: ${getCurrentTimestamp()}")
        log("📝 File logging disabled")
        isEnabled = false
    }
    
    fun log(message: String, tag: String = "LiveScreensaver") {
        if (!isEnabled || logFile == null) return
        
        try {
            val timestamp = getCurrentTimestamp()
            val logMessage = "[$timestamp] [$tag] $message\n"
            
            FileOutputStream(logFile, true).use { fos ->
                fos.write(logMessage.toByteArray())
            }
            
            // Also log to Android logcat
            Log.d(tag, message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to log file", e)
        }
    }
    
    fun logError(message: String, throwable: Throwable? = null, tag: String = "LiveScreensaver") {
        val errorMsg = if (throwable != null) {
            "$message\nException: ${throwable.javaClass.simpleName}\nMessage: ${throwable.message}\nStack: ${throwable.stackTraceToString()}"
        } else {
            message
        }
        log("❌ ERROR: $errorMsg", tag)
    }
    
    fun clearLog() {
        try {
            logFile?.delete()
            log("🗑️ Log file cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear log file", e)
        }
    }
    
    fun getLogPath(): String? {
        return logFile?.absolutePath
    }
    
    private fun getCurrentTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        return sdf.format(Date())
    }
}
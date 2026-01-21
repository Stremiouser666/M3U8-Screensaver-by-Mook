package com.livescreensaver.tv

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * YouTube Signature Decryptor
 * 
 * Handles decryption of YouTube's signatureCipher to obtain direct stream URLs.
 * Supports:
 * - External cipher function file (user-provided)
 * - Auto-extraction from YouTube player
 * - Built-in transformation patterns
 */
class YouTubeSignatureDecryptor(
    private val context: Context,
    httpClient: OkHttpClient? = null
) {
    companion object {
        private const val TAG = "YouTubeSignatureDecryptor"
        private const val EXTERNAL_CIPHER_FILE = "youtube_cipher.js"
        private const val CACHED_CIPHER_FILE = "youtube_cipher_cache.json"
        private const val CACHE_VALIDITY_HOURS = 24 // Refresh cipher every 24 hours
    }

    private val httpClient = httpClient ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class CipherCache(
        val playerUrl: String,
        val decipherFunctionName: String,
        val transformFunctions: Map<String, String>,
        val decipherCode: String,
        val timestamp: Long
    )

    /**
     * Decrypt a signatureCipher to get the direct URL
     */
    suspend fun decryptSignature(signatureCipher: String, videoId: String): String? = withContext(Dispatchers.IO) {
        try {
            debugLog("🔐 Decrypting signature cipher...")
            
            // Parse the signatureCipher
            val params = parseSignatureCipher(signatureCipher)
            val url = params["url"] ?: run {
                debugLog("❌ No URL in signatureCipher")
                return@withContext null
            }
            val signature = params["s"] ?: run {
                debugLog("⚠️ No signature parameter, URL might be direct")
                return@withContext url
            }
            val signatureParam = params["sp"] ?: "sig" // Default signature parameter name

            debugLog("📝 URL (encrypted): ${url.take(100)}...")
            debugLog("📝 Signature (encrypted): ${signature.take(50)}...")
            debugLog("📝 Signature param: $signatureParam")

            // Try external cipher file first
            val externalResult = tryExternalCipher(signature)
            if (externalResult != null) {
                debugLog("✅ External cipher succeeded")
                return@withContext "$url&$signatureParam=$externalResult"
            }

            // Try cached cipher functions
            val cachedResult = tryCachedCipher(signature, videoId)
            if (cachedResult != null) {
                debugLog("✅ Cached cipher succeeded")
                return@withContext "$url&$signatureParam=$cachedResult"
            }

            // Extract and use cipher from YouTube player
            val extractedResult = tryExtractAndDecrypt(signature, videoId)
            if (extractedResult != null) {
                debugLog("✅ Extracted cipher succeeded")
                return@withContext "$url&$signatureParam=$extractedResult"
            }

            debugLog("❌ All decryption methods failed")
            null

        } catch (e: Exception) {
            debugLog("❌ Signature decryption error: ${e.message}")
            debugLog("Stack trace: ${e.stackTraceToString()}")
            null
        }
    }

    /**
     * Parse signatureCipher into components
     * Format: "s=<signature>&sp=sig&url=<url>"
     */
    private fun parseSignatureCipher(cipher: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        cipher.split("&").forEach { part ->
            val keyValue = part.split("=", limit = 2)
            if (keyValue.size == 2) {
                params[keyValue[0]] = URLDecoder.decode(keyValue[1], "UTF-8")
            }
        }
        return params
    }

    /**
     * Try to use external cipher file (user-provided JavaScript function)
     */
    private fun tryExternalCipher(signature: String): String? {
        try {
            val externalFile = File(context.getExternalFilesDir(null), EXTERNAL_CIPHER_FILE)
            if (!externalFile.exists()) {
                debugLog("ℹ️ No external cipher file found at: ${externalFile.absolutePath}")
                return null
            }

            debugLog("📂 Loading external cipher from: ${externalFile.absolutePath}")
            val jsCode = externalFile.readText()

            // Execute JavaScript cipher function
            // Note: This requires a JS engine - for now, we'll document the expected format
            // Users can provide a simple transformation function
            
            debugLog("⚠️ External cipher file found but JS execution not yet implemented")
            debugLog("   Expected format: JavaScript function that transforms signature")
            
            // TODO: Implement JS execution using Rhino or similar
            // For now, return null to fall back to other methods
            return null

        } catch (e: Exception) {
            debugLog("❌ External cipher error: ${e.message}")
            return null
        }
    }

    /**
     * Try to use cached cipher functions
     */
    private suspend fun tryCachedCipher(signature: String, videoId: String): String? {
        try {
            val cacheFile = File(context.getExternalFilesDir(null), CACHED_CIPHER_FILE)
            if (!cacheFile.exists()) {
                debugLog("ℹ️ No cached cipher found")
                return null
            }

            val cacheJson = JSONObject(cacheFile.readText())
            val timestamp = cacheJson.getLong("timestamp")
            val age = System.currentTimeMillis() - timestamp
            val ageHours = age / (1000 * 60 * 60)

            if (ageHours > CACHE_VALIDITY_HOURS) {
                debugLog("⚠️ Cached cipher is ${ageHours}h old (max ${CACHE_VALIDITY_HOURS}h), refreshing...")
                return null
            }

            debugLog("✓ Using cached cipher (${ageHours}h old)")
            
            val transformOps = cacheJson.getString("transformOps")
            return applyTransformations(signature, transformOps)

        } catch (e: Exception) {
            debugLog("❌ Cached cipher error: ${e.message}")
            return null
        }
    }

    /**
     * Extract cipher from YouTube player and decrypt signature
     */
    private suspend fun tryExtractAndDecrypt(signature: String, videoId: String): String? = withContext(Dispatchers.IO) {
        try {
            debugLog("🔍 Extracting cipher from YouTube player...")

            // Get player URL
            val playerUrl = getPlayerUrl(videoId) ?: run {
                debugLog("❌ Could not find player URL")
                return@withContext null
            }

            debugLog("📥 Downloading player from: ${playerUrl.take(100)}...")

            // Download player JavaScript
            val request = Request.Builder().url(playerUrl).build()
            val response = httpClient.newCall(request).execute()
            
            if (!response.isSuccessful) {
                debugLog("❌ Player download failed: ${response.code}")
                return@withContext null
            }

            val playerJs = response.body?.string() ?: run {
                debugLog("❌ Empty player response")
                return@withContext null
            }

            debugLog("✓ Player downloaded (${playerJs.length} chars)")

            // Extract cipher function
            val transformOps = extractTransformOperations(playerJs)
            if (transformOps == null) {
                debugLog("❌ Could not extract transform operations")
                return@withContext null
            }

            debugLog("✓ Extracted transform operations: $transformOps")

            // Cache the cipher
            cacheTransformOperations(playerUrl, transformOps)

            // Apply transformations
            applyTransformations(signature, transformOps)

        } catch (e: Exception) {
            debugLog("❌ Extract and decrypt error: ${e.message}")
            null
        }
    }

    /**
     * Get the player URL for a video
     */
    private suspend fun getPlayerUrl(videoId: String): String? {
        try {
            // Get embed page to find player URL
            val embedUrl = "https://www.youtube.com/embed/$videoId"
            val request = Request.Builder().url(embedUrl).build()
            val response = httpClient.newCall(request).execute()
            
            if (!response.isSuccessful) return null
            
            val html = response.body?.string() ?: return null
            
            // Extract player URL from embed page
            val playerPattern = Pattern.compile("\"jsUrl\":\"(/s/player/[^\"]+)\"")
            val matcher = playerPattern.matcher(html)
            
            if (matcher.find()) {
                val playerPath = matcher.group(1)?.replace("\\/", "/")
                return "https://www.youtube.com$playerPath"
            }

            debugLog("⚠️ Could not find player URL in embed page")
            return null

        } catch (e: Exception) {
            debugLog("❌ Get player URL error: ${e.message}")
            return null
        }
    }

    /**
     * Extract transform operations from player JavaScript
     * 
     * YouTube's cipher typically consists of 3 operations:
     * - Reverse: reverse the string
     * - Splice: remove characters from position
     * - Swap: swap first character with character at position
     */
    private fun extractTransformOperations(playerJs: String): String? {
        try {
            // Find the decipher function
            // Pattern: var ABC=function(a){a=a.split("");XYZ.AB(a,123);...return a.join("")}
            
            val patterns = listOf(
                // Pattern 1: Common decipher pattern
                Pattern.compile("([a-zA-Z0-9$]+)=function\\(a\\)\\{a=a\\.split\\(\"\"\\);(.*?)return a\\.join\\(\"\"\\)\\}"),
                // Pattern 2: Alternative pattern
                Pattern.compile("\\b([a-zA-Z0-9$]+)\\s*=\\s*function\\(\\s*a\\s*\\)\\s*\\{\\s*a\\s*=\\s*a\\.split\\(\"\"\\)\\s*;(.*?)return\\s*a\\.join\\(\"\"\\)\\s*\\}"),
            )

            var decipherBody: String? = null
            var helperObjectName: String? = null

            for (pattern in patterns) {
                val matcher = pattern.matcher(playerJs)
                if (matcher.find()) {
                    decipherBody = matcher.group(2)
                    debugLog("✓ Found decipher function body")
                    break
                }
            }

            if (decipherBody == null) {
                debugLog("❌ Could not find decipher function")
                return null
            }

            // Extract helper object name (e.g., "XYZ" from "XYZ.AB(a,123)")
            val helperPattern = Pattern.compile("([a-zA-Z0-9$]+)\\.")
            val helperMatcher = helperPattern.matcher(decipherBody)
            if (helperMatcher.find()) {
                helperObjectName = helperMatcher.group(1)
                debugLog("✓ Helper object: $helperObjectName")
            }

            // Convert operations to our simplified format
            // For now, return the raw operations string
            // Format: "r;s2;r;s3;r" (r=reverse, s=splice, w=swap)
            
            val operations = parseOperations(decipherBody, helperObjectName)
            debugLog("✓ Parsed operations: $operations")
            
            return operations

        } catch (e: Exception) {
            debugLog("❌ Extract operations error: ${e.message}")
            return null
        }
    }

    /**
     * Parse decipher operations into simplified format
     */
    private fun parseOperations(decipherBody: String, helperName: String?): String {
        val ops = mutableListOf<String>()
        
        // Split by semicolons to get individual operations
        val statements = decipherBody.split(";")
        
        for (statement in statements) {
            val trimmed = statement.trim()
            
            when {
                // Reverse: a.reverse()
                trimmed.contains(".reverse()") -> ops.add("r")
                
                // Splice: a.splice(0,N)
                trimmed.contains(".splice(") -> {
                    val numPattern = Pattern.compile("\\.splice\\(\\d+,(\\d+)\\)")
                    val matcher = numPattern.matcher(trimmed)
                    if (matcher.find()) {
                        val num = matcher.group(1)
                        ops.add("s$num")
                    }
                }
                
                // Swap: helper function call
                helperName != null && trimmed.contains("$helperName.") -> {
                    val numPattern = Pattern.compile("$helperName\\.[^(]+\\([^,]+,(\\d+)\\)")
                    val matcher = numPattern.matcher(trimmed)
                    if (matcher.find()) {
                        val num = matcher.group(1)
                        ops.add("w$num")
                    }
                }
            }
        }
        
        return ops.joinToString(";")
    }

    /**
     * Apply transformation operations to signature
     */
    private fun applyTransformations(signature: String, operations: String): String {
        var result = signature
        
        operations.split(";").forEach { op ->
            when {
                op == "r" -> {
                    // Reverse
                    result = result.reversed()
                    debugLog("  - Applied reverse")
                }
                op.startsWith("s") -> {
                    // Splice (remove N characters from start)
                    val num = op.substring(1).toIntOrNull() ?: 0
                    if (num > 0 && num < result.length) {
                        result = result.substring(num)
                        debugLog("  - Applied splice($num)")
                    }
                }
                op.startsWith("w") -> {
                    // Swap (swap first with Nth character)
                    val num = op.substring(1).toIntOrNull() ?: 0
                    if (num > 0 && num < result.length) {
                        val chars = result.toCharArray()
                        val temp = chars[0]
                        chars[0] = chars[num]
                        chars[num] = temp
                        result = String(chars)
                        debugLog("  - Applied swap($num)")
                    }
                }
            }
        }
        
        return result
    }

    /**
     * Cache transform operations for future use
     */
    private fun cacheTransformOperations(playerUrl: String, operations: String) {
        try {
            val cacheFile = File(context.getExternalFilesDir(null), CACHED_CIPHER_FILE)
            val cacheJson = JSONObject().apply {
                put("playerUrl", playerUrl)
                put("transformOps", operations)
                put("timestamp", System.currentTimeMillis())
            }
            cacheFile.writeText(cacheJson.toString(2))
            debugLog("✓ Cached cipher operations")
        } catch (e: Exception) {
            debugLog("⚠️ Failed to cache operations: ${e.message}")
        }
    }

    private fun debugLog(message: String) {
        Log.d(TAG, message)
        FileLogger.log(message, TAG)
    }
}
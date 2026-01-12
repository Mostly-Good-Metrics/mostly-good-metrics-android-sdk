package com.mostlygoodmetrics.sdk

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream

/** SDK version for User-Agent header */
internal const val SDK_VERSION = "0.2.6"

/**
 * Result of a network send operation.
 */
sealed class SendResult {
    /**
     * Events were successfully sent.
     */
    data object Success : SendResult()

    /**
     * Events should be preserved for retry (network/server error).
     */
    data class RetryLater(val error: MGMError) : SendResult()

    /**
     * Events should be dropped (client error - won't succeed on retry).
     */
    data class DropEvents(val error: MGMError) : SendResult()
}

/**
 * Interface for network operations.
 */
interface NetworkClientInterface {
    /**
     * Send events to the API.
     */
    suspend fun sendEvents(payload: MGMEventsPayload): SendResult
}

/**
 * Network client for sending events to the MostlyGoodMetrics API.
 */
class NetworkClient(
    private val configuration: MGMConfiguration
) : NetworkClientInterface {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val connectTimeoutMs = 30_000
    private val readTimeoutMs = 60_000

    @Volatile
    private var retryAfterTime: Long = 0L

    /**
     * Whether we're currently rate limited.
     */
    val isRateLimited: Boolean
        get() = System.currentTimeMillis() < retryAfterTime

    /**
     * Send events to the API.
     */
    override suspend fun sendEvents(payload: MGMEventsPayload): SendResult = withContext(Dispatchers.IO) {
        // Check rate limit
        if (isRateLimited) {
            val waitTime = (retryAfterTime - System.currentTimeMillis()) / 1000
            MGMLogger.debug("Rate limited, waiting ${waitTime}s before retry")
            return@withContext SendResult.RetryLater(MGMError.RateLimited(waitTime))
        }

        var connection: HttpURLConnection? = null
        try {
            val jsonBody = json.encodeToString(payload)
            val bodyBytes = jsonBody.toByteArray(Charsets.UTF_8)

            // Compress if payload > 1KB
            val (requestBody, isCompressed) = if (bodyBytes.size > 1024) {
                gzipCompress(bodyBytes) to true
            } else {
                bodyBytes to false
            }

            val url = URL("${configuration.baseUrl}/v1/events")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true

                setRequestProperty("X-MGM-Key", configuration.apiKey)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", buildUserAgent())

                // SDK identification headers for metrics
                setRequestProperty("X-MGM-SDK", "android")
                setRequestProperty("X-MGM-SDK-Version", SDK_VERSION)
                setRequestProperty("X-MGM-Platform", "android")
                setRequestProperty("X-MGM-Platform-Version", Build.VERSION.RELEASE ?: "unknown")

                configuration.packageName?.let {
                    setRequestProperty("X-MGM-Bundle-Id", it)
                }

                configuration.wrapperName?.let {
                    setRequestProperty("X-MGM-Wrapper", it)
                }

                configuration.wrapperVersion?.let {
                    setRequestProperty("X-MGM-Wrapper-Version", it)
                }

                if (isCompressed) {
                    setRequestProperty("Content-Encoding", "gzip")
                }
            }

            MGMLogger.debug("Sending ${payload.events.size} events to ${configuration.baseUrl}/v1/events")

            connection.outputStream.use { outputStream ->
                outputStream.write(requestBody)
            }

            handleResponse(connection)
        } catch (e: IOException) {
            MGMLogger.error("Network error sending events", e)
            SendResult.RetryLater(MGMError.NetworkError(e))
        } catch (e: Exception) {
            MGMLogger.error("Error encoding events", e)
            SendResult.DropEvents(MGMError.EncodingError(e))
        } finally {
            connection?.disconnect()
        }
    }

    private fun handleResponse(connection: HttpURLConnection): SendResult {
        val statusCode = connection.responseCode
        val body = try {
            if (statusCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }
        } catch (e: Exception) {
            ""
        }

        MGMLogger.debug("Response: $statusCode - $body")

        return when (statusCode) {
            204 -> {
                MGMLogger.debug("Events sent successfully")
                SendResult.Success
            }
            400 -> {
                MGMLogger.warn("Bad request: $body")
                SendResult.DropEvents(MGMError.BadRequest(body))
            }
            401 -> {
                MGMLogger.warn("Unauthorized - invalid API key")
                SendResult.DropEvents(MGMError.Unauthorized)
            }
            403 -> {
                MGMLogger.warn("Forbidden: $body")
                SendResult.DropEvents(MGMError.Forbidden(body))
            }
            429 -> {
                val retryAfter = connection.getHeaderField("Retry-After")?.toLongOrNull()
                if (retryAfter != null) {
                    retryAfterTime = System.currentTimeMillis() + (retryAfter * 1000)
                }
                MGMLogger.warn("Rate limited, retry after: ${retryAfter ?: "unknown"}s")
                SendResult.RetryLater(MGMError.RateLimited(retryAfter))
            }
            in 500..599 -> {
                MGMLogger.warn("Server error: $statusCode - $body")
                SendResult.RetryLater(MGMError.ServerError(statusCode, body))
            }
            else -> {
                MGMLogger.warn("Unexpected status code: $statusCode")
                SendResult.RetryLater(MGMError.UnexpectedStatusCode(statusCode))
            }
        }
    }

    private fun gzipCompress(data: ByteArray): ByteArray {
        val outputStream = ByteArrayOutputStream()
        GZIPOutputStream(outputStream).use { gzip ->
            gzip.write(data)
        }
        return outputStream.toByteArray()
    }

    private fun buildUserAgent(): String {
        val osVersion = Build.VERSION.RELEASE ?: "unknown"
        return "MostlyGoodMetrics/$SDK_VERSION (Android; OS $osVersion)"
    }
}

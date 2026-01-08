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
internal const val SDK_VERSION = "0.2.4"

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
 * Result of fetching experiments.
 */
sealed class ExperimentsResult {
    /**
     * Successfully fetched experiments.
     */
    data class Success(val experiments: List<Experiment>) : ExperimentsResult()

    /**
     * Failed to fetch experiments.
     */
    data class Failure(val error: MGMError) : ExperimentsResult()
}

/**
 * Represents an A/B test experiment.
 */
data class Experiment(
    val id: String,
    val variants: List<String>
)

/**
 * Interface for network operations.
 */
interface NetworkClientInterface {
    /**
     * Send events to the API.
     */
    suspend fun sendEvents(payload: MGMEventsPayload): SendResult

    /**
     * Fetch active experiments from the API.
     */
    suspend fun fetchExperiments(): ExperimentsResult
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

    /**
     * Fetch active experiments from the API.
     */
    override suspend fun fetchExperiments(): ExperimentsResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("${configuration.baseUrl}/v1/experiments")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs

                setRequestProperty("X-MGM-Key", configuration.apiKey)
                setRequestProperty("User-Agent", buildUserAgent())
                setRequestProperty("Accept", "application/json")

                // SDK identification headers
                setRequestProperty("X-MGM-SDK", "android")
                setRequestProperty("X-MGM-SDK-Version", SDK_VERSION)

                configuration.packageName?.let {
                    setRequestProperty("X-MGM-Bundle-Id", it)
                }
            }

            MGMLogger.debug("Fetching experiments from ${configuration.baseUrl}/v1/experiments")

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

            MGMLogger.debug("Experiments response: $statusCode - $body")

            when (statusCode) {
                200 -> {
                    try {
                        val experiments = parseExperimentsResponse(body)
                        MGMLogger.debug("Fetched ${experiments.size} experiments")
                        ExperimentsResult.Success(experiments)
                    } catch (e: Exception) {
                        MGMLogger.error("Failed to parse experiments response", e)
                        ExperimentsResult.Failure(MGMError.EncodingError(e))
                    }
                }
                401 -> {
                    MGMLogger.warn("Unauthorized - invalid API key")
                    ExperimentsResult.Failure(MGMError.Unauthorized)
                }
                else -> {
                    MGMLogger.warn("Failed to fetch experiments: $statusCode")
                    ExperimentsResult.Failure(MGMError.UnexpectedStatusCode(statusCode))
                }
            }
        } catch (e: IOException) {
            MGMLogger.error("Network error fetching experiments", e)
            ExperimentsResult.Failure(MGMError.NetworkError(e))
        } catch (e: Exception) {
            MGMLogger.error("Error fetching experiments", e)
            ExperimentsResult.Failure(MGMError.EncodingError(e))
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Parse the experiments JSON response.
     * Expected format: { "experiments": [{ "id": "...", "variants": ["a", "b"] }] }
     */
    private fun parseExperimentsResponse(jsonString: String): List<Experiment> {
        val jsonObject = org.json.JSONObject(jsonString)
        val experimentsArray = jsonObject.optJSONArray("experiments") ?: return emptyList()

        val experiments = mutableListOf<Experiment>()
        for (i in 0 until experimentsArray.length()) {
            val expObj = experimentsArray.getJSONObject(i)
            val id = expObj.getString("id")
            val variantsArray = expObj.getJSONArray("variants")
            val variants = mutableListOf<String>()
            for (j in 0 until variantsArray.length()) {
                variants.add(variantsArray.getString(j))
            }
            experiments.add(Experiment(id = id, variants = variants))
        }
        return experiments
    }
}

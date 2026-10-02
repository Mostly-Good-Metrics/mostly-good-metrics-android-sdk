package com.mostlygoodmetrics.sdk

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Represents an analytics event to be tracked.
 */
@Serializable
data class MGMEvent(
    val name: String,
    @SerialName("client_event_id")
    val clientEventId: String,
    val timestamp: String,
    @SerialName("user_id")
    val userId: String? = null,
    @SerialName("session_id")
    val sessionId: String? = null,
    val platform: String? = null,
    @SerialName("app_version")
    val appVersion: String? = null,
    @SerialName("app_build_number")
    val appBuildNumber: String? = null,
    @SerialName("os_version")
    val osVersion: String? = null,
    val environment: String? = null,
    @SerialName("device_manufacturer")
    val deviceManufacturer: String? = null,
    val locale: String? = null,
    val timezone: String? = null,
    val properties: JsonObject? = null
) {
    companion object {
        private const val MAX_EVENT_NAME_LENGTH = 255
        private const val MAX_STRING_PROPERTY_LENGTH = 1000
        private const val MAX_PROPERTIES_DEPTH = 3
        private const val MAX_PROPERTIES_SIZE_BYTES = 10 * 1024 // 10KB

        // Regex pattern: starts with letter (or $ for system events), allows spaces between words.
        private val EVENT_NAME_PATTERN = Regex("^\\$?[a-zA-Z](?:[a-zA-Z0-9_ ]*[a-zA-Z0-9_])?$")

        private val iso8601Format =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }

        private fun format(timestamp: Date): String =
            synchronized(iso8601Format) { iso8601Format.format(timestamp) }

        /**
         * Validates an event name.
         * @return true if the name is valid, false otherwise
         */
        fun isValidEventName(name: String): Boolean {
            if (name.isEmpty() || name.length > MAX_EVENT_NAME_LENGTH) {
                return false
            }
            return EVENT_NAME_PATTERN.matches(name)
        }

        /**
         * Creates an event with the current timestamp.
         */
        fun create(
            name: String,
            userId: String? = null,
            sessionId: String? = null,
            platform: String? = null,
            appVersion: String? = null,
            appBuildNumber: String? = null,
            osVersion: String? = null,
            environment: String? = null,
            deviceManufacturer: String? = null,
            locale: String? = null,
            timezone: String? = null,
            properties: Map<String, Any?>? = null
        ): MGMEvent? = createAt(
            name = name,
            timestamp = Date(),
            userId = userId,
            sessionId = sessionId,
            platform = platform,
            appVersion = appVersion,
            appBuildNumber = appBuildNumber,
            osVersion = osVersion,
            environment = environment,
            deviceManufacturer = deviceManufacturer,
            locale = locale,
            timezone = timezone,
            properties = properties
        )

        internal fun createAt(
            name: String,
            timestamp: Date,
            userId: String? = null,
            sessionId: String? = null,
            platform: String? = null,
            appVersion: String? = null,
            appBuildNumber: String? = null,
            osVersion: String? = null,
            environment: String? = null,
            deviceManufacturer: String? = null,
            locale: String? = null,
            timezone: String? = null,
            properties: Map<String, Any?>? = null
        ): MGMEvent? {
            if (!isValidEventName(name)) {
                return null
            }

            val jsonProperties = properties?.let { convertToJsonObject(it) }

            return MGMEvent(
                name = name,
                clientEventId = UUID.randomUUID().toString(),
                timestamp = format(timestamp),
                userId = userId,
                sessionId = sessionId,
                platform = platform,
                appVersion = appVersion,
                appBuildNumber = appBuildNumber,
                osVersion = osVersion,
                environment = environment,
                deviceManufacturer = deviceManufacturer,
                locale = locale,
                timezone = timezone,
                properties = jsonProperties
            )
        }

        /**
         * Converts a Map to JsonObject with proper type handling and truncation.
         */
        internal fun convertToJsonObject(map: Map<*, *>, depth: Int = 0): JsonObject? = try {
            val converted = convertToJsonObject(map, depth, PropertyBudget())
            if (converted != null && JsonSafety.isSafe(converted.toString(), MAX_PROPERTIES_SIZE_BYTES)) converted else null
        } catch (error: Exception) {
            MGMLogger.warn("Failed to convert event properties: ${error.message}")
            null
        }

        // A depth limit alone still expands wide cyclic graphs exponentially.
        // Share one node budget across the entire event, not one per container.
        private class PropertyBudget(var remaining: Int = 1024) {
            fun consume(): Boolean {
                if (remaining <= 0) return false
                remaining--
                return true
            }
        }

        private fun convertToJsonObject(map: Map<*, *>, depth: Int, budget: PropertyBudget): JsonObject? {
            if (depth > MAX_PROPERTIES_DEPTH) return null
            return buildJsonObject {
                for ((key, value) in map) {
                    if (!budget.consume()) break
                    if (key is String && key.length <= MAX_STRING_PROPERTY_LENGTH) put(key, convertToJsonElement(value, depth, budget))
                }
            }
        }

        private fun convertToJsonElement(value: Any?, depth: Int, budget: PropertyBudget): JsonElement {
            if (!budget.consume() || depth > MAX_PROPERTIES_DEPTH) return JsonNull
            return try {
                when (value) {
                    null -> JsonNull
                    is Boolean -> JsonPrimitive(value)
                    is Number -> JsonPrimitive(value)
                    is String -> JsonPrimitive(truncateString(value))
                    is Map<*, *> -> convertToJsonObject(value, depth + 1, budget) ?: JsonNull
                    is List<*> -> {
                        val elements = mutableListOf<JsonElement>()
                        for (index in 0 until minOf(value.size, budget.remaining)) {
                            if (budget.remaining <= 0) break
                            elements.add(convertToJsonElement(value[index], depth + 1, budget))
                        }
                        JsonArray(elements)
                    }
                    is Array<*> -> {
                        val elements = mutableListOf<JsonElement>()
                        for (index in 0 until minOf(value.size, budget.remaining)) {
                            if (budget.remaining <= 0) break
                            elements.add(convertToJsonElement(value[index], depth + 1, budget))
                        }
                        JsonArray(elements)
                    }
                    else -> JsonPrimitive(truncateString(value.toString()))
                }
            } catch (error: Exception) {
                MGMLogger.warn("Failed to convert event property: ${error.message}")
                JsonNull
            }
        }

        private fun truncateString(value: String): String {
            return if (value.length > MAX_STRING_PROPERTY_LENGTH) {
                value.substring(0, MAX_STRING_PROPERTY_LENGTH)
            } else {
                value
            }
        }
    }
}

/**
 * Payload structure for sending events to the API.
 */
@Serializable
data class MGMEventsPayload(
    val events: List<MGMEvent>,
    val context: MGMEventContext? = null
)

/**
 * Shared context for a batch of events.
 */
@Serializable
data class MGMEventContext(
    val platform: String? = null,
    @SerialName("app_version")
    val appVersion: String? = null,
    @SerialName("app_build_number")
    val appBuildNumber: String? = null,
    @SerialName("os_version")
    val osVersion: String? = null,
    @SerialName("user_id")
    val userId: String? = null,
    @SerialName("session_id")
    val sessionId: String? = null,
    val environment: String? = null,
    @SerialName("device_manufacturer")
    val deviceManufacturer: String? = null,
    val locale: String? = null,
    val timezone: String? = null
)

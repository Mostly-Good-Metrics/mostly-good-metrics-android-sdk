package com.mostlygoodmetrics.sdk

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.ArrayDeque

/** Conservative estimate bounds retained SDK values without serializing them on callers. */
internal object EventMemoryBudget {
    const val MAX_BYTES = 1024 * 1024

    fun weight(event: MGMEvent): Int? {
        return try {
            var bytes = 512L
            val strings = listOf(event.name, event.clientEventId, event.timestamp, event.userId, event.sessionId,
                event.platform, event.appVersion, event.appBuildNumber, event.osVersion, event.environment,
                event.deviceManufacturer, event.locale, event.timezone)
            strings.forEach { bytes += (it?.length ?: 0).toLong() * 6 }
            val pending = ArrayDeque<Pair<JsonElement, Int>>()
            event.properties?.let { pending.add(it to 0) }
            var nodes = 0
            while (pending.isNotEmpty()) {
                val (value, depth) = pending.removeLast()
                if (++nodes > 2048 || depth > 8) return null
                bytes += 96
                if (bytes > MAX_BYTES) return null
                when (value) {
                    is JsonPrimitive -> bytes += value.content.length.toLong() * 6
                    is JsonObject -> {
                        if (value.size > 2048 - nodes - pending.size) return null
                        for ((key, child) in value) {
                            bytes += key.length.toLong() * 6
                            pending.add(child to depth + 1)
                        }
                    }
                    is JsonArray -> {
                        if (value.size > 2048 - nodes - pending.size) return null
                        for (child in value) pending.add(child to depth + 1)
                    }
                }
            }
            if (bytes > MAX_BYTES) null else bytes.toInt()
        } catch (error: Exception) {
            MGMLogger.warn("Cannot read event for memory budget: ${error.message}")
            null
        }
    }
}

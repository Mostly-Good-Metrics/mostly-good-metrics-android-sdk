package com.mostlygoodmetrics.sdk

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Interface for event storage implementations.
 * Provides abstraction for storing and retrieving events.
 */
interface EventStorage {
    /**
     * Store an event.
     */
    fun store(event: MGMEvent)

    /**
     * Fetch events up to the specified limit.
     * @param limit Maximum number of events to return
     * @return List of events, ordered from oldest to newest
     */
    fun fetchEvents(limit: Int): List<MGMEvent>

    /**
     * Remove the specified events from storage.
     */
    fun removeEvents(events: List<MGMEvent>)

    /**
     * Get the current count of stored events.
     */
    fun eventCount(): Int

    /**
     * Clear all stored events.
     */
    fun clear()
}

/**
 * File-based event storage implementation.
 * Persists events to a JSON file in the app's internal storage.
 *
 * Thread-safe via ReentrantReadWriteLock for concurrent access.
 */
class FileEventStorage(
    context: Context,
    private val maxEvents: Int = MGMConfiguration.DEFAULT_MAX_STORED_EVENTS
) : EventStorage {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val storageDir: File = File(context.filesDir, "mostlygoodmetrics")
    private val storageFile: File = File(storageDir, "events.json")
    private val lock = ReentrantReadWriteLock()
    private val events: MutableList<MGMEvent> = mutableListOf()

    init {
        loadFromDisk()
    }

    override fun store(event: MGMEvent) {
        lock.write {
            events.add(event)

            // Rotate if we exceed max events (FIFO - drop oldest)
            while (events.size > maxEvents) {
                events.removeAt(0)
            }

            saveToDisk()
        }
    }

    override fun fetchEvents(limit: Int): List<MGMEvent> {
        return lock.read {
            events.take(limit).toList()
        }
    }

    override fun removeEvents(events: List<MGMEvent>) {
        lock.write {
            this.events.removeAll(events.toSet())
            saveToDisk()
        }
    }

    override fun eventCount(): Int {
        return lock.read {
            events.size
        }
    }

    override fun clear() {
        lock.write {
            events.clear()
            saveToDisk()
        }
    }

    private fun loadFromDisk() {
        lock.write {
            try {
                if (storageFile.exists()) {
                    val content = storageFile.readText()
                    if (content.isNotBlank()) {
                        val loadedEvents: List<MGMEvent> = json.decodeFromString(content)
                        events.clear()
                        events.addAll(loadedEvents)
                    }
                }
            } catch (e: Exception) {
                // Silent failure - start with empty list
                MGMLogger.error("Failed to load events from disk: ${e.message}")
            }
        }
    }

    private fun saveToDisk() {
        try {
            if (!storageDir.exists()) {
                storageDir.mkdirs()
            }

            val content = json.encodeToString(events.toList())

            // Atomic write using temp file
            val tempFile = File(storageDir, "events.json.tmp")
            tempFile.writeText(content)
            tempFile.renameTo(storageFile)
        } catch (e: Exception) {
            // Silent failure - events remain in memory
            MGMLogger.error("Failed to save events to disk: ${e.message}")
        }
    }
}

/**
 * In-memory event storage implementation.
 * Useful for testing or temporary event collection.
 */
class InMemoryEventStorage(
    private val maxEvents: Int = MGMConfiguration.DEFAULT_MAX_STORED_EVENTS
) : EventStorage {

    private val lock = ReentrantReadWriteLock()
    private val events: MutableList<MGMEvent> = mutableListOf()

    override fun store(event: MGMEvent) {
        lock.write {
            events.add(event)

            while (events.size > maxEvents) {
                events.removeAt(0)
            }
        }
    }

    override fun fetchEvents(limit: Int): List<MGMEvent> {
        return lock.read {
            events.take(limit).toList()
        }
    }

    override fun removeEvents(events: List<MGMEvent>) {
        lock.write {
            this.events.removeAll(events.toSet())
        }
    }

    override fun eventCount(): Int {
        return lock.read {
            events.size
        }
    }

    override fun clear() {
        lock.write {
            events.clear()
        }
    }
}

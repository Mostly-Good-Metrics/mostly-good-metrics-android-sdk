package com.mostlygoodmetrics.sdk

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
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

internal interface CloseableEventStorage {
    fun close()
}

/**
 * File-based event storage implementation.
 * Persists events to a JSON file in the app's internal storage.
 *
 * Thread-safe via ReentrantReadWriteLock for concurrent access.
 */
class FileEventStorage internal constructor(
    private val storageDir: File,
    private val maxEvents: Int = MGMConfiguration.DEFAULT_MAX_STORED_EVENTS,
    private val persistenceDelayMs: Long = PERSISTENCE_DELAY_MS,
    private val beforePersist: (() -> Unit)? = null
) : EventStorage, CloseableEventStorage {

    constructor(
        context: Context,
        maxEvents: Int = MGMConfiguration.DEFAULT_MAX_STORED_EVENTS
    ) : this(File(context.filesDir, "mostlygoodmetrics"), maxEvents)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val storageFile: File = File(storageDir, "events.json")
    private val lock = ReentrantReadWriteLock()
    private val events = ArrayDeque<MGMEvent>()
    private val persistenceRevision = AtomicLong(0)
    private var persistedRevision = 0L
    private val persistenceMonitor = Object()
    private var persistenceScheduled = false
    private val persistenceExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "mostly-good-metrics-storage").apply { isDaemon = true }
    }

    init {
        loadFromDisk()
    }

    override fun store(event: MGMEvent) {
        lock.write {
            events.addLast(event)

            // Rotate if we exceed max events (FIFO - drop oldest)
            while (events.size > maxEvents) {
                events.removeFirst()
            }
        }
        schedulePersistence()
    }

    override fun fetchEvents(limit: Int): List<MGMEvent> {
        return lock.read {
            events.take(limit).toList()
        }
    }

    override fun removeEvents(events: List<MGMEvent>) {
        val eventIds = events.mapTo(mutableSetOf()) { it.clientEventId }
        lock.write {
            this.events.removeAll { it.clientEventId in eventIds }
        }
        schedulePersistence()
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
        schedulePersistence()
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

    /**
     * Queue a coalesced JSON-array rewrite. Mutations stay synchronous and cheap in
     * memory, while serialization and file I/O always happen on the storage thread.
     */
    private fun schedulePersistence() {
        synchronized(persistenceMonitor) {
            persistenceRevision.incrementAndGet()
            if (persistenceScheduled) return
            persistenceScheduled = true
            var accepted = false
            try {
                persistenceExecutor.schedule(
                    ::persistLatestSnapshot,
                    persistenceDelayMs,
                    TimeUnit.MILLISECONDS
                )
                accepted = true
            } catch (error: RejectedExecutionException) {
                MGMLogger.error("Failed to schedule event persistence: ${error.message}")
            } finally {
                if (!accepted) {
                    persistenceScheduled = false
                    persistenceMonitor.notifyAll()
                }
            }
        }
    }

    private fun persistLatestSnapshot() {
        while (true) {
            val revision = persistenceRevision.get()
            val snapshot = lock.read { events.toList() }
            beforePersist?.invoke()
            val persisted = saveToDisk(snapshot)

            synchronized(persistenceMonitor) {
                if (persisted) {
                    persistedRevision = revision
                    persistenceMonitor.notifyAll()
                }
                if (persistenceRevision.get() == revision) {
                    persistenceScheduled = false
                    persistenceMonitor.notifyAll()
                    return
                }
            }
        }
    }

    private fun saveToDisk(snapshot: List<MGMEvent>): Boolean {
        return try {
            if (!storageDir.exists()) {
                storageDir.mkdirs()
            }

            val content = json.encodeToString(snapshot)

            // Atomic write using temp file
            val tempFile = File(storageDir, "events.json.tmp")
            tempFile.writeText(content)
            if (!tempFile.renameTo(storageFile)) {
                throw IOException("Failed to replace persisted event store")
            }
            true
        } catch (e: Exception) {
            // Silent failure - events remain in memory
            MGMLogger.error("Failed to save events to disk: ${e.message}")
            false
        }
    }

    /**
     * Wait for all event mutations queued before this call to be persisted.
     *
     * This is intended for lifecycle boundaries where the process may be stopped
     * before the normal coalescing delay expires.
     *
     * @return true when persistence completed, or false when [timeoutMs] elapsed
     *         or persistence can no longer be scheduled
     */
    @JvmOverloads
    fun awaitPersistence(timeoutMs: Long = 5_000): Boolean {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        synchronized(persistenceMonitor) {
            val targetRevision = persistenceRevision.get()
            while (persistedRevision < targetRevision) {
                if (!persistenceScheduled) return false
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0) return false
                TimeUnit.NANOSECONDS.timedWait(persistenceMonitor, remainingNanos)
            }
        }
        return true
    }

    internal fun closeForTesting() {
        awaitPersistence()
        persistenceExecutor.shutdownNow()
    }

    override fun close() {
        persistenceExecutor.shutdown()
    }

    private companion object {
        const val PERSISTENCE_DELAY_MS = 25L
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
        val eventIds = events.mapTo(mutableSetOf()) { it.clientEventId }
        lock.write {
            this.events.removeAll { it.clientEventId in eventIds }
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

package com.mostlygoodmetrics.sdk

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class EventStorageTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var storage: InMemoryEventStorage

    @Before
    fun setup() {
        storage = InMemoryEventStorage(maxEvents = 100)
    }

    // MARK: - Basic Storage Tests

    @Test
    fun `initial storage is empty`() {
        assertEquals(0, storage.eventCount())
    }

    @Test
    fun `store adds event`() {
        val event = createTestEvent("test1")
        storage.store(event)

        assertEquals(1, storage.eventCount())
    }

    @Test
    fun `store multiple events`() {
        storage.store(createTestEvent("event1"))
        storage.store(createTestEvent("event2"))
        storage.store(createTestEvent("event3"))

        assertEquals(3, storage.eventCount())
    }

    // MARK: - Fetch Tests

    @Test
    fun `fetchEvents returns events in order`() {
        val event1 = createTestEvent("test1")
        val event2 = createTestEvent("test2")
        val event3 = createTestEvent("test3")

        storage.store(event1)
        storage.store(event2)
        storage.store(event3)

        val fetched = storage.fetchEvents(10)

        assertEquals(3, fetched.size)
        assertEquals("test1", fetched[0].name)
        assertEquals("test2", fetched[1].name)
        assertEquals("test3", fetched[2].name)
    }

    @Test
    fun `fetchEvents respects limit`() {
        repeat(10) { i ->
            storage.store(createTestEvent("event$i"))
        }

        val fetched = storage.fetchEvents(5)

        assertEquals(5, fetched.size)
        assertEquals("event0", fetched[0].name)
        assertEquals("event4", fetched[4].name)
    }

    @Test
    fun `fetchEvents with limit larger than count returns all events`() {
        storage.store(createTestEvent("event1"))
        storage.store(createTestEvent("event2"))

        val fetched = storage.fetchEvents(100)

        assertEquals(2, fetched.size)
    }

    @Test
    fun `fetchEvents returns empty list when storage is empty`() {
        val fetched = storage.fetchEvents(10)

        assertTrue(fetched.isEmpty())
    }

    @Test
    fun `fetchEvents with zero limit returns empty list`() {
        storage.store(createTestEvent("event1"))

        val fetched = storage.fetchEvents(0)

        assertTrue(fetched.isEmpty())
    }

    @Test
    fun `fetchEvents does not remove events`() {
        storage.store(createTestEvent("event1"))
        storage.store(createTestEvent("event2"))

        storage.fetchEvents(10)
        storage.fetchEvents(10)

        assertEquals(2, storage.eventCount())
    }

    // MARK: - Remove Tests

    @Test
    fun `removeEvents removes specified events`() {
        val event1 = createTestEvent("test1")
        val event2 = createTestEvent("test2")
        val event3 = createTestEvent("test3")

        storage.store(event1)
        storage.store(event2)
        storage.store(event3)

        storage.removeEvents(listOf(event1, event2))

        assertEquals(1, storage.eventCount())
        assertEquals("test3", storage.fetchEvents(10)[0].name)
    }

    @Test
    fun `removeEvents keys removal on client event id`() {
        val acknowledged = createTestEvent("duplicate").copy(
            clientEventId = "acknowledged-id",
            timestamp = "2024-01-01T00:00:00.000Z"
        )
        val unacknowledged = acknowledged.copy(clientEventId = "unacknowledged-id")
        storage.store(acknowledged)
        storage.store(unacknowledged)

        storage.removeEvents(listOf(acknowledged.copy(name = "server_copy")))

        val remaining = storage.fetchEvents(10)
        assertEquals(1, remaining.size)
        assertEquals("unacknowledged-id", remaining.single().clientEventId)
    }

    @Test
    fun `removeEvents with empty list does nothing`() {
        storage.store(createTestEvent("event1"))
        storage.store(createTestEvent("event2"))

        storage.removeEvents(emptyList())

        assertEquals(2, storage.eventCount())
    }

    @Test
    fun `removeEvents handles events not in storage`() {
        val event1 = createTestEvent("event1")
        val event2 = createTestEvent("event2")
        val notStored = createTestEvent("not_stored")

        storage.store(event1)
        storage.store(event2)

        // Should not throw
        storage.removeEvents(listOf(notStored))

        assertEquals(2, storage.eventCount())
    }

    @Test
    fun `removeEvents removes all specified events`() {
        val events = (1..5).map { createTestEvent("event$it") }
        events.forEach { storage.store(it) }

        storage.removeEvents(events.take(3))

        assertEquals(2, storage.eventCount())
        val remaining = storage.fetchEvents(10)
        assertEquals("event4", remaining[0].name)
        assertEquals("event5", remaining[1].name)
    }

    // MARK: - Clear Tests

    @Test
    fun `clear removes all events`() {
        repeat(5) { storage.store(createTestEvent("event$it")) }

        assertEquals(5, storage.eventCount())

        storage.clear()

        assertEquals(0, storage.eventCount())
    }

    @Test
    fun `clear on empty storage does nothing`() {
        storage.clear()

        assertEquals(0, storage.eventCount())
    }

    @Test
    fun `storage can be reused after clear`() {
        storage.store(createTestEvent("event1"))
        storage.clear()
        storage.store(createTestEvent("event2"))

        assertEquals(1, storage.eventCount())
        assertEquals("event2", storage.fetchEvents(1)[0].name)
    }

    // MARK: - Max Events / Rotation Tests

    @Test
    fun `storage rotates when max events exceeded`() {
        val smallStorage = InMemoryEventStorage(maxEvents = 3)

        smallStorage.store(createTestEvent("event1"))
        smallStorage.store(createTestEvent("event2"))
        smallStorage.store(createTestEvent("event3"))
        smallStorage.store(createTestEvent("event4"))
        smallStorage.store(createTestEvent("event5"))

        assertEquals(3, smallStorage.eventCount())

        val events = smallStorage.fetchEvents(10)
        assertEquals("event3", events[0].name) // oldest remaining
        assertEquals("event5", events[2].name) // newest
    }

    @Test
    fun `rotation preserves newest events`() {
        val smallStorage = InMemoryEventStorage(maxEvents = 2)

        smallStorage.store(createTestEvent("old1"))
        smallStorage.store(createTestEvent("old2"))
        smallStorage.store(createTestEvent("new1"))

        val events = smallStorage.fetchEvents(10)
        assertEquals(2, events.size)
        assertEquals("old2", events[0].name)
        assertEquals("new1", events[1].name)
    }

    @Test
    fun `max events of 1 keeps only last event`() {
        val tinyStorage = InMemoryEventStorage(maxEvents = 1)

        tinyStorage.store(createTestEvent("event1"))
        tinyStorage.store(createTestEvent("event2"))
        tinyStorage.store(createTestEvent("event3"))

        assertEquals(1, tinyStorage.eventCount())
        assertEquals("event3", tinyStorage.fetchEvents(1)[0].name)
    }

    // MARK: - Thread Safety Tests

    @Test
    fun `concurrent access is thread-safe`() {
        val threads = (1..10).map { threadId ->
            Thread {
                repeat(100) { i ->
                    storage.store(createTestEvent("thread${threadId}_event$i"))
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // With maxEvents = 100 and rotation, we should have exactly 100 events
        assertEquals(100, storage.eventCount())
    }

    @Test
    fun `concurrent store and fetch is thread-safe`() {
        val latch = CountDownLatch(2)
        val fetchedCount = AtomicInteger(0)

        val storeThread = Thread {
            repeat(50) { i ->
                storage.store(createTestEvent("event$i"))
                Thread.sleep(1)
            }
            latch.countDown()
        }

        val fetchThread = Thread {
            repeat(50) {
                fetchedCount.addAndGet(storage.fetchEvents(10).size)
                Thread.sleep(1)
            }
            latch.countDown()
        }

        storeThread.start()
        fetchThread.start()

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        // Should complete without exceptions
    }

    @Test
    fun `concurrent store and remove is thread-safe`() {
        // Pre-populate storage
        val initialEvents = (1..50).map { createTestEvent("initial$it") }
        initialEvents.forEach { storage.store(it) }

        val latch = CountDownLatch(2)

        val storeThread = Thread {
            repeat(50) { i ->
                storage.store(createTestEvent("new$i"))
                Thread.sleep(1)
            }
            latch.countDown()
        }

        val removeThread = Thread {
            initialEvents.chunked(5).forEach { chunk ->
                storage.removeEvents(chunk)
                Thread.sleep(5)
            }
            latch.countDown()
        }

        storeThread.start()
        removeThread.start()

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        // Should complete without exceptions
    }

    // MARK: - File Persistence Tests

    @Test
    fun `file persistence never blocks the calling thread`() {
        val persistenceStarted = CountDownLatch(1)
        val releasePersistence = CountDownLatch(1)
        val storage = FileEventStorage(
            storageDir = temporaryFolder.newFolder("non-blocking"),
            persistenceDelayMs = 0,
            beforePersist = {
                persistenceStarted.countDown()
                releasePersistence.await(5, TimeUnit.SECONDS)
            }
        )

        try {
            val storeReturned = CountDownLatch(1)
            Thread {
                storage.store(createTestEvent("main_thread_event"))
                storeReturned.countDown()
            }.start()

            assertTrue(persistenceStarted.await(1, TimeUnit.SECONDS))
            assertTrue(
                "store() must return while persistence is still blocked",
                storeReturned.await(1, TimeUnit.SECONDS)
            )
        } finally {
            releasePersistence.countDown()
            storage.closeForTesting()
        }
    }

    @Test
    fun `file persistence coalesces a burst into one JSON rewrite`() {
        val writes = AtomicInteger(0)
        val directory = temporaryFolder.newFolder("coalesced")
        val storage = FileEventStorage(
            storageDir = directory,
            persistenceDelayMs = 100,
            beforePersist = { writes.incrementAndGet() }
        )

        try {
            repeat(100) { storage.store(createTestEvent("event$it")) }

            assertTrue(storage.awaitPersistence())
            assertEquals(1, writes.get())

            val contents = File(directory, "events.json").readText()
            assertTrue(contents.startsWith("["))
            assertTrue(contents.endsWith("]"))
            assertEquals(100, kotlinx.serialization.json.Json.decodeFromString<List<MGMEvent>>(contents).size)
        } finally {
            storage.closeForTesting()
        }
    }

    @Test
    fun `app background waits for lifecycle event persistence before flushing`() {
        val persistenceStarted = CountDownLatch(1)
        val releasePersistence = CountDownLatch(1)
        val backgroundHandlingReturned = CountDownLatch(1)
        val directory = temporaryFolder.newFolder("background-persistence")
        val storage = FileEventStorage(
            storageDir = directory,
            persistenceDelayMs = 0,
            beforePersist = {
                persistenceStarted.countDown()
                releasePersistence.await(5, TimeUnit.SECONDS)
            }
        )
        val configuration = MGMConfiguration.Builder("test-api-key")
            .trackAppLifecycleEvents(false)
            .build()
        val sdk = MostlyGoodMetrics.createForTesting(
            configuration = configuration,
            storage = storage,
            networkClient = MockNetworkClient(
                SendResult.RetryLater(MGMError.ServerError(503, "retry"))
            )
        )

        try {
            Thread {
                sdk.handleAppBackgrounded()
                backgroundHandlingReturned.countDown()
            }.start()

            assertTrue(persistenceStarted.await(1, TimeUnit.SECONDS))
            assertFalse(
                "Background handling must wait for the lifecycle event to reach disk",
                backgroundHandlingReturned.await(100, TimeUnit.MILLISECONDS)
            )

            releasePersistence.countDown()
            assertTrue(backgroundHandlingReturned.await(1, TimeUnit.SECONDS))

            val contents = File(directory, "events.json").readText()
            val persistedEvents =
                kotlinx.serialization.json.Json.decodeFromString<List<MGMEvent>>(contents)
            assertEquals(listOf("\$app_backgrounded"), persistedEvents.map { it.name })
        } finally {
            releasePersistence.countDown()
            backgroundHandlingReturned.await(1, TimeUnit.SECONDS)
            sdk.shutdown()
        }
    }

    @Test
    fun `file storage removes only the matching client event id`() {
        val directory = temporaryFolder.newFolder("stable-id-removal")
        val storage = FileEventStorage(storageDir = directory, persistenceDelayMs = 0)
        val acknowledged = createTestEvent("duplicate").copy(
            clientEventId = "acknowledged-id",
            timestamp = "2024-01-01T00:00:00.000Z"
        )
        val unacknowledged = acknowledged.copy(clientEventId = "unacknowledged-id")

        storage.store(acknowledged)
        storage.store(unacknowledged)
        storage.removeEvents(listOf(acknowledged.copy(name = "server_copy")))
        assertTrue(storage.awaitPersistence())
        storage.closeForTesting()

        val reloaded = FileEventStorage(storageDir = directory, persistenceDelayMs = 0)
        try {
            val remaining = reloaded.fetchEvents(10)
            assertEquals(1, remaining.size)
            assertEquals("unacknowledged-id", remaining.single().clientEventId)
        } finally {
            reloaded.closeForTesting()
        }
    }

    // MARK: - Edge Cases

    @Test
    fun `storage handles events with same name`() {
        storage.store(createTestEvent("duplicate"))
        storage.store(createTestEvent("duplicate"))
        storage.store(createTestEvent("duplicate"))

        assertEquals(3, storage.eventCount())
    }

    @Test
    fun `storage handles events with all fields populated`() {
        val event = MGMEvent(
            name = "full_event",
            clientEventId = "550e8400-e29b-41d4-a716-446655440000",
            timestamp = "2024-01-01T00:00:00.000Z",
            userId = "user-123",
            sessionId = "session-456",
            platform = "android",
            appVersion = "1.0.0",
            osVersion = "14",
            environment = "production"
        )

        storage.store(event)

        val fetched = storage.fetchEvents(1)[0]
        assertEquals("full_event", fetched.name)
        assertEquals("user-123", fetched.userId)
        assertEquals("session-456", fetched.sessionId)
    }

    @Test
    fun `storage handles events with minimal fields`() {
        val event = MGMEvent(
            name = "minimal",
            clientEventId = "550e8400-e29b-41d4-a716-446655440000",
            timestamp = "2024-01-01T00:00:00.000Z"
        )

        storage.store(event)

        val fetched = storage.fetchEvents(1)[0]
        assertEquals("minimal", fetched.name)
        assertNull(fetched.userId)
        assertNull(fetched.sessionId)
    }

    private fun createTestEvent(name: String): MGMEvent {
        return MGMEvent(
            name = name,
            clientEventId = java.util.UUID.randomUUID().toString(),
            timestamp = "2024-01-01T00:00:00.000Z"
        )
    }
}

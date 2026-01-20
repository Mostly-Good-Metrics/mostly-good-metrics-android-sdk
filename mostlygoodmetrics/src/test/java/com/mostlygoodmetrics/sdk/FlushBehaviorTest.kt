package com.mostlygoodmetrics.sdk

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Tests for flush behavior with different network results.
 *
 * Verifies that:
 * - Events are removed from storage on Success
 * - Events are removed from storage on DropEvents
 * - Events are kept in storage on RetryLater
 */
class FlushBehaviorTest {

    private lateinit var storage: InMemoryEventStorage
    private lateinit var configuration: MGMConfiguration

    @Before
    fun setup() {
        storage = InMemoryEventStorage(maxEvents = 100)
        configuration = MGMConfiguration.Builder("test-api-key")
            .enableDebugLogging(false)
            .trackAppLifecycleEvents(false)
            .build()
    }

    // MARK: - Success Tests

    @Test
    fun `events are removed from storage on Success`() = runBlocking {
        val mockNetwork = MockNetworkClient(SendResult.Success)
        val sdk = MostlyGoodMetrics.createForTesting(configuration, storage, mockNetwork)

        // Store some events
        sdk.track("event1")
        sdk.track("event2")
        sdk.track("event3")

        assertEquals(3, storage.eventCount())

        // Flush and wait
        val latch = CountDownLatch(1)
        sdk.flush { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))

        // Events should be removed
        assertEquals(0, storage.eventCount())
        sdk.shutdown()
    }

    @Test
    fun `all batches are sent on Success`() = runBlocking {
        val mockNetwork = MockNetworkClient(SendResult.Success)
        // Use batch size of 2 for sending, but store fewer events to avoid auto-flush
        val smallBatchConfig = MGMConfiguration.Builder("test-api-key")
            .maxBatchSize(2)
            .enableDebugLogging(false)
            .trackAppLifecycleEvents(false)
            .build()

        // Create storage with events pre-loaded to avoid auto-flush during track()
        val preloadedStorage = InMemoryEventStorage(maxEvents = 100)
        repeat(5) {
            preloadedStorage.store(MGMEvent(name = "event$it", clientEventId = java.util.UUID.randomUUID().toString(), timestamp = "2024-01-01T00:00:00.000Z"))
        }

        val sdk = MostlyGoodMetrics.createForTesting(smallBatchConfig, preloadedStorage, mockNetwork)

        assertEquals(5, preloadedStorage.eventCount())

        val latch = CountDownLatch(1)
        sdk.flush { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))

        // All events should be removed
        assertEquals(0, preloadedStorage.eventCount())
        // Should have sent 3 batches (2, 2, 1)
        assertTrue(mockNetwork.sendCount >= 3)
        sdk.shutdown()
    }

    // MARK: - DropEvents Tests

    @Test
    fun `events are removed from storage on DropEvents`() = runBlocking {
        val mockNetwork = MockNetworkClient(SendResult.DropEvents(MGMError.BadRequest("Invalid data")))
        val sdk = MostlyGoodMetrics.createForTesting(configuration, storage, mockNetwork)

        sdk.track("bad_event1")
        sdk.track("bad_event2")

        assertEquals(2, storage.eventCount())

        val latch = CountDownLatch(1)
        sdk.flush { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))

        // Events should be dropped (removed)
        assertEquals(0, storage.eventCount())
        sdk.shutdown()
    }

    @Test
    fun `events are removed on Unauthorized error`() = runBlocking {
        val mockNetwork = MockNetworkClient(SendResult.DropEvents(MGMError.Unauthorized))
        val sdk = MostlyGoodMetrics.createForTesting(configuration, storage, mockNetwork)

        sdk.track("event1")
        assertEquals(1, storage.eventCount())

        val latch = CountDownLatch(1)
        sdk.flush { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))

        // Events should be dropped on auth error
        assertEquals(0, storage.eventCount())
        sdk.shutdown()
    }

    @Test
    fun `events are removed on Forbidden error`() = runBlocking {
        val mockNetwork = MockNetworkClient(SendResult.DropEvents(MGMError.Forbidden("Access denied")))
        val sdk = MostlyGoodMetrics.createForTesting(configuration, storage, mockNetwork)

        sdk.track("event1")
        assertEquals(1, storage.eventCount())

        val latch = CountDownLatch(1)
        sdk.flush { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))

        assertEquals(0, storage.eventCount())
        sdk.shutdown()
    }

    // MARK: - RetryLater Tests

    @Test
    fun `events are kept in storage on RetryLater`() = runBlocking {
        val mockNetwork = MockNetworkClient(SendResult.RetryLater(MGMError.NetworkError(Exception("Connection failed"))))
        val sdk = MostlyGoodMetrics.createForTesting(configuration, storage, mockNetwork)

        sdk.track("event1")
        sdk.track("event2")

        assertEquals(2, storage.eventCount())

        val latch = CountDownLatch(1)
        sdk.flush { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))

        // Events should be kept for retry
        assertEquals(2, storage.eventCount())
        sdk.shutdown()
    }

    @Test
    fun `events are kept on RateLimited error`() = runBlocking {
        val mockNetwork = MockNetworkClient(SendResult.RetryLater(MGMError.RateLimited(60)))
        val sdk = MostlyGoodMetrics.createForTesting(configuration, storage, mockNetwork)

        sdk.track("event1")
        assertEquals(1, storage.eventCount())

        val latch = CountDownLatch(1)
        sdk.flush { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))

        // Events should be kept on rate limit
        assertEquals(1, storage.eventCount())
        sdk.shutdown()
    }

    @Test
    fun `events are kept on ServerError`() = runBlocking {
        val mockNetwork = MockNetworkClient(SendResult.RetryLater(MGMError.ServerError(500, "Internal error")))
        val sdk = MostlyGoodMetrics.createForTesting(configuration, storage, mockNetwork)

        sdk.track("event1")
        assertEquals(1, storage.eventCount())

        val latch = CountDownLatch(1)
        sdk.flush { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))

        // Events should be kept on server error
        assertEquals(1, storage.eventCount())
        sdk.shutdown()
    }

    @Test
    fun `events are kept on UnexpectedStatusCode`() = runBlocking {
        val mockNetwork = MockNetworkClient(SendResult.RetryLater(MGMError.UnexpectedStatusCode(418)))
        val sdk = MostlyGoodMetrics.createForTesting(configuration, storage, mockNetwork)

        sdk.track("event1")
        assertEquals(1, storage.eventCount())

        val latch = CountDownLatch(1)
        sdk.flush { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))

        // Events should be kept
        assertEquals(1, storage.eventCount())
        sdk.shutdown()
    }

    // MARK: - Mixed Results Tests

    @Test
    fun `retry stops processing remaining batches`() = runBlocking {
        // First batch succeeds, second fails with retry
        val mockNetwork = SequentialMockNetworkClient(listOf(
            SendResult.Success,
            SendResult.RetryLater(MGMError.ServerError(503, "Service unavailable"))
        ))

        val smallBatchConfig = MGMConfiguration.Builder("test-api-key")
            .maxBatchSize(2)
            .enableDebugLogging(false)
            .trackAppLifecycleEvents(false)
            .build()

        // Pre-load storage to avoid auto-flush during track()
        val preloadedStorage = InMemoryEventStorage(maxEvents = 100)
        repeat(4) {
            preloadedStorage.store(MGMEvent(name = "event$it", clientEventId = java.util.UUID.randomUUID().toString(), timestamp = "2024-01-01T00:00:00.000Z"))
        }

        val sdk = MostlyGoodMetrics.createForTesting(smallBatchConfig, preloadedStorage, mockNetwork)

        assertEquals(4, preloadedStorage.eventCount())

        val latch = CountDownLatch(1)
        sdk.flush { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))

        // First batch (2 events) removed, second batch (2 events) kept for retry
        assertEquals(2, preloadedStorage.eventCount())
        sdk.shutdown()
    }

    @Test
    fun `events can be retried successfully after failure`() = runBlocking {
        // First attempt fails, second succeeds
        val mockNetwork = SequentialMockNetworkClient(listOf(
            SendResult.RetryLater(MGMError.NetworkError(Exception("Timeout"))),
            SendResult.Success
        ))
        val sdk = MostlyGoodMetrics.createForTesting(configuration, storage, mockNetwork)

        sdk.track("event1")
        assertEquals(1, storage.eventCount())

        // First flush - should fail and keep events
        val latch1 = CountDownLatch(1)
        sdk.flush { latch1.countDown() }
        assertTrue(latch1.await(5, TimeUnit.SECONDS))
        assertEquals(1, storage.eventCount())

        // Second flush - should succeed and remove events
        val latch2 = CountDownLatch(1)
        sdk.flush { latch2.countDown() }
        assertTrue(latch2.await(5, TimeUnit.SECONDS))
        assertEquals(0, storage.eventCount())

        sdk.shutdown()
    }

    // MARK: - Edge Cases

    @Test
    fun `flush with empty storage does nothing`() = runBlocking {
        val mockNetwork = MockNetworkClient(SendResult.Success)
        val sdk = MostlyGoodMetrics.createForTesting(configuration, storage, mockNetwork)

        assertEquals(0, storage.eventCount())

        val latch = CountDownLatch(1)
        sdk.flush { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))

        assertEquals(0, mockNetwork.sendCount)
        sdk.shutdown()
    }

    @Test
    fun `new events added during retry are preserved`() = runBlocking {
        val mockNetwork = MockNetworkClient(SendResult.RetryLater(MGMError.NetworkError(Exception("Failed"))))
        val sdk = MostlyGoodMetrics.createForTesting(configuration, storage, mockNetwork)

        sdk.track("event1")

        val latch = CountDownLatch(1)
        sdk.flush { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))

        // Add another event after failed flush
        sdk.track("event2")

        // Both events should be in storage
        assertEquals(2, storage.eventCount())
        sdk.shutdown()
    }
}

/**
 * Mock network client that always returns the same result.
 */
class MockNetworkClient(
    private val result: SendResult,
    var experimentsResult: ExperimentsResult = ExperimentsResult.Success(ExperimentsResponse())
) : NetworkClientInterface {
    var sendCount = 0
        private set
    var fetchExperimentsCount = 0
        private set
    var lastFetchedUserId: String? = null
        private set

    override suspend fun sendEvents(payload: MGMEventsPayload): SendResult {
        sendCount++
        return result
    }

    override suspend fun fetchExperiments(userId: String): ExperimentsResult {
        fetchExperimentsCount++
        lastFetchedUserId = userId
        return experimentsResult
    }
}

/**
 * Mock network client that returns results in sequence.
 */
class SequentialMockNetworkClient(
    private val results: List<SendResult>,
    private val experimentsResult: ExperimentsResult = ExperimentsResult.Success(ExperimentsResponse())
) : NetworkClientInterface {
    private var callIndex = 0

    override suspend fun sendEvents(payload: MGMEventsPayload): SendResult {
        val result = results.getOrElse(callIndex) { results.last() }
        callIndex++
        return result
    }

    override suspend fun fetchExperiments(userId: String): ExperimentsResult {
        return experimentsResult
    }
}

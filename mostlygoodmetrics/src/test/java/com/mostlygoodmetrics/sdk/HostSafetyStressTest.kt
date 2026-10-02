package com.mostlygoodmetrics.sdk

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class HostSafetyStressTest {
    @get:Rule val folder = TemporaryFolder()
    private fun config() = MGMConfiguration.Builder("offline-test-key").trackAppLifecycleEvents(false).maxStoredEvents(100).build()
    private fun event(name: String = "safe_event") = MGMEvent.create(name)!!

    @Test
    fun `interrupted persistence wait returns false and preserves interrupt status`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val storage = FileEventStorage(folder.newFolder(), persistenceDelayMs = 0, beforePersist = {
            started.countDown(); release.await(5, TimeUnit.SECONDS)
        })
        val result = AtomicReference<Boolean>()
        val failure = AtomicReference<Throwable>()
        val done = CountDownLatch(1)
        val waiter = Thread {
            try {
                Thread.currentThread().interrupt()
                result.set(storage.awaitPersistence())
                assertTrue(Thread.currentThread().isInterrupted)
            } catch (error: Throwable) { failure.set(error) }
            finally { done.countDown() }
        }
        try {
            storage.store(event())
            assertTrue(started.await(5, TimeUnit.SECONDS))
            waiter.start()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertEquals(false, result.get())
        } finally { release.countDown(); storage.closeForTesting(); waiter.join(1000) }
    }

    @Test
    fun `deep corrupt cached JSON cannot overflow parser and fresh events recover`() {
        val dir = folder.newFolder()
        val depth = 20_000
        val cache = "[{\"name\":\"safe_event\",\"client_event_id\":\"cache-id\",\"timestamp\":\"2026-10-02\",\"properties\":{\"nested\":" +
            "[".repeat(depth) + "0" + "]".repeat(depth) + "}}]"
        File(dir, "events.json").writeText(cache)
        val storage = FileEventStorage(dir)
        try {
            assertEquals(0, storage.eventCount())
            storage.store(event())
            assertTrue(storage.awaitPersistence())
            assertEquals(1, storage.eventCount())
        } finally { storage.closeForTesting() }
    }

    @Test
    fun `cache reload obeys current capacity before first tracking call`() {
        val dir = folder.newFolder()
        File(dir, "events.json").writeText(Json.encodeToString((1..8).map { event("event_$it") }))
        val storage = FileEventStorage(dir, maxEvents = 2)
        try { assertEquals(listOf("event_7", "event_8"), storage.fetchEvents(10).map { it.name }) }
        finally { storage.closeForTesting() }
    }

    @Test
    fun `negative direct storage capacities drop events without removing past empty queue`() {
        val disk = FileEventStorage(folder.newFolder(), maxEvents = -1)
        val memory = InMemoryEventStorage(maxEvents = -1)
        try {
            disk.store(event()); memory.store(event())
            assertEquals(0, disk.eventCount()); assertEquals(0, memory.eventCount())
        } finally { disk.closeForTesting() }
    }

    @Test
    fun `repeated disk failures preserve memory and recover without wedging executor`() {
        val dir = File(folder.root, "temporarily-unavailable")
        dir.writeText("blocks directory creation")
        val storage = FileEventStorage(dir, maxEvents = 100, persistenceDelayMs = 0)
        try {
            repeat(4) { index ->
                storage.store(event("failure_$index"))
                assertFalse(storage.awaitPersistence(2000))
            }
            assertEquals(4, storage.eventCount())
            assertTrue(dir.delete()); assertTrue(dir.mkdir())
            storage.store(event("recovered"))
            assertTrue(storage.awaitPersistence())
            assertEquals(5, Json.decodeFromString<List<MGMEvent>>(File(dir, "events.json").readText()).size)
        } finally { storage.closeForTesting() }
    }

    @Test
    fun `real concurrent callers retain bounded queue and recover after identity privacy flush churn`() {
        val storage = InMemoryEventStorage(100)
        val sendAttempts = AtomicInteger()
        val network = object : NetworkClientInterface {
            override suspend fun sendEvents(payload: MGMEventsPayload): SendResult =
                if (sendAttempts.incrementAndGet() % 3 == 0) throw IllegalStateException("transient failure") else SendResult.Success
            override suspend fun fetchExperiments(userId: String, anonymousId: String?) = ExperimentsResult.Success(emptyMap())
        }
        val sdk = MostlyGoodMetrics.createForTesting(config(), storage, network)
        val pool = Executors.newFixedThreadPool(8)
        val start = CyclicBarrier(8)
        try {
            val futures = (0..7).map { worker -> pool.submit {
                start.await(5, TimeUnit.SECONDS)
                repeat(300) { index -> when (worker) {
                    in 0..3 -> sdk.track("worker_event", mapOf("worker" to worker, "index" to index))
                    4 -> { sdk.identify("user_$index"); sdk.resetIdentity() }
                    5 -> { sdk.resetAnonymousId(); sdk.startNewSession() }
                    6 -> { sdk.optOut(); sdk.optIn() }
                    else -> if (index % 10 == 0) sdk.flush()
                } }
            } }
            futures.forEach { it.get(15, TimeUnit.SECONDS) }
            assertTrue(storage.eventCount() <= 100)
            sdk.optIn(); sdk.identify("final-user"); sdk.clearPendingEvents(); sdk.track("final_event")
            val retained = storage.fetchEvents(100).last()
            assertEquals("final_event", retained.name)
            assertEquals("final-user", retained.userId)
        } finally { sdk.shutdown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    @Test
    fun `shutdown cancels actual background send and retains unsent events`() {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val storage = InMemoryEventStorage()
        val network = object : NetworkClientInterface {
            override suspend fun sendEvents(payload: MGMEventsPayload): SendResult {
                entered.countDown()
                try { awaitCancellation() } finally { cancelled.countDown() }
            }
            override suspend fun fetchExperiments(userId: String, anonymousId: String?) = ExperimentsResult.Success(emptyMap())
        }
        val sdk = MostlyGoodMetrics.createForTesting(config(), storage, network)
        val completed = AtomicInteger()
        try {
            sdk.track("unsent_event"); sdk.flush { completed.incrementAndGet() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            sdk.shutdown()
            assertTrue(cancelled.await(5, TimeUnit.SECONDS))
            assertEquals(0, completed.get()); assertEquals(1, storage.eventCount())
        } finally { sdk.shutdown() }
    }

    @Test
    fun `lifecycle registration and removal failures do not escape configure or shutdown`() {
        val lifecycle = object : Lifecycle() {
            override val currentState: State get() = State.CREATED
            override fun addObserver(observer: LifecycleObserver) { throw IllegalStateException("lifecycle unavailable") }
            override fun removeObserver(observer: LifecycleObserver) { throw IllegalStateException("lifecycle unavailable") }
        }
        val sdk = MostlyGoodMetrics.createForTesting(MGMConfiguration.Builder("offline-key").build(),
            InMemoryEventStorage(), MockNetworkClient(SendResult.Success), processLifecycle = lifecycle)
        sdk.track("still_works"); sdk.shutdown(); sdk.shutdown()
    }

    @Test
    fun `main dispatcher failure does not escape opted out flush`() {
        val sdk = MostlyGoodMetrics.createForTesting(config(), InMemoryEventStorage(), MockNetworkClient(SendResult.Success),
            dispatchLifecycleAction = { throw IllegalStateException("main dispatcher unavailable") })
        try { sdk.optOut(); sdk.flush { fail("undeliverable callback") } } finally { sdk.shutdown() }
    }
    @Test
    fun `deep native super property cache is rejected before recursive org json parse`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("super_properties", "{\"nested\":" + "[".repeat(20_000) + "0" + "]".repeat(20_000) + "}").apply()
        val storage = InMemoryEventStorage()
        val sdk = MostlyGoodMetrics.createForTesting(config(), storage, MockNetworkClient(SendResult.Success), prefs)
        try { sdk.track("recovered"); assertEquals(1, storage.eventCount()) }
        finally { sdk.shutdown() }
    }

    @Test
    fun `large property payload is omitted before it can fill persisted queue`() {
        val properties = (1..512).associate { "key_$it" to "x".repeat(1000) }
        val tracked = MGMEvent.create("safe_event", properties = properties)!!
        assertNull("10KB property limit bounds event memory and wire size", tracked.properties)
        assertNotNull(MGMEvent.create("following_event", properties = mapOf("small" to 1))!!.properties)
    }

    @Test
    fun `throwing top level property map does not escape public event factory`() {
        val broken = object : AbstractMap<String, Any?>() {
            override val size: Int get() = 2
            override val entries: Set<Map.Entry<String, Any?>> get() = throw IllegalStateException("unreadable map")
        }
        assertNotNull(MGMEvent.create("safe_event", properties = broken))
    }

    @Test
    fun `loopback malformed experiment response fails safely and next request recovers`() = runBlocking {
        val deep = AtomicBoolean(true)
        val server = LoopbackServer { _ ->
            val body = if (deep.get()) "{\"assigned_variants\":{\"bad\":" + "[".repeat(20_000) + "0" + "]".repeat(20_000) + "}}"
                else "{\"assigned_variants\":{\"safe\":\"control\"}}"
            Response(body)
        }
        try {
            val client = NetworkClient(MGMConfiguration.Builder("offline-key").baseUrl("http://127.0.0.1:" + server.port).build())
            assertTrue(client.fetchExperiments("test-user") is ExperimentsResult.Failure)
            deep.set(false)
            assertEquals("control", (client.fetchExperiments("test-user") as ExperimentsResult.Success).assignedVariants["safe"])
        } finally { server.close() }
    }

    @Test
    fun `loopback oversized response is bounded and rate limit overflow does not disable backoff`() = runBlocking {
        val server = LoopbackServer { path ->
            if (path.startsWith("/v1/experiments")) Response(" ".repeat(JsonSafety.MAX_METADATA_BYTES + 20_000))
            else Response("", status = 429, headers = "Retry-After: " + Long.MAX_VALUE + "\r\n")
        }
        try {
            val client = NetworkClient(MGMConfiguration.Builder("offline-key").baseUrl("http://127.0.0.1:" + server.port).build())
            assertTrue(client.fetchExperiments("test-user") is ExperimentsResult.Failure)
            assertTrue(client.sendEvents(MGMEventsPayload(listOf(event()))) is SendResult.RetryLater)
            assertTrue("Overflowed Retry-After must not cause immediate retries", client.isRateLimited)
        } finally { server.close() }
    }

    @Test
    fun `json bounds ignore braces and escaped quotes within valid string data`() {
        val text = Json.encodeToString(mapOf("value" to "[ { \" \\ ] }".repeat(100)))
        assertTrue(JsonSafety.isSafe(text))
        assertFalse(JsonSafety.isSafe("{\"nested\":" + "[".repeat(17) + "0" + "]".repeat(17) + "}"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `burst tracking coalesces automatic flush coroutine allocations`() = runTest {
        val delegated = StandardTestDispatcher(testScheduler)
        val dispatches = AtomicInteger()
        val counting = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                dispatches.incrementAndGet()
                delegated.dispatch(context, block)
            }
        }
        val storage = InMemoryEventStorage(100)
        val sdk = MostlyGoodMetrics.createForTesting(config(), storage, MockNetworkClient(SendResult.Success), backgroundDispatcher = counting)
        try {
            runCurrent() // Start the single timer and finish experiments init.
            val before = dispatches.get()
            repeat(5000) { sdk.track("burst_event") }
            assertEquals(100, storage.eventCount())
            assertEquals("A burst must enqueue one automatic flush, not one per event", 1, dispatches.get() - before)
            runCurrent()
            assertEquals(0, storage.eventCount())
        } finally { sdk.shutdown() }
    }

    @Test
    fun `pending count failure returns safe fallback`() {
        val storage = object : EventStorage by InMemoryEventStorage() {
            override fun eventCount(): Int = throw IllegalStateException("store unavailable")
        }
        val sdk = MostlyGoodMetrics.createForTesting(config(), storage, MockNetworkClient(SendResult.Success))
        try { assertEquals(0, sdk.pendingEventCount) } finally { sdk.shutdown() }
    }

    @Test
    fun `retained memory budget bounds high count large event queues and stays correct after removals`() {
        val disk = FileEventStorage(folder.newFolder(), maxEvents = 10_000, persistenceDelayMs = 100)
        val memory = InMemoryEventStorage(10_000)
        try {
            for (storage in listOf<EventStorage>(disk, memory)) {
                repeat(2000) { storage.store(MGMEvent.create("large_event", properties = mapOf("payload" to "x".repeat(1000)))!!) }
                val retained = storage.fetchEvents(10_000)
                assertTrue(retained.size in 1..1000)
                assertTrue(retained.sumOf { EventMemoryBudget.weight(it)!!.toLong() } <= EventMemoryBudget.MAX_BYTES)
                storage.removeEvents(retained.take(retained.size / 2))
                repeat(500) { storage.store(event("after_removal")) }
                assertTrue(storage.fetchEvents(10_000).sumOf { EventMemoryBudget.weight(it)!!.toLong() } <= EventMemoryBudget.MAX_BYTES)
                storage.clear(); storage.store(event("after_clear")); assertEquals(1, storage.eventCount())
            }
            assertTrue(disk.awaitPersistence())
        } finally { disk.closeForTesting() }
    }

    @Test
    fun `oversized cached file is discarded before allocating its entire contents`() {
        val dir = folder.newFolder()
        java.io.RandomAccessFile(File(dir, "events.json"), "rw").use { it.setLength(JsonSafety.MAX_CACHE_BYTES.toLong() + 1) }
        val storage = FileEventStorage(dir)
        try { assertEquals(0, storage.eventCount()); storage.store(event()); assertTrue(storage.awaitPersistence()) }
        finally { storage.closeForTesting() }
    }

    @Test
    fun `completion cancellation exception is contained outside coroutine context`() {
        val sdk = MostlyGoodMetrics.createForTesting(config(), InMemoryEventStorage(), MockNetworkClient(SendResult.Success))
        try {
            sdk.optOut()
            sdk.flush { throw kotlinx.coroutines.CancellationException("host callback cancelled") }
        } finally { sdk.shutdown() }
    }

    private data class Response(val body: String, val status: Int = 200, val headers: String = "")

    private class LoopbackServer(private val respond: (String) -> Response) : java.io.Closeable {
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort
        private val worker = Thread {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader()
                        val path = reader.readLine().split(' ')[1]
                        var length = 0
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
                        }
                        repeat(length) { reader.read() }
                        val response = respond(path)
                        val bytes = response.body.toByteArray()
                        val headers = "HTTP/1.1 ${response.status} Test\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n${response.headers}\r\n"
                        socket.getOutputStream().use { output -> output.write(headers.toByteArray()); output.write(bytes) }
                    }
                } catch (_: java.io.IOException) {
                    // Client deliberately closes oversized responses; close()
                    // also interrupts accept(). Neither is a server failure.
                }
            }
        }.apply { name = "mgm-test-loopback"; isDaemon = true; start() }

        override fun close() { server.close(); worker.join(5000); assertFalse(worker.isAlive) }
    }

}

package com.mostlygoodmetrics.sdk

import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class CrashSafetyTest {
    @Test
    fun `cyclic list property is depth bounded`() {
        val cycle = mutableListOf<Any?>()
        cycle.add(cycle)
        val event = MGMEvent.create("safe_event", properties = mapOf("cycle" to cycle))!!
        assertNotNull(event.properties)
        assertTrue(event.properties.toString().length < 100)
    }

    @Test
    fun `cyclic array property is depth bounded`() {
        val cycle = arrayOfNulls<Any>(1)
        cycle[0] = cycle
        val event = MGMEvent.create("safe_event", properties = mapOf("cycle" to cycle))!!
        assertTrue(event.properties.toString().length < 100)
    }

    @Test
    fun `nested maps skip non string keys without losing valid properties`() {
        val event = MGMEvent.create("safe_event", properties = mapOf("nested" to mapOf(42 to "bad", "good" to "kept")))!!
        val nested = event.properties!!["nested"] as JsonObject
        assertEquals(setOf("good"), nested.keys)
        assertEquals("kept", (nested["good"] as JsonPrimitive).content)
    }

    @Test
    fun `throwing property conversion does not disrupt tracking`() {
        val bad = object { override fun toString(): String = throw IllegalStateException("bad property") }
        val event = MGMEvent.create("safe_event", properties = mapOf("bad" to bad, "good" to 42))!!
        assertEquals(JsonNull, event.properties!!["bad"])
        assertEquals("42", (event.properties!!["good"] as JsonPrimitive).content)
    }

    @Test
    fun `flush completion is dispatched to main boundary`() {
        val actions = LinkedBlockingQueue<() -> Unit>()
        val sdk = sdk(dispatch = { actions.put(it) })
        try {
            val caller = Thread.currentThread()
            var completed = false
            sdk.flush { assertSame(caller, Thread.currentThread()); completed = true }
            val action = actions.poll(5, TimeUnit.SECONDS)
            assertNotNull("Completion must be queued through main dispatcher", action)
            assertFalse(completed)
            action!!.invoke()
            assertTrue(completed)
        } finally { sdk.shutdown() }
    }

    @Test
    fun `opted out completion uses main boundary and contains callback exceptions`() {
        val actions = LinkedBlockingQueue<() -> Unit>()
        val config = configuration().optedOutByDefault(true).build()
        val sdk = sdk(config, dispatch = { actions.put(it) })
        try {
            sdk.flush { throw IllegalStateException("host callback failed") }
            val action = actions.poll(5, TimeUnit.SECONDS)
            assertNotNull(action)
            action!!.invoke() // An exception here would terminate Android's main thread.
        } finally { sdk.shutdown() }
    }

    @Test
    fun `unexpected flush failure reaches completion and leaves events retryable`() {
        val storage = InMemoryEventStorage()
        var fail = true
        val network = object : NetworkClientInterface {
            override suspend fun sendEvents(payload: MGMEventsPayload): SendResult {
                if (fail) throw IllegalStateException("unexpected network exception")
                return SendResult.Success
            }
            override suspend fun fetchExperiments(userId: String, anonymousId: String?) = ExperimentsResult.Success(emptyMap())
        }
        val sdk = MostlyGoodMetrics.createForTesting(configuration().build(), storage, network)
        try {
            sdk.track("safe_event")
            val failed = CountDownLatch(1)
            sdk.flush { assertTrue(it.isFailure); failed.countDown() }
            assertTrue("Failure must complete instead of escaping coroutine", failed.await(5, TimeUnit.SECONDS))
            assertEquals(1, storage.eventCount())
            assertFalse(sdk.isFlushingEvents)
            fail = false
            val succeeded = CountDownLatch(1)
            sdk.flush { assertTrue(it.isSuccess); succeeded.countDown() }
            assertTrue(succeeded.await(5, TimeUnit.SECONDS))
            assertEquals(0, storage.eventCount())
        } finally { sdk.shutdown() }
    }

    @Test
    fun `failed background experiment loads complete ready without uncaught exceptions`() = runTest {
        for (mode in MGMExperimentMode.values()) {
            val network = object : NetworkClientInterface {
                override suspend fun sendEvents(payload: MGMEventsPayload) = SendResult.Success
                override suspend fun fetchExperiments(userId: String, anonymousId: String?): ExperimentsResult =
                    throw IllegalStateException("experiments failed")
                override suspend fun fetchExperimentConfigs(): ExperimentConfigsResult =
                    throw IllegalStateException("experiment configs failed")
            }
            val sdk = MostlyGoodMetrics.createForTesting(configuration().experimentMode(mode).build(),
                InMemoryEventStorage(), network, backgroundDispatcher = StandardTestDispatcher(testScheduler))
            try {
                runCurrent()
                assertTrue(sdk.ready())
                sdk.identify("new-user")
                runCurrent() // Covers the retained identify-triggered experiment fetch too.
            } finally { sdk.shutdown() }
        }
    }

    @Test
    fun `periodic flush survives an ordinary failure and retries next interval`() = runTest {
        var attempts = 0
        val storage = InMemoryEventStorage()
        val network = object : NetworkClientInterface {
            override suspend fun sendEvents(payload: MGMEventsPayload): SendResult {
                if (++attempts == 1) throw IllegalStateException("first attempt failed")
                return SendResult.Success
            }
            override suspend fun fetchExperiments(userId: String, anonymousId: String?) = ExperimentsResult.Success(emptyMap())
        }
        val sdk = MostlyGoodMetrics.createForTesting(configuration().flushIntervalSeconds(1).build(),
            storage, network, backgroundDispatcher = StandardTestDispatcher(testScheduler))
        try {
            sdk.track("safe_event")
            runCurrent()
            advanceTimeBy(1000)
            runCurrent()
            assertEquals(1, attempts)
            assertEquals(1, storage.eventCount())
            advanceTimeBy(1000)
            runCurrent()
            assertEquals(2, attempts)
            assertEquals(0, storage.eventCount())
        } finally { sdk.shutdown() }
    }

    @Test
    fun `cancelled flush does not report success or remove events`() = runTest {
        val storage = InMemoryEventStorage()
        val network = object : NetworkClientInterface {
            override suspend fun sendEvents(payload: MGMEventsPayload): SendResult = throw CancellationException("cancelled")
            override suspend fun fetchExperiments(userId: String, anonymousId: String?) = ExperimentsResult.Success(emptyMap())
        }
        val sdk = MostlyGoodMetrics.createForTesting(configuration().build(), storage, network,
            backgroundDispatcher = StandardTestDispatcher(testScheduler))
        try {
            var completed = false
            sdk.track("safe_event")
            sdk.flush { completed = true }
            runCurrent()
            assertFalse(completed)
            assertFalse(sdk.isFlushingEvents)
            assertEquals(1, storage.eventCount())
        } finally { sdk.shutdown() }
    }

    @Test
    fun `provider result iteration failure omits context and keeps tracking`() {
        val broken = object : AbstractMap<String, Any?>() {
            override val size: Int get() = 2
            override val entries: Set<Map.Entry<String, Any?>> get() = throw IllegalStateException("broken context")
        }
        val storage = InMemoryEventStorage()
        val sdk = MostlyGoodMetrics.createForTesting(configuration().contextProvider { broken }.build(),
            storage, MockNetworkClient(SendResult.Success))
        try {
            sdk.track("safe_event")
            assertEquals(1, storage.eventCount())
            assertEquals("android", (storage.fetchEvents(1).single().properties!!["\$sdk"] as JsonPrimitive).content)
        } finally { sdk.shutdown() }
    }

    @Test
    fun `cyclic super property uses same bounded converter`() {
        val prefs = FakeSharedPreferences()
        val sdk = MostlyGoodMetrics.createForTesting(configuration().build(), InMemoryEventStorage(),
            MockNetworkClient(SendResult.Success), prefs = prefs)
        try {
            val cycle = mutableListOf<Any?>()
            cycle.add(cycle)
            sdk.setSuperProperty("cycle", cycle)
            val persisted = prefs.getString("super_properties", null)
            assertNotNull(persisted)
            assertTrue(persisted!!.length < 100)
        } finally { sdk.shutdown() }
    }

    @Test
    fun `track storage exception is contained on caller thread`() {
        val storage = object : EventStorage by InMemoryEventStorage() {
            override fun store(event: MGMEvent) { throw IllegalStateException("storage unavailable") }
        }
        val sdk = MostlyGoodMetrics.createForTesting(configuration().build(), storage,
            MockNetworkClient(SendResult.Success))
        try { sdk.track("safe_event") } finally { sdk.shutdown() }
    }

    @Test
    fun `failed storage clear does not crash opt out or forget me`() {
        val storage = object : EventStorage by InMemoryEventStorage() {
            override fun clear() { throw IllegalStateException("storage unavailable") }
        }
        val sdk = MostlyGoodMetrics.createForTesting(configuration().build(), storage, MockNetworkClient(SendResult.Success))
        try {
            sdk.optOut()
            assertTrue(sdk.isOptedOut)
            sdk.clearPendingEvents()
            sdk.resetIdentity(clearAnonymousId = true)
            assertNull(sdk.userId)
            sdk.track("must_not_track")
            assertEquals(0, storage.eventCount())
        } finally { sdk.shutdown() }
    }

    @Test
    fun `corrupt preference values do not crash startup and consent fails closed`() {
        val prefs = object : SharedPreferences by FakeSharedPreferences() {
            override fun getBoolean(key: String?, defValue: Boolean): Boolean = throw ClassCastException("bad boolean")
            override fun getString(key: String?, defValue: String?): String? = throw ClassCastException("bad string")
            override fun getLong(key: String?, defValue: Long): Long = throw ClassCastException("bad long")
            override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = throw ClassCastException("bad set")
        }
        val sdk = MostlyGoodMetrics.createForTesting(configuration().build(), InMemoryEventStorage(), MockNetworkClient(SendResult.Success), prefs)
        try {
            assertTrue(sdk.isOptedOut)
            assertNull(sdk.userId)
            assertTrue(sdk.anonymousId.startsWith("\$anon_"))
            sdk.optIn()
            sdk.identify("new-user", UserProfile(email = "test@example.invalid"))
            sdk.track("safe_event")
            assertTrue(sdk.pendingEventCount > 0)
            assertTrue(sdk.getSuperProperties().isEmpty())
        } finally { sdk.shutdown() }
    }

    @Test
    fun `failed preference persistence does not prevent in memory privacy controls`() {
        val prefs = object : SharedPreferences by FakeSharedPreferences() {
            override fun edit(): SharedPreferences.Editor = throw IllegalStateException("persistence unavailable")
        }
        val storage = InMemoryEventStorage()
        val sdk = MostlyGoodMetrics.createForTesting(configuration().build(), storage, MockNetworkClient(SendResult.Success), prefs)
        try {
            sdk.identify("new-user")
            assertEquals("new-user", sdk.userId)
            sdk.track("safe_event")
            assertEquals(1, storage.eventCount())
            sdk.optOut()
            assertTrue(sdk.isOptedOut)
            assertEquals(0, storage.eventCount())
            sdk.resetIdentity(clearAnonymousId = true)
            assertNull(sdk.userId)
        } finally { sdk.shutdown() }
    }

    @Test
    fun `broken super property input is dropped without crashing caller`() {
        val broken = object : AbstractMap<String, Any?>() {
            override val size: Int get() = 2
            override val entries: Set<Map.Entry<String, Any?>> get() = throw IllegalStateException("broken input")
        }
        val sdk = sdk()
        try { sdk.setSuperProperties(broken) } finally { sdk.shutdown() }
    }

    @Test
    fun `wide cyclic property graph has one shared traversal budget`() {
        val cycle = mutableListOf<Any?>()
        repeat(200) { cycle.add(cycle) }
        val event = MGMEvent.create("wide_event", properties = mapOf("cycle" to cycle))!!
        assertTrue("Graph must not expand exponentially", event.properties.toString().length < 10_000)
        assertNotNull(MGMEvent.create("following_event"))
    }

    @Test
    fun `context provider can track a nested event without recursion`() {
        lateinit var sdk: MostlyGoodMetrics
        var providerCalls = 0
        val storage = InMemoryEventStorage()
        val config = configuration().contextProvider {
            providerCalls++
            sdk.track("nested_event")
            mapOf("context" to "outer")
        }.build()
        sdk = MostlyGoodMetrics.createForTesting(config, storage, MockNetworkClient(SendResult.Success))
        try {
            sdk.track("outer_event")
            assertEquals(1, providerCalls)
            val events = storage.fetchEvents(10)
            assertEquals(listOf("nested_event", "outer_event"), events.map { it.name })
            assertFalse(events[0].properties!!.containsKey("context"))
            assertEquals("outer", (events[1].properties!!["context"] as JsonPrimitive).content)
            sdk.track("another_outer")
            assertEquals(2, providerCalls)
        } finally { sdk.shutdown() }
    }

    private fun configuration() = MGMConfiguration.Builder("test-key").trackAppLifecycleEvents(false)
    private fun sdk(config: MGMConfiguration = configuration().build(), dispatch: ((() -> Unit) -> Unit) = { it() }): MostlyGoodMetrics =
        MostlyGoodMetrics.createForTesting(config, InMemoryEventStorage(), MockNetworkClient(SendResult.Success), dispatchLifecycleAction = dispatch)
}

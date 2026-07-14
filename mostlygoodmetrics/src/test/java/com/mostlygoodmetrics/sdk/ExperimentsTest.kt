package com.mostlygoodmetrics.sdk

import android.content.SharedPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for A/B testing support (server-assigned experiment variants).
 *
 * Contract under test:
 * - Variants are only ever assigned by the server (GET /v1/experiments) —
 *   the SDK never buckets locally.
 * - Assignments are cached per user in SharedPreferences with no expiry
 *   (stale-while-revalidate; background refetch throttled to ~1h).
 * - getVariant() is synchronous, never throws, never blocks, and returns
 *   the fallback when unknown or not yet loaded.
 * - ready(timeoutMs) resolves on load, failure, or timeout — never hangs.
 * - Reading a variant sets the $experiment_{name} super property and tracks
 *   $experiment_exposure once per (user, experiment, variant), persisted
 *   across restarts.
 * - identify() with a changed user keeps serving current variants and swaps
 *   atomically once the refetch (including anonymous_id) responds.
 */
class ExperimentsTest {

    private fun testConfiguration(): MGMConfiguration =
        MGMConfiguration.Builder("test-api-key")
            .enableDebugLogging(false)
            .trackAppLifecycleEvents(false)
            .build()

    private fun createSdk(
        network: NetworkClientInterface,
        prefs: SharedPreferences? = null,
        storage: EventStorage = InMemoryEventStorage(maxEvents = 100)
    ): MostlyGoodMetrics {
        return MostlyGoodMetrics.createForTesting(testConfiguration(), storage, network, prefs)
    }

    private fun awaitReady(sdk: MostlyGoodMetrics, timeoutMs: Long = 2_000): Boolean =
        runBlocking { sdk.ready(timeoutMs) }

    private fun waitUntil(timeoutMs: Long = 2_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("Timed out waiting for condition", condition())
    }

    private fun exposureEvents(storage: InMemoryEventStorage): List<MGMEvent> =
        storage.fetchEvents(100).filter { it.name == "\$experiment_exposure" }

    // region getVariant + fallback

    @Test
    fun `getVariant returns server-assigned variant after load`() {
        val network = ControllableExperimentsNetworkClient(
            ExperimentsResult.Success(mapOf("checkout-flow" to "treatment"))
        )
        val sdk = createSdk(network)

        assertTrue(awaitReady(sdk))

        assertEquals("treatment", sdk.getVariant("checkout-flow"))
        // Initial fetch uses the anonymous ID as user_id with no anonymous_id param
        assertEquals(sdk.anonymousId to null, network.fetchCalls.first())

        sdk.shutdown()
    }

    @Test
    fun `getVariant returns fallback before load and for unknown experiments`() {
        val network = ControllableExperimentsNetworkClient(
            ExperimentsResult.Success(mapOf("known" to "a")),
            hangForever = true
        )
        val sdk = createSdk(network)

        // Pre-load: synchronous, non-blocking, serves the fallback
        assertNull(sdk.getVariant("known"))
        assertEquals("control", sdk.getVariant("known", fallback = "control"))

        sdk.shutdown()

        // After a successful load, unknown experiments still serve the fallback —
        // the SDK must never bucket locally.
        val network2 = ControllableExperimentsNetworkClient(
            ExperimentsResult.Success(emptyMap())
        )
        val sdk2 = createSdk(network2)
        assertTrue(awaitReady(sdk2))

        assertNull(sdk2.getVariant("never-assigned"))
        assertEquals("control", sdk2.getVariant("never-assigned", fallback = "control"))

        sdk2.shutdown()
    }

    @Test
    fun `getVariant sets snake_cased experiment super property`() {
        val prefs = FakeSharedPreferences()
        val network = ControllableExperimentsNetworkClient(
            ExperimentsResult.Success(mapOf("checkoutFlow-v2" to "treatment"))
        )
        val sdk = createSdk(network, prefs)
        assertTrue(awaitReady(sdk))

        assertEquals("treatment", sdk.getVariant("checkoutFlow-v2"))

        assertEquals("treatment", sdk.getSuperProperties()["\$experiment_checkout_flow_v2"])

        sdk.shutdown()
    }

    @Test
    fun `snake_case transform matches the JS reference byte-for-byte`() {
        val cases = mapOf(
            "Pricing-Test V2" to "pricing__test__v2",
            "A-B-Test" to "a__b__test",
            "ABTest" to "a_b_test",
            "button-color" to "button_color",
            "myExperiment2" to "my_experiment2",
            "a--b" to "a_b"
        )
        val network = ControllableExperimentsNetworkClient(
            ExperimentsResult.Success(cases.keys.associateWith { "treatment" })
        )
        val sdk = createSdk(network, FakeSharedPreferences())
        assertTrue(awaitReady(sdk))

        cases.forEach { (name, expectedSnake) ->
            assertEquals("treatment", sdk.getVariant(name))
            assertEquals(
                "snake_case(\"$name\") must produce \"$expectedSnake\"",
                "treatment",
                sdk.getSuperProperties()["\$experiment_$expectedSnake"]
            )
        }

        // No extra experiment super properties from a divergent transform
        val experimentKeys = sdk.getSuperProperties().keys.filter { it.startsWith("\$experiment_") }
        assertEquals(cases.size, experimentKeys.size)

        sdk.shutdown()
    }

    // endregion

    // region Exposure tracking + dedup

    @Test
    fun `exposure event is tracked once per user experiment variant`() {
        val prefs = FakeSharedPreferences()
        val storage = InMemoryEventStorage(maxEvents = 100)
        val network = ControllableExperimentsNetworkClient(
            ExperimentsResult.Success(mapOf("checkout-flow" to "treatment"))
        )
        val sdk = createSdk(network, prefs, storage)
        assertTrue(awaitReady(sdk))

        repeat(3) { assertEquals("treatment", sdk.getVariant("checkout-flow")) }

        val exposures = exposureEvents(storage)
        assertEquals("Exposure must be deduplicated", 1, exposures.size)
        val properties = exposures.first().properties
        assertNotNull(properties)
        assertEquals("checkout-flow", properties!!["\$experiment_name"]?.toString()?.trim('"'))
        assertEquals("treatment", properties["\$variant"]?.toString()?.trim('"'))

        sdk.shutdown()
    }

    @Test
    fun `exposure dedup survives a simulated restart`() {
        val prefs = FakeSharedPreferences()
        val network = ControllableExperimentsNetworkClient(
            ExperimentsResult.Success(mapOf("checkout-flow" to "treatment"))
        )

        val storage1 = InMemoryEventStorage(maxEvents = 100)
        val sdk1 = createSdk(network, prefs, storage1)
        assertTrue(awaitReady(sdk1))
        assertEquals("treatment", sdk1.getVariant("checkout-flow"))
        assertEquals(1, exposureEvents(storage1).size)
        sdk1.shutdown()

        // Simulated restart: fresh instance and storage, same persisted prefs
        val storage2 = InMemoryEventStorage(maxEvents = 100)
        val sdk2 = createSdk(network, prefs, storage2)
        assertTrue(awaitReady(sdk2))

        assertEquals("treatment", sdk2.getVariant("checkout-flow"))
        assertEquals("Exposure dedup must persist across restarts", 0, exposureEvents(storage2).size)

        sdk2.shutdown()
    }

    // endregion

    // region Cache: no expiry + stale-while-revalidate + throttle

    @Test
    fun `cached variants are served immediately and never expire`() {
        val prefs = FakeSharedPreferences()
        val anonymousId = "\$anon_cachedidtest"
        val thirtyDaysAgo = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        prefs.edit()
            .putString("anonymous_id", anonymousId)
            .putString("experiment_variants_$anonymousId", """{"checkout-flow":"cached"}""")
            .putLong("experiments_last_fetch_$anonymousId", thirtyDaysAgo)
            .apply()

        // Network never responds — only the (very old) cache can serve
        val network = ControllableExperimentsNetworkClient(
            ExperimentsResult.Success(emptyMap()),
            hangForever = true
        )
        val sdk = createSdk(network, prefs)

        assertEquals(
            "A 30-day-old cache must still be served (no expiry)",
            "cached",
            sdk.getVariant("checkout-flow")
        )

        sdk.shutdown()
    }

    @Test
    fun `stale cache is served immediately then refreshed in the background`() {
        val prefs = FakeSharedPreferences()
        val anonymousId = "\$anon_swrtestuser"
        val twoHoursAgo = System.currentTimeMillis() - 2L * 60 * 60 * 1000
        prefs.edit()
            .putString("anonymous_id", anonymousId)
            .putString("experiment_variants_$anonymousId", """{"checkout-flow":"stale"}""")
            .putLong("experiments_last_fetch_$anonymousId", twoHoursAgo)
            .apply()

        val gate = CompletableDeferred<Unit>()
        val network = ControllableExperimentsNetworkClient(
            ExperimentsResult.Success(mapOf("checkout-flow" to "fresh")),
            gate = gate
        )
        val sdk = createSdk(network, prefs)

        // Stale value served synchronously while the refetch is in flight
        assertEquals("stale", sdk.getVariant("checkout-flow"))

        gate.complete(Unit)
        assertTrue(awaitReady(sdk))

        assertEquals("fresh", sdk.getVariant("checkout-flow"))

        sdk.shutdown()
    }

    @Test
    fun `background refetch is throttled when last fetch was recent`() {
        val prefs = FakeSharedPreferences()
        val anonymousId = "\$anon_throttleuser"
        prefs.edit()
            .putString("anonymous_id", anonymousId)
            .putString("experiment_variants_$anonymousId", """{"checkout-flow":"cached"}""")
            .putLong("experiments_last_fetch_$anonymousId", System.currentTimeMillis())
            .apply()

        val network = ControllableExperimentsNetworkClient(
            ExperimentsResult.Success(mapOf("checkout-flow" to "fresh"))
        )
        val sdk = createSdk(network, prefs)

        assertTrue("ready must resolve immediately when throttled", awaitReady(sdk))
        Thread.sleep(100)

        assertEquals("No network fetch within the throttle window", 0, network.fetchCalls.size)
        assertEquals("cached", sdk.getVariant("checkout-flow"))

        sdk.shutdown()
    }

    // endregion

    // region ready(): timeout + failure

    @Test
    fun `ready times out when the fetch hangs and getVariant stays safe`() {
        val network = ControllableExperimentsNetworkClient(
            ExperimentsResult.Success(mapOf("checkout-flow" to "a")),
            hangForever = true
        )
        val sdk = createSdk(network)

        val start = System.currentTimeMillis()
        val ready = runBlocking { sdk.ready(timeoutMs = 200) }
        val elapsed = System.currentTimeMillis() - start

        assertFalse("ready must return false on timeout", ready)
        assertTrue("ready must not hang past its timeout", elapsed < 2_000)
        assertEquals("fallback", sdk.getVariant("checkout-flow", fallback = "fallback"))

        sdk.shutdown()
    }

    @Test
    fun `ready resolves when the fetch fails`() {
        val network = ControllableExperimentsNetworkClient(
            ExperimentsResult.Failure(MGMError.NetworkError(Exception("Connection failed")))
        )
        val sdk = createSdk(network)

        assertTrue("ready must resolve on fetch failure, not hang", awaitReady(sdk))
        assertNull(sdk.getVariant("checkout-flow"))
        assertEquals("control", sdk.getVariant("checkout-flow", fallback = "control"))

        sdk.shutdown()
    }

    @Test
    fun `ready default timeout is 5 seconds`() {
        assertEquals(5_000L, MostlyGoodMetrics.DEFAULT_READY_TIMEOUT_MS)
    }

    // endregion

    // region identify(): refetch + atomic swap

    @Test
    fun `identify keeps serving current variants then swaps atomically`() {
        val prefs = FakeSharedPreferences()
        val network = ControllableExperimentsNetworkClient(
            ExperimentsResult.Success(mapOf("checkout-flow" to "anon-variant"))
        )
        val sdk = createSdk(network, prefs)
        assertTrue(awaitReady(sdk))
        assertEquals("anon-variant", sdk.getVariant("checkout-flow"))

        // Gate the identify refetch so we can observe the in-flight window
        val gate = CompletableDeferred<Unit>()
        network.gate = gate
        network.result = ExperimentsResult.Success(mapOf("checkout-flow" to "user-variant"))

        sdk.identify("user-123")
        waitUntil { network.fetchCalls.size == 2 }

        // While the refetch is in flight the old variants keep being served
        assertEquals("anon-variant", sdk.getVariant("checkout-flow"))

        // The refetch is for the new user and links the stored anonymous ID
        assertEquals("user-123" to sdk.anonymousId, network.fetchCalls[1])

        gate.complete(Unit)
        waitUntil { sdk.getVariant("checkout-flow") == "user-variant" }

        sdk.shutdown()
    }

    @Test
    fun `identify never clears variants when the refetch fails`() {
        val network = ControllableExperimentsNetworkClient(
            ExperimentsResult.Success(mapOf("checkout-flow" to "anon-variant"))
        )
        val sdk = createSdk(network)
        assertTrue(awaitReady(sdk))
        assertEquals("anon-variant", sdk.getVariant("checkout-flow"))

        network.result = ExperimentsResult.Failure(MGMError.NetworkError(Exception("offline")))
        sdk.identify("user-123")
        waitUntil { network.fetchCalls.size == 2 }
        Thread.sleep(100)

        assertEquals(
            "Variants must never be cleared mid-session",
            "anon-variant",
            sdk.getVariant("checkout-flow")
        )

        sdk.shutdown()
    }

    @Test
    fun `background refetch includes anonymous_id while identified`() {
        val prefs = FakeSharedPreferences()
        val anonymousId = "\$anon_identifiedbg"
        prefs.edit()
            .putString("anonymous_id", anonymousId)
            .putString("user_id", "user-123")
            .apply()

        val network = ControllableExperimentsNetworkClient(
            ExperimentsResult.Success(mapOf("checkout-flow" to "treatment"))
        )
        val sdk = createSdk(network, prefs)
        assertTrue(awaitReady(sdk))

        // Every fetch while identified links the stored anonymous ID,
        // not only the identify()-triggered refetch.
        assertEquals("user-123" to anonymousId, network.fetchCalls.first())

        sdk.shutdown()
    }

    // endregion
}

/**
 * Network client test double with controllable experiment fetch behavior.
 */
class ControllableExperimentsNetworkClient(
    initialResult: ExperimentsResult,
    gate: CompletableDeferred<Unit>? = null,
    private val hangForever: Boolean = false
) : NetworkClientInterface {
    @Volatile
    var result: ExperimentsResult = initialResult

    @Volatile
    var gate: CompletableDeferred<Unit>? = gate

    private val calls = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String?>>())

    /** Recorded (userId, anonymousId) pairs for each fetchExperiments call. */
    val fetchCalls: List<Pair<String, String?>>
        get() = calls.toList()

    override suspend fun sendEvents(payload: MGMEventsPayload): SendResult = SendResult.Success

    override suspend fun fetchExperiments(userId: String, anonymousId: String?): ExperimentsResult {
        calls.add(userId to anonymousId)
        if (hangForever) awaitCancellation()
        gate?.await()
        return result
    }
}

/**
 * In-memory SharedPreferences for unit tests (simulates platform storage,
 * including persistence across "restarts" when the same instance is reused).
 */
class FakeSharedPreferences : SharedPreferences {
    private val data = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = synchronized(data) { data.toMutableMap() }

    override fun getString(key: String?, defValue: String?): String? =
        synchronized(data) { data[key] as? String ?: defValue }

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        synchronized(data) {
            @Suppress("UNCHECKED_CAST")
            return (data[key] as? Set<String>)?.toMutableSet() ?: defValues
        }
    }

    override fun getInt(key: String?, defValue: Int): Int =
        synchronized(data) { data[key] as? Int ?: defValue }

    override fun getLong(key: String?, defValue: Long): Long =
        synchronized(data) { data[key] as? Long ?: defValue }

    override fun getFloat(key: String?, defValue: Float): Float =
        synchronized(data) { data[key] as? Float ?: defValue }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        synchronized(data) { data[key] as? Boolean ?: defValue }

    override fun contains(key: String?): Boolean = synchronized(data) { data.containsKey(key) }

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    private inner class FakeEditor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String, value: String?) = apply { pending[key] = value }

        override fun putStringSet(key: String, values: MutableSet<String>?) =
            apply { pending[key] = values?.toSet() }

        override fun putInt(key: String, value: Int) = apply { pending[key] = value }

        override fun putLong(key: String, value: Long) = apply { pending[key] = value }

        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }

        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }

        override fun remove(key: String) = apply { removals.add(key) }

        override fun clear() = apply { clearAll = true }

        override fun commit(): Boolean {
            applyChanges()
            return true
        }

        override fun apply() = applyChanges()

        private fun applyChanges() {
            synchronized(data) {
                if (clearAll) data.clear()
                removals.forEach { data.remove(it) }
                pending.forEach { (key, value) ->
                    if (value == null) data.remove(key) else data[key] = value
                }
            }
        }
    }
}

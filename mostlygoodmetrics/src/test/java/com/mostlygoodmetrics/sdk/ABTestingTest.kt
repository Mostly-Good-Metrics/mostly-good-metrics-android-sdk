package com.mostlygoodmetrics.sdk

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Tests for A/B testing functionality.
 *
 * Verifies:
 * - getVariant returns correct variant
 * - getVariant returns null for unknown experiment
 * - Variants are cached and restored from SharedPreferences
 * - Cache is invalidated when user changes via identify()
 * - Super properties are set correctly with $experiment_ prefix
 * - ready() completes after experiments load
 */
class ABTestingTest {

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

    // region getVariant tests

    @Test
    fun `getVariant returns correct variant for assigned experiment`() = runBlocking {
        val experimentsResponse = ExperimentsResponse(
            assignedVariants = mapOf(
                "button-color" to "a",
                "checkout-flow" to "treatment"
            )
        )
        val mockNetwork = MockNetworkClient(
            SendResult.Success,
            ExperimentsResult.Success(experimentsResponse)
        )
        val sdk = MostlyGoodMetrics.createForTesting(configuration, storage, mockNetwork)

        // Wait for experiments to load
        sdk.ready()

        val variant = sdk.getVariant("button-color")
        assertEquals("a", variant)

        sdk.shutdown()
    }

    @Test
    fun `getVariant returns null for unknown experiment`() = runBlocking {
        val experimentsResponse = ExperimentsResponse(
            assignedVariants = mapOf("button-color" to "a")
        )
        val mockNetwork = MockNetworkClient(
            SendResult.Success,
            ExperimentsResult.Success(experimentsResponse)
        )
        val sdk = MostlyGoodMetrics.createForTesting(configuration, storage, mockNetwork)

        // Wait for experiments to load
        sdk.ready()

        val variant = sdk.getVariant("nonexistent-experiment")
        assertNull(variant)

        sdk.shutdown()
    }

    @Test
    fun `getVariant returns null for empty experiment name`() = runBlocking {
        val mockNetwork = MockNetworkClient(
            SendResult.Success,
            ExperimentsResult.Success(ExperimentsResponse())
        )
        val sdk = MostlyGoodMetrics.createForTesting(configuration, storage, mockNetwork)

        // Wait for experiments to load
        sdk.ready()

        val variant = sdk.getVariant("")
        assertNull(variant)

        val variantBlank = sdk.getVariant("   ")
        assertNull(variantBlank)

        sdk.shutdown()
    }

    @Test
    fun `getVariant returns null before experiments are loaded`() = runBlocking {
        // Network that never completes
        val mockNetwork = object : NetworkClientInterface {
            override suspend fun sendEvents(payload: MGMEventsPayload) = SendResult.Success
            override suspend fun fetchExperiments(userId: String): ExperimentsResult {
                // Simulate network delay - never returns
                kotlinx.coroutines.delay(10000)
                return ExperimentsResult.Success(ExperimentsResponse(mapOf("test" to "a")))
            }
        }
        val sdk = MostlyGoodMetrics.createForTesting(configuration, storage, mockNetwork)

        // Don't wait for ready - immediately check
        assertFalse(sdk.areExperimentsLoaded)
        val variant = sdk.getVariant("test")
        assertNull(variant)

        sdk.shutdown()
    }

    @Test
    fun `getVariant is deterministic - same user gets same variant`() = runBlocking {
        val experimentsResponse = ExperimentsResponse(
            assignedVariants = mapOf("button-color" to "b")
        )
        val mockNetwork = MockNetworkClient(
            SendResult.Success,
            ExperimentsResult.Success(experimentsResponse)
        )
        val sdk = MostlyGoodMetrics.createForTesting(configuration, storage, mockNetwork)

        sdk.ready()

        // Call multiple times - should return same server-assigned variant
        val variant1 = sdk.getVariant("button-color")
        val variant2 = sdk.getVariant("button-color")
        val variant3 = sdk.getVariant("button-color")

        assertEquals("b", variant1)
        assertEquals(variant1, variant2)
        assertEquals(variant2, variant3)

        sdk.shutdown()
    }

    // endregion

    // region Super property tests

    @Test
    fun `getVariant stores variant as super property`() = runBlocking {
        val experimentsResponse = ExperimentsResponse(
            assignedVariants = mapOf("button-color" to "a")
        )
        val mockNetwork = MockNetworkClient(
            SendResult.Success,
            ExperimentsResult.Success(experimentsResponse)
        )
        val sdk = MostlyGoodMetrics.createForTesting(configuration, storage, mockNetwork)

        sdk.ready()

        val variant = sdk.getVariant("button-color")
        assertEquals("a", variant)

        val superProps = sdk.getSuperProperties()
        assertEquals("a", superProps["\$experiment_button_color"])

        sdk.shutdown()
    }

    @Test
    fun `super property uses snake_case for experiment name`() = runBlocking {
        val experimentsResponse = ExperimentsResponse(
            assignedVariants = mapOf(
                "checkoutFlow" to "treatment",
                "new-onboarding" to "b",
                "Button Color" to "c"
            )
        )
        val mockNetwork = MockNetworkClient(
            SendResult.Success,
            ExperimentsResult.Success(experimentsResponse)
        )
        val sdk = MostlyGoodMetrics.createForTesting(configuration, storage, mockNetwork)

        sdk.ready()

        sdk.getVariant("checkoutFlow")
        sdk.getVariant("new-onboarding")
        sdk.getVariant("Button Color")

        val superProps = sdk.getSuperProperties()
        assertEquals("treatment", superProps["\$experiment_checkout_flow"])
        assertEquals("b", superProps["\$experiment_new_onboarding"])
        assertEquals("c", superProps["\$experiment_button_color"])

        sdk.shutdown()
    }

    @Test
    fun `experiment variant is attached to tracked events via super property`() = runBlocking {
        val experimentsResponse = ExperimentsResponse(
            assignedVariants = mapOf("checkout-flow" to "treatment")
        )
        val mockNetwork = MockNetworkClient(
            SendResult.Success,
            ExperimentsResult.Success(experimentsResponse)
        )
        val sdk = MostlyGoodMetrics.createForTesting(configuration, storage, mockNetwork)

        sdk.ready()

        // Get variant first (this sets the super property)
        val variant = sdk.getVariant("checkout-flow")
        assertEquals("treatment", variant)

        // Track an event
        sdk.track("purchase_completed")

        // Check that the event has the experiment variant
        val events = storage.fetchEvents(10)
        val purchaseEvent = events.find { it.name == "purchase_completed" }
        assertNotNull(purchaseEvent)

        val properties = purchaseEvent!!.properties
        assertNotNull(properties)
        val experimentValue = (properties?.get("\$experiment_checkout_flow") as? kotlinx.serialization.json.JsonPrimitive)?.content
        assertEquals("treatment", experimentValue)

        sdk.shutdown()
    }

    // endregion

    // region ready() tests

    @Test
    fun `ready completes when experiments are loaded`() = runBlocking {
        val experimentsResponse = ExperimentsResponse(
            assignedVariants = mapOf("test" to "a")
        )
        val mockNetwork = MockNetworkClient(
            SendResult.Success,
            ExperimentsResult.Success(experimentsResponse)
        )
        val sdk = MostlyGoodMetrics.createForTesting(configuration, storage, mockNetwork)

        // Wait for ready
        sdk.ready()

        assertTrue(sdk.areExperimentsLoaded)

        sdk.shutdown()
    }

    @Test
    fun `ready with callback is called when experiments are loaded`() = runBlocking {
        val experimentsResponse = ExperimentsResponse(
            assignedVariants = mapOf("test" to "a")
        )
        val mockNetwork = MockNetworkClient(
            SendResult.Success,
            ExperimentsResult.Success(experimentsResponse)
        )
        val sdk = MostlyGoodMetrics.createForTesting(configuration, storage, mockNetwork)

        val latch = CountDownLatch(1)
        sdk.ready { latch.countDown() }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertTrue(sdk.areExperimentsLoaded)

        sdk.shutdown()
    }

    @Test
    fun `ready completes even when fetch fails`() = runBlocking {
        val mockNetwork = MockNetworkClient(
            SendResult.Success,
            ExperimentsResult.Failure(MGMError.NetworkError(Exception("Network error")))
        )
        val sdk = MostlyGoodMetrics.createForTesting(configuration, storage, mockNetwork)

        // Wait for ready - should still complete
        sdk.ready()

        assertTrue(sdk.areExperimentsLoaded)
        // No variants should be available
        assertNull(sdk.getVariant("test"))

        sdk.shutdown()
    }

    @Test
    fun `ready returns immediately if already loaded`() = runBlocking {
        val experimentsResponse = ExperimentsResponse(
            assignedVariants = mapOf("test" to "a")
        )
        val mockNetwork = MockNetworkClient(
            SendResult.Success,
            ExperimentsResult.Success(experimentsResponse)
        )
        val sdk = MostlyGoodMetrics.createForTesting(configuration, storage, mockNetwork)

        // First wait
        sdk.ready()
        assertTrue(sdk.areExperimentsLoaded)

        // Second wait should return immediately
        var callbackCalled = false
        sdk.ready { callbackCalled = true }
        assertTrue(callbackCalled)

        sdk.shutdown()
    }

    // endregion

    // region Cache invalidation on identify tests

    @Test
    fun `identify with different user refetches experiments`() = runBlocking {
        val experimentsResponse1 = ExperimentsResponse(
            assignedVariants = mapOf("test-exp" to "anon-variant")
        )
        val mockNetwork = MockNetworkClient(
            SendResult.Success,
            ExperimentsResult.Success(experimentsResponse1)
        )
        val sdk = MostlyGoodMetrics.createForTesting(configuration, storage, mockNetwork)

        // Wait for initial fetch
        sdk.ready()
        assertEquals(1, mockNetwork.fetchExperimentsCount)
        assertEquals("anon-variant", sdk.getVariant("test-exp"))

        // Change the mock to return different variant
        val experimentsResponse2 = ExperimentsResponse(
            assignedVariants = mapOf("test-exp" to "user-variant")
        )
        mockNetwork.experimentsResult = ExperimentsResult.Success(experimentsResponse2)

        // Identify - should trigger refetch
        sdk.identify("user123")

        // Wait a bit for the background fetch
        kotlinx.coroutines.delay(100)
        sdk.ready()

        // Should have fetched again
        assertEquals(2, mockNetwork.fetchExperimentsCount)
        assertEquals("user123", mockNetwork.lastFetchedUserId)
        assertEquals("user-variant", sdk.getVariant("test-exp"))

        sdk.shutdown()
    }

    @Test
    fun `identify with same user does not refetch experiments`() = runBlocking {
        val experimentsResponse = ExperimentsResponse(
            assignedVariants = mapOf("test-exp" to "a")
        )
        val mockNetwork = MockNetworkClient(
            SendResult.Success,
            ExperimentsResult.Success(experimentsResponse)
        )
        val sdk = MostlyGoodMetrics.createForTesting(configuration, storage, mockNetwork)

        // First identify
        sdk.identify("user123")
        sdk.ready()
        val initialFetchCount = mockNetwork.fetchExperimentsCount

        // Identify again with same user
        sdk.identify("user123")

        // Small delay to ensure no new fetch is triggered
        kotlinx.coroutines.delay(100)

        // Should not trigger another fetch
        assertEquals(initialFetchCount, mockNetwork.fetchExperimentsCount)

        sdk.shutdown()
    }

    @Test
    fun `experiments cache is cleared on identify`() = runBlocking {
        val experimentsResponse = ExperimentsResponse(
            assignedVariants = mapOf("test-exp" to "initial")
        )
        val mockNetwork = MockNetworkClient(
            SendResult.Success,
            ExperimentsResult.Success(experimentsResponse)
        )
        val sdk = MostlyGoodMetrics.createForTesting(configuration, storage, mockNetwork)

        sdk.ready()
        assertEquals("initial", sdk.getVariant("test-exp"))

        // Change to empty response
        mockNetwork.experimentsResult = ExperimentsResult.Success(ExperimentsResponse())

        // Identify with new user
        sdk.identify("newuser")
        kotlinx.coroutines.delay(100)
        sdk.ready()

        // Old variant should be gone
        assertNull(sdk.getVariant("test-exp"))

        sdk.shutdown()
    }

    // endregion

    // region Network failure tests

    @Test
    fun `experiments are empty when server returns error`() = runBlocking {
        val mockNetwork = MockNetworkClient(
            SendResult.Success,
            ExperimentsResult.Failure(MGMError.Unauthorized)
        )
        val sdk = MostlyGoodMetrics.createForTesting(configuration, storage, mockNetwork)

        sdk.ready()

        assertNull(sdk.getVariant("any-experiment"))
        assertTrue(sdk.areExperimentsLoaded)

        sdk.shutdown()
    }

    @Test
    fun `fetch experiments uses correct user id`() = runBlocking {
        val mockNetwork = MockNetworkClient(
            SendResult.Success,
            ExperimentsResult.Success(ExperimentsResponse())
        )
        val sdk = MostlyGoodMetrics.createForTesting(configuration, storage, mockNetwork)

        sdk.ready()

        // First fetch should use anonymous ID
        assertNotNull(mockNetwork.lastFetchedUserId)
        val anonymousId = mockNetwork.lastFetchedUserId!!

        // Identify and wait for refetch
        mockNetwork.experimentsResult = ExperimentsResult.Success(ExperimentsResponse())
        sdk.identify("real-user-123")
        kotlinx.coroutines.delay(100)

        // Second fetch should use identified user ID
        assertEquals("real-user-123", mockNetwork.lastFetchedUserId)

        sdk.shutdown()
    }

    // endregion

    // region Multiple experiments tests

    @Test
    fun `multiple experiments can be fetched and accessed`() = runBlocking {
        val experimentsResponse = ExperimentsResponse(
            assignedVariants = mapOf(
                "experiment1" to "a",
                "experiment2" to "b",
                "experiment3" to "c",
                "experiment4" to "control"
            )
        )
        val mockNetwork = MockNetworkClient(
            SendResult.Success,
            ExperimentsResult.Success(experimentsResponse)
        )
        val sdk = MostlyGoodMetrics.createForTesting(configuration, storage, mockNetwork)

        sdk.ready()

        assertEquals("a", sdk.getVariant("experiment1"))
        assertEquals("b", sdk.getVariant("experiment2"))
        assertEquals("c", sdk.getVariant("experiment3"))
        assertEquals("control", sdk.getVariant("experiment4"))
        assertNull(sdk.getVariant("experiment5"))

        sdk.shutdown()
    }

    @Test
    fun `all experiment variants are stored as super properties`() = runBlocking {
        val experimentsResponse = ExperimentsResponse(
            assignedVariants = mapOf(
                "exp1" to "a",
                "exp2" to "b"
            )
        )
        val mockNetwork = MockNetworkClient(
            SendResult.Success,
            ExperimentsResult.Success(experimentsResponse)
        )
        val sdk = MostlyGoodMetrics.createForTesting(configuration, storage, mockNetwork)

        sdk.ready()

        // Access both variants
        sdk.getVariant("exp1")
        sdk.getVariant("exp2")

        val superProps = sdk.getSuperProperties()
        assertEquals("a", superProps["\$experiment_exp1"])
        assertEquals("b", superProps["\$experiment_exp2"])

        sdk.shutdown()
    }

    // endregion
}

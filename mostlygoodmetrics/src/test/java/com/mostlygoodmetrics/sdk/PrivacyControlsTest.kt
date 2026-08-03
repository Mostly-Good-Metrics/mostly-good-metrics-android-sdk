package com.mostlygoodmetrics.sdk

import android.content.SharedPreferences
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Tests for privacy controls.
 *
 * Contract under test:
 * - optOut() stops all tracking immediately: track/identify/flush no-op,
 *   pending (unsent) events are purged, and the choice is persisted in
 *   SharedPreferences so it survives app restarts. optIn() re-enables tracking.
 * - optedOutByDefault starts the SDK opted out until the user explicitly
 *   opts in; a persisted choice always wins over the configured default.
 * - resetAnonymousId() rotates the persisted anonymous ID.
 * - resetIdentity(clearAnonymousId = true) is a full "forget me": rotates the
 *   anonymous ID, purges pending events and super properties, and starts a
 *   new session. The default (false) keeps the existing behavior.
 * - collectDeviceProperties(false) omits $device_model, $device_type,
 *   device_manufacturer, locale, and timezone from events while keeping
 *   platform, os_version, and app_version.
 */
class PrivacyControlsTest {

    private fun testConfiguration(
        configure: MGMConfiguration.Builder.() -> Unit = {}
    ): MGMConfiguration =
        MGMConfiguration.Builder("test-api-key")
            .enableDebugLogging(false)
            .trackAppLifecycleEvents(false)
            .apply(configure)
            .build()

    private fun createSdk(
        configuration: MGMConfiguration = testConfiguration(),
        storage: EventStorage = InMemoryEventStorage(maxEvents = 100),
        network: NetworkClientInterface = MockNetworkClient(SendResult.Success),
        prefs: SharedPreferences? = null
    ): MostlyGoodMetrics {
        return MostlyGoodMetrics.createForTesting(configuration, storage, network, prefs)
    }

    private fun awaitFlush(sdk: MostlyGoodMetrics) {
        val latch = CountDownLatch(1)
        sdk.flush { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
    }

    // region Opt-out / opt-in

    @Test
    fun `optOut purges pending events and stops tracking immediately`() {
        val storage = InMemoryEventStorage(maxEvents = 100)
        val mockNetwork = MockNetworkClient(SendResult.Success)
        val sdk = createSdk(storage = storage, network = mockNetwork)

        sdk.track("before_opt_out")
        assertEquals(1, storage.eventCount())
        assertFalse(sdk.isOptedOut)

        sdk.optOut()

        assertTrue(sdk.isOptedOut)
        // Queued events are purged
        assertEquals(0, storage.eventCount())

        // track() is a no-op
        sdk.track("after_opt_out")
        assertEquals(0, storage.eventCount())

        // flush() is a no-op (still invokes its completion) and sends nothing
        awaitFlush(sdk)
        assertEquals(0, mockNetwork.sendCount)

        sdk.shutdown()
    }

    @Test
    fun `identify is a no-op while opted out`() {
        val storage = InMemoryEventStorage(maxEvents = 100)
        val sdk = createSdk(storage = storage, prefs = FakeSharedPreferences())

        sdk.optOut()
        sdk.identify("user-123", UserProfile(email = "test@example.com"))

        assertNull(sdk.userId)
        assertEquals(0, storage.eventCount())

        sdk.shutdown()
    }

    @Test
    fun `optOut is persisted and survives restart`() {
        val prefs = FakeSharedPreferences()

        val sdk1 = createSdk(prefs = prefs)
        sdk1.optOut()
        sdk1.shutdown()

        // Simulate app restart: a new instance backed by the same prefs
        val storage = InMemoryEventStorage(maxEvents = 100)
        val sdk2 = createSdk(storage = storage, prefs = prefs)

        assertTrue(sdk2.isOptedOut)
        sdk2.track("after_restart")
        assertEquals(0, storage.eventCount())

        sdk2.shutdown()
    }

    @Test
    fun `optIn re-enables tracking and is persisted`() {
        val prefs = FakeSharedPreferences()
        val storage = InMemoryEventStorage(maxEvents = 100)
        val sdk = createSdk(storage = storage, prefs = prefs)

        sdk.optOut()
        sdk.optIn()

        assertFalse(sdk.isOptedOut)
        sdk.track("after_opt_in")
        assertEquals(1, storage.eventCount())
        sdk.shutdown()

        // Opt-in survives restart
        val sdk2 = createSdk(prefs = prefs)
        assertFalse(sdk2.isOptedOut)
        sdk2.shutdown()
    }

    @Test
    fun `optedOutByDefault starts the SDK opted out`() {
        val configuration = testConfiguration { optedOutByDefault(true) }
        val storage = InMemoryEventStorage(maxEvents = 100)
        val sdk = createSdk(configuration, storage, prefs = FakeSharedPreferences())

        assertTrue(sdk.isOptedOut)
        sdk.track("no_consent_yet")
        assertEquals(0, storage.eventCount())

        sdk.shutdown()
    }

    @Test
    fun `persisted opt-in wins over optedOutByDefault`() {
        val prefs = FakeSharedPreferences()
        val configuration = testConfiguration { optedOutByDefault(true) }

        val sdk1 = createSdk(configuration, prefs = prefs)
        assertTrue(sdk1.isOptedOut)
        sdk1.optIn()
        sdk1.shutdown()

        // Restart: consent was already granted, default no longer applies
        val storage = InMemoryEventStorage(maxEvents = 100)
        val sdk2 = createSdk(configuration, storage, prefs = prefs)

        assertFalse(sdk2.isOptedOut)
        sdk2.track("consented")
        assertEquals(1, storage.eventCount())

        sdk2.shutdown()
    }

    // endregion

    // region Anonymous ID rotation

    @Test
    fun `resetAnonymousId rotates and persists the anonymous ID`() {
        val prefs = FakeSharedPreferences()
        val sdk = createSdk(prefs = prefs)

        val originalId = sdk.anonymousId
        assertTrue(originalId.startsWith("\$anon_"))

        sdk.resetAnonymousId()

        val rotatedId = sdk.anonymousId
        assertTrue(rotatedId.startsWith("\$anon_"))
        assertNotEquals(originalId, rotatedId)
        sdk.shutdown()

        // The rotated ID is the one restored after restart
        val sdk2 = createSdk(prefs = prefs)
        assertEquals(rotatedId, sdk2.anonymousId)
        sdk2.shutdown()
    }

    @Test
    fun `events after resetAnonymousId use the new anonymous ID`() {
        val storage = InMemoryEventStorage(maxEvents = 100)
        val sdk = createSdk(storage = storage, prefs = FakeSharedPreferences())

        sdk.track("before_rotation")
        sdk.resetAnonymousId()
        sdk.track("after_rotation")

        val events = storage.fetchEvents(10)
        val before = events.find { it.name == "before_rotation" }
        val after = events.find { it.name == "after_rotation" }
        assertNotNull(before)
        assertNotNull(after)
        assertNotEquals(before!!.userId, after!!.userId)
        assertEquals(sdk.anonymousId, after.userId)

        sdk.shutdown()
    }

    // endregion

    // region resetIdentity

    @Test
    fun `resetIdentity keeps anonymous ID and pending events by default`() {
        val storage = InMemoryEventStorage(maxEvents = 100)
        val sdk = createSdk(storage = storage, prefs = FakeSharedPreferences())

        sdk.identify("user-123")
        sdk.track("some_event")
        val anonymousId = sdk.anonymousId
        val sessionId = sdk.sessionId

        sdk.resetIdentity()

        assertNull(sdk.userId)
        assertEquals(anonymousId, sdk.anonymousId)
        assertEquals(sessionId, sdk.sessionId)
        assertEquals(1, storage.eventCount())

        sdk.shutdown()
    }

    @Test
    fun `resetIdentity with clearAnonymousId performs a full forget-me`() {
        val storage = InMemoryEventStorage(maxEvents = 100)
        val prefs = FakeSharedPreferences()
        val sdk = createSdk(storage = storage, prefs = prefs)

        sdk.identify("user-123")
        sdk.setSuperProperty("plan", "pro")
        sdk.track("some_event")
        val originalAnonymousId = sdk.anonymousId
        val originalSessionId = sdk.sessionId

        sdk.resetIdentity(clearAnonymousId = true)

        assertNull(sdk.userId)
        assertNotEquals(originalAnonymousId, sdk.anonymousId)
        assertNotEquals(originalSessionId, sdk.sessionId)
        assertEquals(0, storage.eventCount())
        assertTrue(sdk.getSuperProperties().isEmpty())

        sdk.shutdown()
    }

    // endregion

    // region collectDeviceProperties

    @Test
    fun `device properties are collected by default`() {
        val storage = InMemoryEventStorage(maxEvents = 100)
        val sdk = createSdk(storage = storage)

        sdk.track("test_event")

        val event = storage.fetchEvents(1).first()
        val properties = event.properties
        assertNotNull(properties)
        assertTrue(properties!!.containsKey("\$device_type"))
        assertTrue(properties.containsKey("\$device_model"))
        assertNotNull(event.deviceManufacturer)
        assertNotNull(event.locale)
        assertNotNull(event.timezone)

        sdk.shutdown()
    }

    @Test
    fun `collectDeviceProperties false omits device properties from events`() {
        val configuration = testConfiguration { collectDeviceProperties(false) }
        val storage = InMemoryEventStorage(maxEvents = 100)
        val sdk = createSdk(configuration, storage)

        sdk.track("test_event")

        val event = storage.fetchEvents(1).first()
        val properties = event.properties
        assertNotNull(properties)

        // Omitted device-level fields
        assertFalse(properties!!.containsKey("\$device_type"))
        assertFalse(properties.containsKey("\$device_model"))
        assertNull(event.deviceManufacturer)
        assertNull(event.locale)
        assertNull(event.timezone)

        // Still-included context
        assertEquals("android", event.platform)
        assertNotNull(event.osVersion)
        assertNotNull(event.appVersion)
        assertEquals("android", (properties["\$sdk"] as? JsonPrimitive)?.content)

        sdk.shutdown()
    }

    // endregion
}

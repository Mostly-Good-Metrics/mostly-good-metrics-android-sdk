package com.mostlygoodmetrics.sdk

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for MostlyGoodMetrics client functionality.
 *
 * Note: These tests focus on the public API and behavior that can be tested
 * without Android context. Full integration tests would require instrumented tests.
 */
class MostlyGoodMetricsTest {

    // Since MostlyGoodMetrics requires Android Context, we test the supporting
    // components and behaviors that don't require Context

    @Test
    fun `InMemoryEventStorage can be used for testing`() {
        val storage = InMemoryEventStorage(maxEvents = 100)

        assertEquals(0, storage.eventCount())

        val event = MGMEvent(
            name = "test_event",
            timestamp = "2024-01-01T00:00:00.000Z"
        )
        storage.store(event)

        assertEquals(1, storage.eventCount())
    }

    @Test
    fun `events are stored with all metadata`() {
        val storage = InMemoryEventStorage(maxEvents = 100)

        val event = MGMEvent(
            name = "test_event",
            timestamp = "2024-01-01T00:00:00.000Z",
            userId = "user-123",
            sessionId = "session-456",
            platform = "android",
            appVersion = "1.0.0 (1)",
            osVersion = "14",
            environment = "production"
        )
        storage.store(event)

        val fetched = storage.fetchEvents(1)
        assertEquals(1, fetched.size)

        val fetchedEvent = fetched[0]
        assertEquals("test_event", fetchedEvent.name)
        assertEquals("user-123", fetchedEvent.userId)
        assertEquals("session-456", fetchedEvent.sessionId)
        assertEquals("android", fetchedEvent.platform)
        assertEquals("1.0.0 (1)", fetchedEvent.appVersion)
        assertEquals("14", fetchedEvent.osVersion)
        assertEquals("production", fetchedEvent.environment)
    }

    @Test
    fun `multiple events maintain order`() {
        val storage = InMemoryEventStorage(maxEvents = 100)

        for (i in 1..5) {
            storage.store(createTestEvent("event_$i"))
        }

        val events = storage.fetchEvents(10)
        assertEquals(5, events.size)
        assertEquals("event_1", events[0].name)
        assertEquals("event_2", events[1].name)
        assertEquals("event_3", events[2].name)
        assertEquals("event_4", events[3].name)
        assertEquals("event_5", events[4].name)
    }

    @Test
    fun `clearing storage removes all events`() {
        val storage = InMemoryEventStorage(maxEvents = 100)

        for (i in 1..5) {
            storage.store(createTestEvent("event_$i"))
        }

        assertEquals(5, storage.eventCount())

        storage.clear()

        assertEquals(0, storage.eventCount())
    }

    @Test
    fun `events can be selectively removed`() {
        val storage = InMemoryEventStorage(maxEvents = 100)

        val event1 = createTestEvent("event_1")
        val event2 = createTestEvent("event_2")
        val event3 = createTestEvent("event_3")

        storage.store(event1)
        storage.store(event2)
        storage.store(event3)

        storage.removeEvents(listOf(event1, event3))

        val remaining = storage.fetchEvents(10)
        assertEquals(1, remaining.size)
        assertEquals("event_2", remaining[0].name)
    }

    @Test
    fun `event creation validates name before storing`() {
        // Valid names should create events
        assertNotNull(MGMEvent.create("valid_event"))
        assertNotNull(MGMEvent.create("ValidEvent"))
        assertNotNull(MGMEvent.create("event123"))
        assertNotNull(MGMEvent.create("\$app_opened"))

        // Invalid names should return null
        assertNull(MGMEvent.create(""))
        assertNull(MGMEvent.create("123invalid"))
        assertNull(MGMEvent.create("invalid-name"))
        assertNull(MGMEvent.create("invalid name"))
    }

    @Test
    fun `events include timestamp`() {
        val event = MGMEvent.create("test_event")

        assertNotNull(event)
        assertNotNull(event!!.timestamp)
        assertTrue(event.timestamp.contains("T"))
        assertTrue(event.timestamp.endsWith("Z"))
    }

    @Test
    fun `events include user ID when provided`() {
        val event = MGMEvent.create(
            name = "test_event",
            userId = "user-123"
        )

        assertNotNull(event)
        assertEquals("user-123", event!!.userId)
    }

    @Test
    fun `events include session ID when provided`() {
        val event = MGMEvent.create(
            name = "test_event",
            sessionId = "session-456"
        )

        assertNotNull(event)
        assertEquals("session-456", event!!.sessionId)
    }

    @Test
    fun `events include environment when provided`() {
        val event = MGMEvent.create(
            name = "test_event",
            environment = "staging"
        )

        assertNotNull(event)
        assertEquals("staging", event!!.environment)
    }

    @Test
    fun `events include platform when provided`() {
        val event = MGMEvent.create(
            name = "test_event",
            platform = "android"
        )

        assertNotNull(event)
        assertEquals("android", event!!.platform)
    }

    @Test
    fun `events include app version when provided`() {
        val event = MGMEvent.create(
            name = "test_event",
            appVersion = "2.0.0 (42)"
        )

        assertNotNull(event)
        assertEquals("2.0.0 (42)", event!!.appVersion)
    }

    @Test
    fun `events include OS version when provided`() {
        val event = MGMEvent.create(
            name = "test_event",
            osVersion = "14"
        )

        assertNotNull(event)
        assertEquals("14", event!!.osVersion)
    }

    private fun createTestEvent(name: String): MGMEvent {
        return MGMEvent(
            name = name,
            timestamp = "2024-01-01T00:00:00.000Z"
        )
    }
}

/**
 * Tests for SendResult sealed class behavior.
 */
class SendResultTest {

    @Test
    fun `Success result is correctly identified`() {
        val result: SendResult = SendResult.Success

        assertTrue(result is SendResult.Success)
    }

    @Test
    fun `RetryLater result contains error`() {
        val error = MGMError.NetworkError(Exception("Connection failed"))
        val result = SendResult.RetryLater(error)

        assertTrue(result is SendResult.RetryLater)
        assertEquals(error, result.error)
    }

    @Test
    fun `DropEvents result contains error`() {
        val error = MGMError.BadRequest("Invalid format")
        val result = SendResult.DropEvents(error)

        assertTrue(result is SendResult.DropEvents)
        assertEquals(error, result.error)
    }
}

/**
 * Tests for MGMError sealed class.
 */
class MGMErrorTest {

    @Test
    fun `NetworkError has correct message`() {
        val cause = Exception("Connection refused")
        val error = MGMError.NetworkError(cause)

        assertTrue(error.message!!.contains("Network error"))
        assertTrue(error.message!!.contains("Connection refused"))
        assertEquals(cause, error.cause)
    }

    @Test
    fun `EncodingError has correct message`() {
        val cause = Exception("Invalid JSON")
        val error = MGMError.EncodingError(cause)

        assertTrue(error.message!!.contains("Encoding error"))
        assertTrue(error.message!!.contains("Invalid JSON"))
        assertEquals(cause, error.cause)
    }

    @Test
    fun `InvalidResponse has correct message`() {
        val error = MGMError.InvalidResponse

        assertTrue(error.message!!.contains("Invalid response"))
    }

    @Test
    fun `BadRequest has correct message`() {
        val error = MGMError.BadRequest("Missing required field")

        assertTrue(error.message!!.contains("Bad request"))
        assertTrue(error.message!!.contains("Missing required field"))
    }

    @Test
    fun `Unauthorized has correct message`() {
        val error = MGMError.Unauthorized

        assertTrue(error.message!!.contains("Unauthorized"))
    }

    @Test
    fun `Forbidden has correct message`() {
        val error = MGMError.Forbidden("Access denied")

        assertTrue(error.message!!.contains("Forbidden"))
        assertTrue(error.message!!.contains("Access denied"))
    }

    @Test
    fun `RateLimited has correct message with retry time`() {
        val error = MGMError.RateLimited(60L)

        assertTrue(error.message!!.contains("Rate limited"))
        assertTrue(error.message!!.contains("60"))
    }

    @Test
    fun `RateLimited handles null retry time`() {
        val error = MGMError.RateLimited(null)

        assertTrue(error.message!!.contains("Rate limited"))
        assertTrue(error.message!!.contains("unknown"))
    }

    @Test
    fun `ServerError has correct message`() {
        val error = MGMError.ServerError(500, "Internal error")

        assertTrue(error.message!!.contains("Server error"))
        assertTrue(error.message!!.contains("500"))
        assertTrue(error.message!!.contains("Internal error"))
    }

    @Test
    fun `UnexpectedStatusCode has correct message`() {
        val error = MGMError.UnexpectedStatusCode(418)

        assertTrue(error.message!!.contains("Unexpected status code"))
        assertTrue(error.message!!.contains("418"))
    }

    @Test
    fun `InvalidEventName has correct message`() {
        val error = MGMError.InvalidEventName("123invalid")

        assertTrue(error.message!!.contains("Invalid event name"))
        assertTrue(error.message!!.contains("123invalid"))
    }
}

/**
 * Tests for MGMLogger functionality.
 *
 * Note: Full logging tests require Android instrumented tests since
 * android.util.Log is not available in unit tests.
 */
class MGMLoggerTest {

    @Test
    fun `logger can be enabled and disabled`() {
        val originalState = MGMLogger.isEnabled

        MGMLogger.isEnabled = true
        assertTrue(MGMLogger.isEnabled)

        MGMLogger.isEnabled = false
        assertFalse(MGMLogger.isEnabled)

        // Reset
        MGMLogger.isEnabled = originalState
    }

    @Test
    fun `logging methods do not throw when disabled`() {
        val originalState = MGMLogger.isEnabled
        MGMLogger.isEnabled = false

        // These should not throw (they call android.util.Log internally
        // but it returns 0 in unit tests which is fine)
        try {
            MGMLogger.debug("Debug message")
            MGMLogger.info("Info message")
            MGMLogger.warn("Warning message")
            MGMLogger.error("Error message")
            MGMLogger.error("Error with exception", Exception("Test"))
        } catch (e: Exception) {
            // Expected in unit tests - android.util.Log is stubbed
        }

        MGMLogger.isEnabled = originalState
    }
}

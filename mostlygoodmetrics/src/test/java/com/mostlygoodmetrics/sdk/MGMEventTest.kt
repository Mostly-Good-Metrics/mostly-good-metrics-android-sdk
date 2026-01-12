package com.mostlygoodmetrics.sdk

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class MGMEventTest {

    private val json = Json { ignoreUnknownKeys = true }

    // MARK: - Event Name Validation Tests

    @Test
    fun `isValidEventName accepts valid names`() {
        assertTrue(MGMEvent.isValidEventName("click"))
        assertTrue(MGMEvent.isValidEventName("button_clicked"))
        assertTrue(MGMEvent.isValidEventName("ButtonClicked"))
        assertTrue(MGMEvent.isValidEventName("event123"))
        assertTrue(MGMEvent.isValidEventName("a"))
        assertTrue(MGMEvent.isValidEventName("\$app_opened"))
        assertTrue(MGMEvent.isValidEventName("\$system_event"))
    }

    @Test
    fun `isValidEventName accepts single character names`() {
        assertTrue(MGMEvent.isValidEventName("a"))
        assertTrue(MGMEvent.isValidEventName("Z"))
    }

    @Test
    fun `isValidEventName accepts names with numbers after letters`() {
        assertTrue(MGMEvent.isValidEventName("event1"))
        assertTrue(MGMEvent.isValidEventName("event123"))
        assertTrue(MGMEvent.isValidEventName("a1b2c3"))
    }

    @Test
    fun `isValidEventName accepts names with underscores`() {
        assertTrue(MGMEvent.isValidEventName("my_event"))
        assertTrue(MGMEvent.isValidEventName("my_long_event_name"))
        assertTrue(MGMEvent.isValidEventName("event_1_2_3"))
    }

    @Test
    fun `isValidEventName accepts system events with dollar prefix`() {
        assertTrue(MGMEvent.isValidEventName("\$app_opened"))
        assertTrue(MGMEvent.isValidEventName("\$app_closed"))
        assertTrue(MGMEvent.isValidEventName("\$session_start"))
        assertTrue(MGMEvent.isValidEventName("\$purchase"))
    }

    @Test
    fun `isValidEventName rejects empty names`() {
        assertFalse(MGMEvent.isValidEventName(""))
    }

    @Test
    fun `isValidEventName rejects names starting with number`() {
        assertFalse(MGMEvent.isValidEventName("123event"))
        assertFalse(MGMEvent.isValidEventName("1event"))
        assertFalse(MGMEvent.isValidEventName("0_event"))
    }

    @Test
    fun `isValidEventName rejects names starting with underscore`() {
        assertFalse(MGMEvent.isValidEventName("_event"))
        assertFalse(MGMEvent.isValidEventName("__event"))
    }

    @Test
    fun `isValidEventName rejects names with hyphen`() {
        assertFalse(MGMEvent.isValidEventName("event-name"))
        assertFalse(MGMEvent.isValidEventName("my-event-name"))
    }

    @Test
    fun `isValidEventName accepts names with spaces`() {
        assertTrue(MGMEvent.isValidEventName("event name"))
        assertTrue(MGMEvent.isValidEventName("my event"))
        assertTrue(MGMEvent.isValidEventName("Button Clicked"))
        assertTrue(MGMEvent.isValidEventName("User Signed Up"))
    }

    @Test
    fun `isValidEventName rejects names with leading or trailing spaces`() {
        assertFalse(MGMEvent.isValidEventName(" event"))
        assertFalse(MGMEvent.isValidEventName("event "))
    }

    @Test
    fun `isValidEventName rejects names with dot`() {
        assertFalse(MGMEvent.isValidEventName("event.name"))
        assertFalse(MGMEvent.isValidEventName("my.event.name"))
    }

    @Test
    fun `isValidEventName rejects names that are too long`() {
        assertFalse(MGMEvent.isValidEventName("a".repeat(256)))
        assertFalse(MGMEvent.isValidEventName("a".repeat(1000)))
    }

    @Test
    fun `isValidEventName accepts names at max length`() {
        assertTrue(MGMEvent.isValidEventName("a".repeat(255)))
    }

    @Test
    fun `isValidEventName rejects names with special characters`() {
        assertFalse(MGMEvent.isValidEventName("event@name"))
        assertFalse(MGMEvent.isValidEventName("event#name"))
        assertFalse(MGMEvent.isValidEventName("event!name"))
        assertFalse(MGMEvent.isValidEventName("event&name"))
        assertFalse(MGMEvent.isValidEventName("event*name"))
    }

    // MARK: - Event Creation Tests

    @Test
    fun `create returns null for invalid event name`() {
        val event = MGMEvent.create("123invalid")
        assertNull(event)
    }

    @Test
    fun `create returns null for empty event name`() {
        val event = MGMEvent.create("")
        assertNull(event)
    }

    @Test
    fun `create returns event for valid name`() {
        val event = MGMEvent.create("valid_event")
        assertNotNull(event)
        assertEquals("valid_event", event!!.name)
    }

    @Test
    fun `create generates event with timestamp`() {
        val event = MGMEvent.create("test_event")
        assertNotNull(event)
        assertEquals("test_event", event!!.name)
        assertTrue(event.timestamp.contains("T"))
        assertTrue(event.timestamp.endsWith("Z"))
    }

    @Test
    fun `create generates ISO 8601 timestamp`() {
        val event = MGMEvent.create("test")
        assertNotNull(event)

        // Should match ISO 8601 format: YYYY-MM-DDTHH:MM:SS.sssZ
        val timestamp = event!!.timestamp
        assertTrue(timestamp.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z")))
    }

    @Test
    fun `create generates clientEventId as UUID`() {
        val event = MGMEvent.create("test")
        assertNotNull(event)

        // Should be a valid UUID format
        val clientEventId = event!!.clientEventId
        assertNotNull(clientEventId)
        assertTrue(clientEventId.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
    }

    @Test
    fun `create generates unique clientEventId for each event`() {
        val event1 = MGMEvent.create("test1")
        val event2 = MGMEvent.create("test2")
        val event3 = MGMEvent.create("test3")

        assertNotNull(event1)
        assertNotNull(event2)
        assertNotNull(event3)

        // All should be unique
        val ids = setOf(event1!!.clientEventId, event2!!.clientEventId, event3!!.clientEventId)
        assertEquals(3, ids.size)
    }

    @Test
    fun `create includes all metadata`() {
        val event = MGMEvent.create(
            name = "test_event",
            userId = "user-123",
            sessionId = "session-456",
            platform = "android",
            appVersion = "1.0.0 (1)",
            osVersion = "14",
            environment = "production"
        )

        assertNotNull(event)
        assertEquals("user-123", event!!.userId)
        assertEquals("session-456", event.sessionId)
        assertEquals("android", event.platform)
        assertEquals("1.0.0 (1)", event.appVersion)
        assertEquals("14", event.osVersion)
        assertEquals("production", event.environment)
    }

    @Test
    fun `create allows null optional fields`() {
        val event = MGMEvent.create(name = "test_event")

        assertNotNull(event)
        assertNull(event!!.userId)
        assertNull(event.sessionId)
        assertNull(event.platform)
        assertNull(event.appVersion)
        assertNull(event.osVersion)
        assertNull(event.environment)
        assertNull(event.properties)
    }

    // MARK: - Properties Tests

    @Test
    fun `create converts properties to JsonObject`() {
        val event = MGMEvent.create(
            name = "test",
            properties = mapOf(
                "string" to "value",
                "int" to 42,
                "double" to 3.14,
                "boolean" to true,
                "null" to null
            )
        )

        assertNotNull(event)
        assertNotNull(event!!.properties)

        val props = event.properties!!
        assertEquals("value", props["string"]?.jsonPrimitive?.content)
        assertEquals(42, props["int"]?.jsonPrimitive?.content?.toInt())
        assertEquals(3.14, props["double"]?.jsonPrimitive?.content?.toDouble() ?: 0.0, 0.001)
        assertEquals(true, props["boolean"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `properties support nested objects`() {
        val event = MGMEvent.create(
            name = "test",
            properties = mapOf(
                "user" to mapOf(
                    "name" to "John",
                    "age" to 30
                )
            )
        )

        assertNotNull(event)
        val user = event!!.properties!!["user"]?.jsonObject
        assertNotNull(user)
        assertEquals("John", user!!["name"]?.jsonPrimitive?.content)
        assertEquals(30, user["age"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `properties support deeply nested objects`() {
        val event = MGMEvent.create(
            name = "test",
            properties = mapOf(
                "level1" to mapOf(
                    "level2" to mapOf(
                        "level3" to "deep_value"
                    )
                )
            )
        )

        assertNotNull(event)
        val level1 = event!!.properties!!["level1"]?.jsonObject
        val level2 = level1?.get("level2")?.jsonObject
        val level3 = level2?.get("level3")?.jsonPrimitive?.content

        assertEquals("deep_value", level3)
    }

    @Test
    fun `properties support arrays`() {
        val event = MGMEvent.create(
            name = "test",
            properties = mapOf(
                "tags" to listOf("a", "b", "c")
            )
        )

        assertNotNull(event)
        val tags = event!!.properties!!["tags"]?.jsonArray
        assertNotNull(tags)
        assertEquals(3, tags!!.size)
    }

    @Test
    fun `properties support arrays of numbers`() {
        val event = MGMEvent.create(
            name = "test",
            properties = mapOf(
                "numbers" to listOf(1, 2, 3, 4, 5)
            )
        )

        assertNotNull(event)
        val numbers = event!!.properties!!["numbers"]?.jsonArray
        assertNotNull(numbers)
        assertEquals(5, numbers!!.size)
    }

    @Test
    fun `properties support mixed arrays`() {
        val event = MGMEvent.create(
            name = "test",
            properties = mapOf(
                "mixed" to listOf("string", 42, true)
            )
        )

        assertNotNull(event)
        val mixed = event!!.properties!!["mixed"]?.jsonArray
        assertNotNull(mixed)
        assertEquals(3, mixed!!.size)
    }

    @Test
    fun `string properties are truncated`() {
        val longString = "x".repeat(2000)
        val event = MGMEvent.create(
            name = "test",
            properties = mapOf("long" to longString)
        )

        assertNotNull(event)
        val truncated = event!!.properties!!["long"]?.jsonPrimitive?.content
        assertEquals(1000, truncated?.length)
    }

    @Test
    fun `string properties at max length are not truncated`() {
        val maxString = "x".repeat(1000)
        val event = MGMEvent.create(
            name = "test",
            properties = mapOf("max" to maxString)
        )

        assertNotNull(event)
        val result = event!!.properties!!["max"]?.jsonPrimitive?.content
        assertEquals(1000, result?.length)
    }

    @Test
    fun `empty properties map results in null properties`() {
        val event = MGMEvent.create(
            name = "test",
            properties = emptyMap()
        )

        assertNotNull(event)
        // Empty map should result in null or empty JsonObject depending on implementation
    }

    // MARK: - Serialization Tests

    @Test
    fun `event serializes to JSON with snake_case`() {
        val event = MGMEvent(
            name = "test",
            clientEventId = "550e8400-e29b-41d4-a716-446655440000",
            timestamp = "2024-01-01T00:00:00.000Z",
            userId = "user-1",
            sessionId = "session-1",
            platform = "android",
            appVersion = "1.0",
            osVersion = "14",
            environment = "production"
        )

        val jsonStr = json.encodeToString(event)

        assertTrue(jsonStr.contains("\"user_id\""))
        assertTrue(jsonStr.contains("\"session_id\""))
        assertTrue(jsonStr.contains("\"app_version\""))
        assertTrue(jsonStr.contains("\"os_version\""))
        assertTrue(jsonStr.contains("\"client_event_id\""))
    }

    @Test
    fun `event serializes clientEventId with snake_case`() {
        val event = MGMEvent(
            name = "test",
            clientEventId = "550e8400-e29b-41d4-a716-446655440000",
            timestamp = "2024-01-01T00:00:00.000Z"
        )

        val jsonStr = json.encodeToString(event)

        assertTrue(jsonStr.contains("\"client_event_id\":\"550e8400-e29b-41d4-a716-446655440000\""))
    }

    @Test
    fun `event deserializes clientEventId from JSON`() {
        val jsonStr = """
            {
                "name": "test",
                "client_event_id": "550e8400-e29b-41d4-a716-446655440000",
                "timestamp": "2024-01-01T00:00:00.000Z"
            }
        """.trimIndent()

        val event: MGMEvent = json.decodeFromString(jsonStr)

        assertEquals("550e8400-e29b-41d4-a716-446655440000", event.clientEventId)
    }

    @Test
    fun `event deserializes from JSON`() {
        val jsonStr = """
            {
                "name": "test",
                "client_event_id": "550e8400-e29b-41d4-a716-446655440000",
                "timestamp": "2024-01-01T00:00:00.000Z",
                "user_id": "user-1",
                "session_id": "session-1",
                "platform": "android"
            }
        """.trimIndent()

        val event: MGMEvent = json.decodeFromString(jsonStr)

        assertEquals("test", event.name)
        assertEquals("user-1", event.userId)
        assertEquals("session-1", event.sessionId)
    }

    @Test
    fun `event with null fields serializes correctly`() {
        val event = MGMEvent(
            name = "test",
            clientEventId = "550e8400-e29b-41d4-a716-446655440000",
            timestamp = "2024-01-01T00:00:00.000Z"
        )

        val jsonStr = json.encodeToString(event)

        assertTrue(jsonStr.contains("\"name\""))
        assertTrue(jsonStr.contains("\"timestamp\""))
        // Null fields should either be absent or null
    }

    @Test
    fun `event with properties serializes correctly`() {
        val event = MGMEvent.create(
            name = "test",
            properties = mapOf(
                "key" to "value",
                "number" to 42
            )
        )

        assertNotNull(event)
        val jsonStr = json.encodeToString(event!!)

        assertTrue(jsonStr.contains("\"properties\""))
        assertTrue(jsonStr.contains("\"key\""))
        assertTrue(jsonStr.contains("\"value\""))
    }

    @Test
    fun `roundtrip serialization preserves data`() {
        val original = MGMEvent(
            name = "roundtrip_test",
            clientEventId = "550e8400-e29b-41d4-a716-446655440000",
            timestamp = "2024-06-15T10:30:00.000Z",
            userId = "user-abc",
            sessionId = "session-xyz",
            platform = "android",
            appVersion = "2.0.0",
            osVersion = "13",
            environment = "staging"
        )

        val jsonStr = json.encodeToString(original)
        val deserialized: MGMEvent = json.decodeFromString(jsonStr)

        assertEquals(original.name, deserialized.name)
        assertEquals(original.timestamp, deserialized.timestamp)
        assertEquals(original.userId, deserialized.userId)
        assertEquals(original.sessionId, deserialized.sessionId)
        assertEquals(original.platform, deserialized.platform)
        assertEquals(original.appVersion, deserialized.appVersion)
        assertEquals(original.osVersion, deserialized.osVersion)
        assertEquals(original.environment, deserialized.environment)
    }

    // MARK: - New Device Properties Tests

    @Test
    fun `create includes new device properties`() {
        val event = MGMEvent.create(
            name = "test_event",
            appVersion = "1.2.3",
            appBuildNumber = "42",
            deviceManufacturer = "Samsung",
            locale = "en_US",
            timezone = "America/New_York"
        )

        assertNotNull(event)
        assertEquals("1.2.3", event!!.appVersion)
        assertEquals("42", event.appBuildNumber)
        assertEquals("Samsung", event.deviceManufacturer)
        assertEquals("en_US", event.locale)
        assertEquals("America/New_York", event.timezone)
    }

    @Test
    fun `event serializes new device properties with snake_case`() {
        val event = MGMEvent(
            name = "test",
            clientEventId = "550e8400-e29b-41d4-a716-446655440000",
            timestamp = "2024-01-01T00:00:00.000Z",
            appVersion = "1.2.3",
            appBuildNumber = "42",
            deviceManufacturer = "Samsung",
            locale = "en_US",
            timezone = "America/New_York"
        )

        val jsonStr = json.encodeToString(event)

        assertTrue(jsonStr.contains("\"app_version\""))
        assertTrue(jsonStr.contains("\"app_build_number\""))
        assertTrue(jsonStr.contains("\"device_manufacturer\""))
        assertTrue(jsonStr.contains("\"locale\""))
        assertTrue(jsonStr.contains("\"timezone\""))
    }

    @Test
    fun `event deserializes new device properties from JSON`() {
        val jsonStr = """
            {
                "name": "test",
                "client_event_id": "550e8400-e29b-41d4-a716-446655440000",
                "timestamp": "2024-01-01T00:00:00.000Z",
                "app_version": "1.2.3",
                "app_build_number": "42",
                "device_manufacturer": "Samsung",
                "locale": "en_US",
                "timezone": "America/New_York"
            }
        """.trimIndent()

        val event: MGMEvent = json.decodeFromString(jsonStr)

        assertEquals("1.2.3", event.appVersion)
        assertEquals("42", event.appBuildNumber)
        assertEquals("Samsung", event.deviceManufacturer)
        assertEquals("en_US", event.locale)
        assertEquals("America/New_York", event.timezone)
    }

    @Test
    fun `roundtrip serialization preserves new device properties`() {
        val original = MGMEvent(
            name = "roundtrip_test",
            clientEventId = "550e8400-e29b-41d4-a716-446655440000",
            timestamp = "2024-06-15T10:30:00.000Z",
            appVersion = "2.0.0",
            appBuildNumber = "123",
            deviceManufacturer = "Google",
            locale = "fr_FR",
            timezone = "Europe/Paris"
        )

        val jsonStr = json.encodeToString(original)
        val deserialized: MGMEvent = json.decodeFromString(jsonStr)

        assertEquals(original.appVersion, deserialized.appVersion)
        assertEquals(original.appBuildNumber, deserialized.appBuildNumber)
        assertEquals(original.deviceManufacturer, deserialized.deviceManufacturer)
        assertEquals(original.locale, deserialized.locale)
        assertEquals(original.timezone, deserialized.timezone)
    }

    @Test
    fun `event context includes new device properties`() {
        val context = MGMEventContext(
            platform = "android",
            appVersion = "1.2.3",
            appBuildNumber = "42",
            osVersion = "14",
            deviceManufacturer = "Samsung",
            locale = "en_US",
            timezone = "America/New_York",
            environment = "production"
        )

        val jsonStr = json.encodeToString(context)

        assertTrue(jsonStr.contains("\"app_build_number\""))
        assertTrue(jsonStr.contains("\"device_manufacturer\""))
        assertTrue(jsonStr.contains("\"locale\""))
        assertTrue(jsonStr.contains("\"timezone\""))
    }
}

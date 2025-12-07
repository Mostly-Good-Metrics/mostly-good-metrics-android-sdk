package com.mostlygoodmetrics.sdk

import org.junit.Assert.*
import org.junit.Test

class MGMConfigurationTest {

    @Test
    fun `builder creates configuration with defaults`() {
        val config = MGMConfiguration.Builder("test-api-key").build()

        assertEquals("test-api-key", config.apiKey)
        assertEquals(MGMConfiguration.DEFAULT_BASE_URL, config.baseUrl)
        assertEquals(MGMConfiguration.DEFAULT_ENVIRONMENT, config.environment)
        assertNull(config.packageName)
        assertEquals(MGMConfiguration.DEFAULT_MAX_BATCH_SIZE, config.maxBatchSize)
        assertEquals(MGMConfiguration.DEFAULT_FLUSH_INTERVAL_SECONDS, config.flushIntervalSeconds)
        assertEquals(MGMConfiguration.DEFAULT_MAX_STORED_EVENTS, config.maxStoredEvents)
        assertFalse(config.enableDebugLogging)
        assertTrue(config.trackAppLifecycleEvents)
    }

    @Test
    fun `builder allows customization`() {
        val config = MGMConfiguration.Builder("my-key")
            .baseUrl("https://custom.example.com")
            .environment("staging")
            .packageName("com.example.app")
            .maxBatchSize(50)
            .flushIntervalSeconds(60)
            .maxStoredEvents(5000)
            .enableDebugLogging(true)
            .trackAppLifecycleEvents(false)
            .build()

        assertEquals("my-key", config.apiKey)
        assertEquals("https://custom.example.com", config.baseUrl)
        assertEquals("staging", config.environment)
        assertEquals("com.example.app", config.packageName)
        assertEquals(50, config.maxBatchSize)
        assertEquals(60L, config.flushIntervalSeconds)
        assertEquals(5000, config.maxStoredEvents)
        assertTrue(config.enableDebugLogging)
        assertFalse(config.trackAppLifecycleEvents)
    }

    @Test
    fun `maxBatchSize is clamped to valid range`() {
        val configMin = MGMConfiguration.Builder("key")
            .maxBatchSize(0)
            .build()
        assertEquals(1, configMin.maxBatchSize)

        val configMax = MGMConfiguration.Builder("key")
            .maxBatchSize(2000)
            .build()
        assertEquals(1000, configMax.maxBatchSize)

        val configValid = MGMConfiguration.Builder("key")
            .maxBatchSize(500)
            .build()
        assertEquals(500, configValid.maxBatchSize)
    }

    @Test
    fun `flushIntervalSeconds has minimum of 1`() {
        val config = MGMConfiguration.Builder("key")
            .flushIntervalSeconds(0)
            .build()
        assertEquals(1L, config.flushIntervalSeconds)

        val configNegative = MGMConfiguration.Builder("key")
            .flushIntervalSeconds(-10)
            .build()
        assertEquals(1L, configNegative.flushIntervalSeconds)
    }

    @Test
    fun `maxStoredEvents has minimum of 100`() {
        val config = MGMConfiguration.Builder("key")
            .maxStoredEvents(50)
            .build()
        assertEquals(100, config.maxStoredEvents)

        val configValid = MGMConfiguration.Builder("key")
            .maxStoredEvents(5000)
            .build()
        assertEquals(5000, configValid.maxStoredEvents)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `builder throws on blank API key`() {
        MGMConfiguration.Builder("   ").build()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `builder throws on empty API key`() {
        MGMConfiguration.Builder("").build()
    }
}

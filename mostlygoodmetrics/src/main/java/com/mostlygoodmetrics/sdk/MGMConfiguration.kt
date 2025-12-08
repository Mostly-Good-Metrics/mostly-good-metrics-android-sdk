package com.mostlygoodmetrics.sdk

/**
 * Configuration options for MostlyGoodMetrics SDK.
 * Use [Builder] to create an instance with custom settings.
 */
class MGMConfiguration private constructor(
    val apiKey: String,
    val baseUrl: String,
    val environment: String,
    val packageName: String?,
    val maxBatchSize: Int,
    val flushIntervalSeconds: Long,
    val maxStoredEvents: Int,
    val enableDebugLogging: Boolean,
    val trackAppLifecycleEvents: Boolean,
    val wrapperName: String?,
    val wrapperVersion: String?
) {
    /**
     * Builder for creating [MGMConfiguration] instances.
     *
     * @param apiKey Required API key for authentication
     */
    class Builder(private val apiKey: String) {
        private var baseUrl: String = DEFAULT_BASE_URL
        private var environment: String = DEFAULT_ENVIRONMENT
        private var packageName: String? = null
        private var maxBatchSize: Int = DEFAULT_MAX_BATCH_SIZE
        private var flushIntervalSeconds: Long = DEFAULT_FLUSH_INTERVAL_SECONDS
        private var maxStoredEvents: Int = DEFAULT_MAX_STORED_EVENTS
        private var enableDebugLogging: Boolean = false
        private var trackAppLifecycleEvents: Boolean = true
        private var wrapperName: String? = null
        private var wrapperVersion: String? = null

        /**
         * Set the base URL for the API endpoint.
         * Default: https://mostlygoodmetrics.com
         */
        fun baseUrl(url: String) = apply { this.baseUrl = url }

        /**
         * Set the environment name (e.g., "production", "staging", "development").
         * Default: "production"
         */
        fun environment(env: String) = apply { this.environment = env }

        /**
         * Override the package name (bundle identifier).
         * Default: Uses the application's package name
         */
        fun packageName(name: String) = apply { this.packageName = name }

        /**
         * Set the maximum number of events to send in a single batch.
         * Valid range: 1-1000. Values outside this range will be clamped.
         * Default: 100
         */
        fun maxBatchSize(size: Int) = apply {
            this.maxBatchSize = size.coerceIn(1, 1000)
        }

        /**
         * Set the interval between automatic flush operations in seconds.
         * Minimum value: 1 second.
         * Default: 30 seconds
         */
        fun flushIntervalSeconds(seconds: Long) = apply {
            this.flushIntervalSeconds = maxOf(1L, seconds)
        }

        /**
         * Set the maximum number of events to store locally.
         * Minimum value: 100.
         * Default: 10000
         */
        fun maxStoredEvents(count: Int) = apply {
            this.maxStoredEvents = maxOf(100, count)
        }

        /**
         * Enable or disable debug logging.
         * Default: false
         */
        fun enableDebugLogging(enable: Boolean) = apply {
            this.enableDebugLogging = enable
        }

        /**
         * Enable or disable automatic app lifecycle event tracking.
         * When enabled, tracks: $app_installed, $app_updated, $app_opened, $app_backgrounded
         * Default: true
         */
        fun trackAppLifecycleEvents(track: Boolean) = apply {
            this.trackAppLifecycleEvents = track
        }

        /**
         * Set the wrapper SDK name (e.g., "react-native", "flutter", "expo").
         * Used by hybrid framework SDKs to identify themselves.
         * Default: null (no wrapper)
         */
        fun wrapperName(name: String?) = apply {
            this.wrapperName = name
        }

        /**
         * Set the wrapper SDK version.
         * Used by hybrid framework SDKs to identify their version.
         * Default: null (no wrapper)
         */
        fun wrapperVersion(version: String?) = apply {
            this.wrapperVersion = version
        }

        /**
         * Build the configuration instance.
         */
        fun build(): MGMConfiguration {
            require(apiKey.isNotBlank()) { "API key cannot be blank" }

            return MGMConfiguration(
                apiKey = apiKey,
                baseUrl = baseUrl,
                environment = environment,
                packageName = packageName,
                maxBatchSize = maxBatchSize,
                flushIntervalSeconds = flushIntervalSeconds,
                maxStoredEvents = maxStoredEvents,
                enableDebugLogging = enableDebugLogging,
                trackAppLifecycleEvents = trackAppLifecycleEvents,
                wrapperName = wrapperName,
                wrapperVersion = wrapperVersion
            )
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://mostlygoodmetrics.com"
        const val DEFAULT_ENVIRONMENT = "production"
        const val DEFAULT_MAX_BATCH_SIZE = 100
        const val DEFAULT_FLUSH_INTERVAL_SECONDS = 30L
        const val DEFAULT_MAX_STORED_EVENTS = 10000
    }
}

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
    val optedOutByDefault: Boolean,
    val collectDeviceProperties: Boolean,
    val existingInstallation: Boolean,
    val contextProvider: (() -> Map<String, Any?>)?,
    val wrapperName: String?,
    val wrapperVersion: String?,
    val experimentMode: MGMExperimentMode,
    val localExperiments: List<MGMExperimentConfig>
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
        private var optedOutByDefault: Boolean = false
        private var collectDeviceProperties: Boolean = true
        private var existingInstallation: Boolean = false
        private var contextProvider: (() -> Map<String, Any?>)? = null
        private var wrapperName: String? = null
        private var wrapperVersion: String? = null
        private var experimentMode: MGMExperimentMode = MGMExperimentMode.SERVER
        private var localExperiments: List<MGMExperimentConfig> = emptyList()

        /**
         * Set the base URL for the API endpoint.
         * Default: https://ingest.mostlygoodmetrics.com
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
         * Start the SDK in the opted-out state until the user explicitly opts in.
         * Useful for consent-first apps (e.g., GDPR): no events are tracked or
         * sent until [MostlyGoodMetrics.optIn] is called. A persisted opt-in or
         * opt-out choice always takes precedence over this default.
         * Default: false
         */
        fun optedOutByDefault(optedOut: Boolean) = apply {
            this.optedOutByDefault = optedOut
        }

        /**
         * Enable or disable collection of device-level properties.
         * When disabled, events omit `$device_model`, `$device_type`,
         * `device_manufacturer`, `locale`, and `timezone`. Platform, OS version,
         * and app version are always included.
         * Default: true
         */
        fun collectDeviceProperties(collect: Boolean) = apply {
            this.collectDeviceProperties = collect
        }

        /**
         * Mark a known existing device installation when migrating from
         * another analytics provider. On its first MGM launch, the SDK records
         * the current version as its lifecycle baseline without sending
         * `$app_installed`. Later version changes still send `$app_updated`.
         * Pass true only when the host app can identify an existing install
         * (for example, using its own prior-install marker).
         * Default: false.
         */
        fun existingInstallation(existing: Boolean = true) = apply {
            this.existingInstallation = existing
        }

        /**
         * Provide dynamic properties evaluated every time an event is tracked.
         * Context properties override super properties; explicit event
         * properties override context; MGM system properties always win.
         * Exceptions are ignored so analytics never disrupts the host app.
         */
        fun contextProvider(provider: (() -> Map<String, Any?>)?) = apply {
            this.contextProvider = provider
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
         * Set how experiment variants are assigned.
         *
         * [MGMExperimentMode.SERVER] (the default) fetches server-assigned
         * variants. [MGMExperimentMode.LOCAL] buckets deterministically on
         * device from experiment configs — no per-user assignment request
         * ever leaves the device.
         * Default: [MGMExperimentMode.SERVER]
         */
        fun experimentMode(mode: MGMExperimentMode) = apply {
            this.experimentMode = mode
        }

        /**
         * Supply experiment configs inline for [MGMExperimentMode.LOCAL].
         *
         * When set (together with `experimentMode(MGMExperimentMode.LOCAL)`),
         * the SDK buckets on device from these configs and performs no
         * experiments network fetch at all.
         * Default: empty (LOCAL mode fetches configs from the server)
         */
        fun localExperiments(experiments: List<MGMExperimentConfig>) = apply {
            this.localExperiments = experiments.toList()
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
                optedOutByDefault = optedOutByDefault,
                collectDeviceProperties = collectDeviceProperties,
                existingInstallation = existingInstallation,
                contextProvider = contextProvider,
                wrapperName = wrapperName,
                wrapperVersion = wrapperVersion,
                experimentMode = experimentMode,
                localExperiments = localExperiments
            )
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://ingest.mostlygoodmetrics.com"
        const val DEFAULT_ENVIRONMENT = "production"
        const val DEFAULT_MAX_BATCH_SIZE = 100
        const val DEFAULT_FLUSH_INTERVAL_SECONDS = 30L
        const val DEFAULT_MAX_STORED_EVENTS = 10000
    }
}

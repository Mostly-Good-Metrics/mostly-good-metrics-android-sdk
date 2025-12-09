package com.mostlygoodmetrics.sdk

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MostlyGoodMetrics SDK for Android.
 *
 * Main entry point for tracking analytics events.
 *
 * ## Quick Start
 * ```kotlin
 * // Initialize in your Application class
 * MostlyGoodMetrics.configure(this, "your-api-key")
 *
 * // Track events
 * MostlyGoodMetrics.track("button_clicked", mapOf("button_name" to "submit"))
 *
 * // Identify users
 * MostlyGoodMetrics.identify("user-123")
 * ```
 */
class MostlyGoodMetrics private constructor(
    private val context: Context?,
    private val configuration: MGMConfiguration,
    private val storage: EventStorage,
    private val networkClient: NetworkClientInterface,
    private val prefs: SharedPreferences?
) {
    /**
     * Primary constructor for production use.
     */
    private constructor(
        context: Context,
        configuration: MGMConfiguration
    ) : this(
        context = context,
        configuration = configuration,
        storage = FileEventStorage(context, configuration.maxStoredEvents),
        networkClient = NetworkClient(configuration),
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var flushJob: Job? = null
    private val isFlushing = AtomicBoolean(false)

    /**
     * Current user identifier. Persisted across app launches.
     */
    var userId: String? = null
        private set

    /**
     * Current session identifier. Generated per app launch.
     */
    var sessionId: String = UUID.randomUUID().toString()
        private set

    /**
     * Number of events waiting to be sent.
     */
    val pendingEventCount: Int
        get() = storage.eventCount()

    /**
     * Whether events are currently being flushed.
     */
    val isFlushingEvents: Boolean
        get() = isFlushing.get()

    init {
        MGMLogger.isEnabled = configuration.enableDebugLogging
        MGMLogger.info("Initializing MostlyGoodMetrics SDK")

        // Restore user ID
        userId = prefs?.getString(KEY_USER_ID, null)

        // Start flush timer
        startFlushTimer()

        // Setup lifecycle tracking (only when context is available)
        if (configuration.trackAppLifecycleEvents && context != null) {
            setupLifecycleTracking()
        }

        // Track install/update (only when context is available)
        if (context != null) {
            trackInstallOrUpdate()
        }
    }

    /**
     * Track an event.
     *
     * @param name Event name. Must start with a letter (or $ for system events),
     *             followed by alphanumeric characters or underscores. Max 255 chars.
     * @param properties Optional map of custom properties.
     */
    fun track(name: String, properties: Map<String, Any?>? = null) {
        if (!MGMEvent.isValidEventName(name)) {
            MGMLogger.warn("Invalid event name: $name")
            return
        }

        val mergedProperties = buildProperties(properties)

        val event = MGMEvent.create(
            name = name,
            userId = userId,
            sessionId = sessionId,
            platform = PLATFORM,
            appVersion = getAppVersion(),
            osVersion = getOsVersion(),
            environment = configuration.environment,
            properties = mergedProperties
        )

        if (event != null) {
            storage.store(event)
            MGMLogger.debug("Tracked event: $name")

            // Auto-flush if batch size reached
            if (storage.eventCount() >= configuration.maxBatchSize) {
                flush()
            }
        }
    }

    /**
     * Identify a user.
     *
     * @param userId Unique identifier for the user. Persisted across app launches.
     */
    fun identify(userId: String) {
        this.userId = userId
        prefs?.edit()?.putString(KEY_USER_ID, userId)?.apply()
        MGMLogger.info("User identified: $userId")
    }

    /**
     * Reset the user identity. Clears the persisted user ID.
     */
    fun resetIdentity() {
        userId = null
        prefs?.edit()?.remove(KEY_USER_ID)?.apply()
        MGMLogger.info("User identity reset")
    }

    /**
     * Start a new session. Generates a new session ID.
     */
    fun startNewSession() {
        sessionId = UUID.randomUUID().toString()
        MGMLogger.info("New session started: $sessionId")
    }

    /**
     * Manually flush pending events to the server.
     *
     * @param completion Optional callback when flush completes.
     */
    fun flush(completion: ((Result<Unit>) -> Unit)? = null) {
        scope.launch {
            flushInternal()
            completion?.invoke(Result.success(Unit))
        }
    }

    /**
     * Clear all pending events without sending them.
     */
    fun clearPendingEvents() {
        storage.clear()
        MGMLogger.info("Pending events cleared")
    }

    private suspend fun flushInternal() {
        if (!isFlushing.compareAndSet(false, true)) {
            MGMLogger.debug("Flush already in progress, skipping")
            return
        }

        try {
            while (storage.eventCount() > 0) {
                val events = storage.fetchEvents(configuration.maxBatchSize)
                if (events.isEmpty()) break

                val context = MGMEventContext(
                    platform = PLATFORM,
                    appVersion = getAppVersion(),
                    osVersion = getOsVersion(),
                    userId = userId,
                    sessionId = sessionId,
                    environment = configuration.environment
                )

                val payload = MGMEventsPayload(events = events, context = context)
                val result = networkClient.sendEvents(payload)

                when (result) {
                    is SendResult.Success -> {
                        storage.removeEvents(events)
                        MGMLogger.debug("Successfully flushed ${events.size} events")
                    }
                    is SendResult.DropEvents -> {
                        storage.removeEvents(events)
                        MGMLogger.warn("Dropped ${events.size} events due to: ${result.error.message}")
                    }
                    is SendResult.RetryLater -> {
                        MGMLogger.debug("Will retry ${events.size} events later: ${result.error.message}")
                        break
                    }
                }

                // Small delay between batches to avoid hammering the server
                delay(100)
            }
        } finally {
            isFlushing.set(false)
        }
    }

    private fun startFlushTimer() {
        flushJob?.cancel()
        flushJob = scope.launch {
            while (true) {
                delay(configuration.flushIntervalSeconds * 1000)
                flushInternal()
            }
        }
    }

    private fun setupLifecycleTracking() {
        val lifecycleObserver = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                track("\$app_opened")
            }

            override fun onStop(owner: LifecycleOwner) {
                track("\$app_backgrounded")
                // Flush when app goes to background
                flush()
            }
        }

        // Must be called on main thread
        scope.launch(Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
        }
    }

    private fun trackInstallOrUpdate() {
        val currentVersion = getAppVersion()
        val storedVersion = prefs?.getString(KEY_APP_VERSION, null)

        when {
            storedVersion == null -> {
                // First install
                track("\$app_installed", mapOf("version" to currentVersion))
            }
            storedVersion != currentVersion -> {
                // App updated
                track("\$app_updated", mapOf(
                    "previous_version" to storedVersion,
                    "version" to currentVersion
                ))
            }
        }

        prefs?.edit()?.putString(KEY_APP_VERSION, currentVersion)?.apply()
    }

    private fun buildProperties(userProperties: Map<String, Any?>?): Map<String, Any?> {
        val systemProperties = mapOf(
            "\$device_type" to getDeviceType(),
            "\$device_model" to (Build.MODEL ?: "unknown"),
            "\$sdk" to (configuration.wrapperName ?: "android")
        )

        return if (userProperties != null) {
            systemProperties + userProperties
        } else {
            systemProperties
        }
    }

    private fun getAppVersion(): String {
        val ctx = context ?: return "unknown"
        return try {
            val packageInfo = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            val versionName = packageInfo.versionName ?: "unknown"
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            "$versionName ($versionCode)"
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun getOsVersion(): String {
        return Build.VERSION.RELEASE ?: "unknown"
    }

    private fun getDeviceType(): String {
        val ctx = context ?: return "phone"
        val metrics = ctx.resources.displayMetrics
        val widthDp = metrics.widthPixels / metrics.density
        return if (widthDp >= 600) "tablet" else "phone"
    }

    /**
     * Shutdown the SDK and release resources.
     */
    fun shutdown() {
        MGMLogger.info("Shutting down MostlyGoodMetrics SDK")
        flushJob?.cancel()
        scope.cancel()
    }

    companion object {
        private const val PREFS_NAME = "mostly_good_metrics"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_APP_VERSION = "app_version"
        private const val PLATFORM = "android"

        @Volatile
        private var instance: MostlyGoodMetrics? = null

        /**
         * The shared SDK instance. Must call [configure] first.
         */
        @JvmStatic
        val shared: MostlyGoodMetrics?
            get() = instance

        /**
         * Configure the SDK with just an API key (uses defaults).
         *
         * @param context Application context
         * @param apiKey Your MostlyGoodMetrics API key
         */
        @JvmStatic
        fun configure(context: Context, apiKey: String) {
            val config = MGMConfiguration.Builder(apiKey).build()
            configure(context, config)
        }

        /**
         * Configure the SDK with a custom configuration.
         *
         * @param context Application context
         * @param configuration Custom configuration
         */
        @JvmStatic
        fun configure(context: Context, configuration: MGMConfiguration) {
            synchronized(this) {
                if (instance != null) {
                    MGMLogger.warn("MostlyGoodMetrics already configured, ignoring")
                    return
                }

                val appContext = context.applicationContext
                val configWithPackage = if (configuration.packageName == null) {
                    MGMConfiguration.Builder(configuration.apiKey)
                        .baseUrl(configuration.baseUrl)
                        .environment(configuration.environment)
                        .packageName(appContext.packageName)
                        .maxBatchSize(configuration.maxBatchSize)
                        .flushIntervalSeconds(configuration.flushIntervalSeconds)
                        .maxStoredEvents(configuration.maxStoredEvents)
                        .enableDebugLogging(configuration.enableDebugLogging)
                        .trackAppLifecycleEvents(configuration.trackAppLifecycleEvents)
                        .wrapperName(configuration.wrapperName)
                        .wrapperVersion(configuration.wrapperVersion)
                        .build()
                } else {
                    configuration
                }

                instance = MostlyGoodMetrics(appContext, configWithPackage)
            }
        }

        /**
         * Track an event using the shared instance.
         */
        @JvmStatic
        @JvmName("trackEvent")
        fun track(name: String, properties: Map<String, Any?>? = null) {
            instance?.track(name, properties)
                ?: MGMLogger.warn("MostlyGoodMetrics not configured. Call configure() first.")
        }

        /**
         * Identify a user using the shared instance.
         */
        @JvmStatic
        @JvmName("identifyUser")
        fun identify(userId: String) {
            instance?.identify(userId)
                ?: MGMLogger.warn("MostlyGoodMetrics not configured. Call configure() first.")
        }

        /**
         * Reset user identity using the shared instance.
         */
        @JvmStatic
        @JvmName("resetUserIdentity")
        fun resetIdentity() {
            instance?.resetIdentity()
                ?: MGMLogger.warn("MostlyGoodMetrics not configured. Call configure() first.")
        }

        /**
         * Flush pending events using the shared instance.
         */
        @JvmStatic
        @JvmName("flushEvents")
        fun flush(completion: ((Result<Unit>) -> Unit)? = null) {
            instance?.flush(completion)
                ?: MGMLogger.warn("MostlyGoodMetrics not configured. Call configure() first.")
        }

        /**
         * Start a new session using the shared instance.
         */
        @JvmStatic
        @JvmName("startNewSessionStatic")
        fun startNewSession() {
            instance?.startNewSession()
                ?: MGMLogger.warn("MostlyGoodMetrics not configured. Call configure() first.")
        }

        /**
         * Get the current user ID from the shared instance.
         */
        @JvmStatic
        @get:JvmName("getCurrentUserId")
        val userId: String?
            get() = instance?.userId

        /**
         * Get the current session ID from the shared instance.
         */
        @JvmStatic
        @get:JvmName("getCurrentSessionId")
        val sessionId: String?
            get() = instance?.sessionId

        /**
         * Get the pending event count from the shared instance.
         */
        @JvmStatic
        @get:JvmName("getCurrentPendingEventCount")
        val pendingEventCount: Int
            get() = instance?.pendingEventCount ?: 0

        /**
         * Reset the SDK (mainly for testing).
         */
        @JvmStatic
        internal fun reset() {
            synchronized(this) {
                instance?.shutdown()
                instance = null
            }
        }

        /**
         * Create a test instance with injected dependencies.
         * For testing only - not for production use.
         */
        internal fun createForTesting(
            configuration: MGMConfiguration,
            storage: EventStorage,
            networkClient: NetworkClientInterface
        ): MostlyGoodMetrics {
            return MostlyGoodMetrics(
                context = null,
                configuration = configuration,
                storage = storage,
                networkClient = networkClient,
                prefs = null
            )
        }
    }
}

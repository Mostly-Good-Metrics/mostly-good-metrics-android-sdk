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
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

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

    // A/B Testing state
    private val assignedVariants: MutableMap<String, String> = mutableMapOf()
    private var experimentsLoaded = false
    private val experimentsReadyCallbacks: MutableList<() -> Unit> = mutableListOf()

    /**
     * Current user identifier. Persisted across app launches.
     */
    var userId: String? = null
        private set

    /**
     * Anonymous user ID. Auto-generated and persisted across app launches.
     * Format: $anon_xxxxxxxxxxxx (12 random alphanumeric chars)
     */
    var anonymousId: String = ""
        private set

    /**
     * The effective user ID to use in events (identified user or anonymous).
     */
    private val effectiveUserId: String
        get() = userId ?: anonymousId

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

        // Restore or generate anonymous ID
        anonymousId = prefs?.getString(KEY_ANONYMOUS_ID, null) ?: run {
            val newId = generateAnonymousId()
            prefs?.edit()?.putString(KEY_ANONYMOUS_ID, newId)?.apply()
            newId
        }

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

        // Fetch experiments in background
        fetchExperimentsInBackground()
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
            userId = effectiveUserId,
            sessionId = sessionId,
            platform = PLATFORM,
            appVersion = getAppVersion(),
            appBuildNumber = getAppBuildNumber(),
            osVersion = getOsVersion(),
            environment = configuration.environment,
            deviceManufacturer = getDeviceManufacturer(),
            locale = getLocale(),
            timezone = getTimezone(),
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
     * Identify a user with optional profile data.
     * Profile data (email, name) is sent to the backend via the $identify event.
     * Debouncing: only sends $identify if payload changed or >24h since last send.
     *
     * This also invalidates the experiment variants cache and refetches experiments
     * with the new user ID to handle the "identified wins" aliasing strategy.
     *
     * @param userId Unique identifier for the user. Persisted across app launches.
     * @param profile Optional profile data (email, name)
     */
    @JvmOverloads
    fun identify(userId: String, profile: UserProfile? = null) {
        val previousUserId = this.userId ?: anonymousId
        this.userId = userId
        prefs?.edit()?.putString(KEY_USER_ID, userId)?.apply()
        MGMLogger.info("User identified: $userId")

        // If profile data is provided, check if we should send $identify event
        if (profile != null && (profile.email != null || profile.name != null)) {
            sendIdentifyEventIfNeeded(userId, profile)
        }

        // If user ID changed, invalidate experiment cache and refetch
        // This handles the "identified wins" aliasing strategy on the server
        if (previousUserId != userId) {
            MGMLogger.debug("User identity changed, refetching experiments")
            invalidateExperimentsCache()
            fetchExperimentsInBackground()
        }
    }

    /**
     * Send $identify event if debounce conditions are met.
     * Only sends if: hash changed OR more than 24 hours since last send.
     */
    private fun sendIdentifyEventIfNeeded(userId: String, profile: UserProfile) {
        val currentHash = computeIdentifyHash(userId, profile)
        val storedHash = prefs?.getString(KEY_IDENTIFY_HASH, null)
        val lastSentAt = prefs?.getLong(KEY_IDENTIFY_TIMESTAMP, 0L) ?: 0L
        val now = System.currentTimeMillis()

        val hashChanged = storedHash != currentHash
        val expiredTime = lastSentAt == 0L || (now - lastSentAt) > TWENTY_FOUR_HOURS_MS

        if (hashChanged || expiredTime) {
            MGMLogger.debug("Sending \$identify event (hashChanged=$hashChanged, expiredTime=$expiredTime)")

            // Build properties with only defined values
            val properties = mutableMapOf<String, Any?>()
            profile.email?.let { properties["email"] = it }
            profile.name?.let { properties["name"] = it }

            // Track the $identify event
            track("\$identify", properties)

            // Update stored hash and timestamp
            prefs?.edit()
                ?.putString(KEY_IDENTIFY_HASH, currentHash)
                ?.putLong(KEY_IDENTIFY_TIMESTAMP, now)
                ?.apply()
        } else {
            MGMLogger.debug("Skipping \$identify event (debounced)")
        }
    }

    /**
     * Compute a simple hash for debouncing identify calls.
     */
    private fun computeIdentifyHash(userId: String, profile: UserProfile): String {
        val payload = "$userId|${profile.email ?: ""}|${profile.name ?: ""}"
        var hash = 0
        for (char in payload) {
            hash = ((hash shl 5) - hash) + char.code
        }
        return hash.toString(16)
    }

    /**
     * Clear identify debounce state.
     */
    private fun clearIdentifyState() {
        prefs?.edit()
            ?.remove(KEY_IDENTIFY_HASH)
            ?.remove(KEY_IDENTIFY_TIMESTAMP)
            ?.apply()
    }

    /**
     * Reset the user identity. Clears the persisted user ID and identify debounce state.
     */
    fun resetIdentity() {
        userId = null
        prefs?.edit()?.remove(KEY_USER_ID)?.apply()
        clearIdentifyState()
        MGMLogger.info("User identity reset")
    }

    /**
     * Start a new session. Generates a new session ID.
     */
    fun startNewSession() {
        sessionId = UUID.randomUUID().toString()
        MGMLogger.info("New session started: $sessionId")
    }

    // region Super Properties

    /**
     * Set a single super property that will be included with every event.
     *
     * @param key The property key
     * @param value The property value
     */
    fun setSuperProperty(key: String, value: Any?) {
        val properties = getSuperProperties().toMutableMap()
        properties[key] = value
        saveSuperProperties(properties)
        MGMLogger.debug("Set super property: $key")
    }

    /**
     * Set multiple super properties at once.
     *
     * @param properties Map of properties to set
     */
    fun setSuperProperties(properties: Map<String, Any?>) {
        val current = getSuperProperties().toMutableMap()
        current.putAll(properties)
        saveSuperProperties(current)
        MGMLogger.debug("Set super properties: ${properties.keys.joinToString(", ")}")
    }

    /**
     * Remove a single super property.
     *
     * @param key The property key to remove
     */
    fun removeSuperProperty(key: String) {
        val properties = getSuperProperties().toMutableMap()
        properties.remove(key)
        saveSuperProperties(properties)
        MGMLogger.debug("Removed super property: $key")
    }

    /**
     * Clear all super properties.
     */
    fun clearSuperProperties() {
        prefs?.edit()?.remove(KEY_SUPER_PROPERTIES)?.apply()
        MGMLogger.debug("Cleared all super properties")
    }

    /**
     * Get all current super properties.
     *
     * @return Map of super properties
     */
    fun getSuperProperties(): Map<String, Any?> {
        val json = prefs?.getString(KEY_SUPER_PROPERTIES, null) ?: return emptyMap()
        return try {
            @Suppress("UNCHECKED_CAST")
            org.json.JSONObject(json).toMap()
        } catch (e: Exception) {
            MGMLogger.warn("Failed to parse super properties: ${e.message}")
            emptyMap()
        }
    }

    private fun saveSuperProperties(properties: Map<String, Any?>) {
        try {
            val json = org.json.JSONObject(properties).toString()
            prefs?.edit()?.putString(KEY_SUPER_PROPERTIES, json)?.apply()
        } catch (e: Exception) {
            MGMLogger.warn("Failed to save super properties: ${e.message}")
        }
    }

    private fun org.json.JSONObject.toMap(): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        val keys = this.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = this.get(key)
        }
        return map
    }

    // endregion

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
                    appBuildNumber = getAppBuildNumber(),
                    osVersion = getOsVersion(),
                    userId = effectiveUserId,
                    sessionId = sessionId,
                    environment = configuration.environment,
                    deviceManufacturer = getDeviceManufacturer(),
                    locale = getLocale(),
                    timezone = getTimezone()
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

        // Merge properties: super properties < user properties < system properties
        // User properties override super properties, system properties are always added
        val superProperties = getSuperProperties()
        val merged = superProperties.toMutableMap()

        if (userProperties != null) {
            merged.putAll(userProperties)
        }

        merged.putAll(systemProperties)

        return merged
    }

    private fun getAppVersion(): String {
        val ctx = context ?: return "unknown"
        return try {
            val packageInfo = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            packageInfo.versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun getAppBuildNumber(): String {
        val ctx = context ?: return "unknown"
        return try {
            val packageInfo = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toString()
            }
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

    private fun getDeviceManufacturer(): String {
        return Build.MANUFACTURER ?: "unknown"
    }

    private fun getLocale(): String {
        return java.util.Locale.getDefault().toString()
    }

    private fun getTimezone(): String {
        return java.util.TimeZone.getDefault().id
    }

    /**
     * Shutdown the SDK and release resources.
     */
    fun shutdown() {
        MGMLogger.info("Shutting down MostlyGoodMetrics SDK")
        flushJob?.cancel()
        scope.cancel()
    }

    // region A/B Testing

    /**
     * Get the variant for an experiment.
     * Returns the assigned variant ('a', 'b', etc.) or null if experiment not found.
     *
     * Variants are assigned server-side and cached locally. The server ensures
     * the same user always gets the same variant for the same experiment.
     *
     * The variant is automatically stored as a super property ($experiment_{name}: 'variant')
     * so it's attached to all subsequent events.
     *
     * @param experimentName The name of the experiment
     * @return The assigned variant, or null if not found or not loaded
     */
    fun getVariant(experimentName: String): String? {
        if (experimentName.isBlank()) {
            MGMLogger.warn("getVariant called with empty experimentName")
            return null
        }

        // Check if we have a server-assigned variant for this experiment
        val variant = assignedVariants[experimentName]

        if (variant != null) {
            // Store as super property so it's attached to all events
            val propertyName = "\$experiment_${toSnakeCase(experimentName)}"
            setSuperProperty(propertyName, variant)

            MGMLogger.debug("Using server-assigned variant '$variant' for experiment '$experimentName'")
            return variant
        }

        // No assigned variant - check if experiments have been loaded
        if (!experimentsLoaded) {
            MGMLogger.debug("Experiments not loaded yet, cannot get variant for: $experimentName")
            return null
        }

        // Experiments loaded but no assignment for this experiment
        MGMLogger.debug("No variant assigned for experiment: $experimentName")
        return null
    }

    /**
     * Returns a suspend function that completes when experiments have been loaded.
     * Useful for waiting before calling getVariant() to ensure server-side experiments are available.
     */
    suspend fun ready() {
        if (experimentsLoaded) return
        suspendCoroutine { continuation ->
            synchronized(experimentsReadyCallbacks) {
                if (experimentsLoaded) {
                    continuation.resume(Unit)
                } else {
                    experimentsReadyCallbacks.add { continuation.resume(Unit) }
                }
            }
        }
    }

    /**
     * Returns a callback-based function that completes when experiments have been loaded.
     * Useful for Java interop or when coroutines aren't available.
     */
    fun ready(callback: () -> Unit) {
        synchronized(experimentsReadyCallbacks) {
            if (experimentsLoaded) {
                callback()
            } else {
                experimentsReadyCallbacks.add(callback)
            }
        }
    }

    /**
     * Check if experiments have been loaded.
     */
    val areExperimentsLoaded: Boolean
        get() = experimentsLoaded

    /**
     * Fetch experiments in the background.
     */
    private fun fetchExperimentsInBackground() {
        scope.launch {
            fetchExperiments()
        }
    }

    /**
     * Fetch experiments from the server.
     * Called automatically during initialization and after identify().
     *
     * Flow:
     * 1. Check SharedPreferences cache - if valid and same user, use cached variants
     * 2. Otherwise, fetch from server with user_id
     * 3. Store response in memory and SharedPreferences cache
     */
    private suspend fun fetchExperiments() {
        val currentUserId = effectiveUserId

        // Check SharedPreferences cache first
        val cachedUserId = prefs?.getString(KEY_EXPERIMENTS_USER_ID, null)
        val cachedFetchedAt = prefs?.getLong(KEY_EXPERIMENTS_FETCHED_AT, 0L) ?: 0L
        val cacheAge = System.currentTimeMillis() - cachedFetchedAt

        if (cachedUserId == currentUserId && cachedFetchedAt > 0 && cacheAge < EXPERIMENTS_CACHE_TTL_MS) {
            // Use cached variants
            val cachedVariantsJson = prefs?.getString(KEY_EXPERIMENTS_VARIANTS, null)
            if (cachedVariantsJson != null) {
                try {
                    val cachedVariants = parseVariantsFromJson(cachedVariantsJson)
                    synchronized(assignedVariants) {
                        assignedVariants.clear()
                        assignedVariants.putAll(cachedVariants)
                    }
                    MGMLogger.debug("Using cached experiment variants (age: ${cacheAge / 1000 / 60}min)")
                    markExperimentsReady()
                    return
                } catch (e: Exception) {
                    MGMLogger.debug("Failed to load experiments cache: ${e.message}")
                }
            }
        }

        // Fetch from server
        val result = networkClient.fetchExperiments(currentUserId)

        when (result) {
            is ExperimentsResult.Success -> {
                synchronized(assignedVariants) {
                    assignedVariants.clear()
                    assignedVariants.putAll(result.response.assignedVariants)
                }

                // Cache in SharedPreferences
                saveExperimentsCache(currentUserId, result.response.assignedVariants)
            }
            is ExperimentsResult.Failure -> {
                MGMLogger.warn("Failed to fetch experiments: ${result.error.message}")
            }
        }

        markExperimentsReady()
    }

    /**
     * Mark experiments as loaded and notify all waiting callbacks.
     */
    private fun markExperimentsReady() {
        synchronized(experimentsReadyCallbacks) {
            experimentsLoaded = true
            experimentsReadyCallbacks.forEach { it() }
            experimentsReadyCallbacks.clear()
        }
    }

    /**
     * Save experiment variants to SharedPreferences cache.
     */
    private fun saveExperimentsCache(userId: String, variants: Map<String, String>) {
        try {
            val variantsJson = org.json.JSONObject(variants).toString()
            prefs?.edit()
                ?.putString(KEY_EXPERIMENTS_USER_ID, userId)
                ?.putString(KEY_EXPERIMENTS_VARIANTS, variantsJson)
                ?.putLong(KEY_EXPERIMENTS_FETCHED_AT, System.currentTimeMillis())
                ?.apply()
        } catch (e: Exception) {
            MGMLogger.debug("Failed to save experiments cache: ${e.message}")
        }
    }

    /**
     * Parse variants from JSON string.
     */
    private fun parseVariantsFromJson(json: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val jsonObject = org.json.JSONObject(json)
        val keys = jsonObject.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = jsonObject.getString(key)
        }
        return result
    }

    /**
     * Invalidate (clear) the experiments cache.
     * Called when user identity changes.
     */
    private fun invalidateExperimentsCache() {
        // Clear in-memory state
        synchronized(assignedVariants) {
            assignedVariants.clear()
        }
        experimentsLoaded = false

        // Clear SharedPreferences cache
        prefs?.edit()
            ?.remove(KEY_EXPERIMENTS_USER_ID)
            ?.remove(KEY_EXPERIMENTS_VARIANTS)
            ?.remove(KEY_EXPERIMENTS_FETCHED_AT)
            ?.apply()

        MGMLogger.debug("Invalidated experiments cache")
    }

    /**
     * Convert a string to snake_case.
     * Used for experiment property names (e.g., "button-color" -> "button_color").
     */
    private fun toSnakeCase(str: String): String {
        return str
            .replace(Regex("([A-Z])")) { "_${it.value}" }
            .replace(Regex("[-\\s]+"), "_")
            .lowercase()
            .removePrefix("_")
    }

    // endregion

    companion object {
        private const val PREFS_NAME = "mostly_good_metrics"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_ANONYMOUS_ID = "anonymous_id"
        private const val KEY_APP_VERSION = "app_version"
        private const val KEY_SUPER_PROPERTIES = "super_properties"
        private const val KEY_IDENTIFY_HASH = "identify_hash"
        private const val KEY_IDENTIFY_TIMESTAMP = "identify_timestamp"
        private const val KEY_EXPERIMENTS_USER_ID = "experiments_user_id"
        private const val KEY_EXPERIMENTS_VARIANTS = "experiments_variants"
        private const val KEY_EXPERIMENTS_FETCHED_AT = "experiments_fetched_at"
        private const val PLATFORM = "android"
        private const val TWENTY_FOUR_HOURS_MS = 24 * 60 * 60 * 1000L
        private const val EXPERIMENTS_CACHE_TTL_MS = 24 * 60 * 60 * 1000L // 24 hours

        /**
         * Generate a random alphanumeric string of the given length.
         */
        private fun generateRandomString(length: Int): String {
            val chars = "0123456789abcdefghijklmnopqrstuvwxyz"
            return (1..length)
                .map { chars.random() }
                .joinToString("")
        }

        /**
         * Generate an anonymous user ID with $anon_ prefix.
         * Format: $anon_xxxxxxxxxxxx (12 random alphanumeric chars)
         */
        private fun generateAnonymousId(): String {
            return "\$anon_${generateRandomString(12)}"
        }

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
         * Identify a user using the shared instance with optional profile data.
         * Profile data (email, name) is sent to the backend via the $identify event.
         * Debouncing: only sends $identify if payload changed or >24h since last send.
         *
         * @param userId Unique identifier for the user
         * @param profile Optional profile data (email, name)
         */
        @JvmStatic
        @JvmOverloads
        @JvmName("identifyUser")
        fun identify(userId: String, profile: UserProfile? = null) {
            instance?.identify(userId, profile)
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
         * Get the current anonymous ID from the shared instance.
         */
        @JvmStatic
        @get:JvmName("getCurrentAnonymousId")
        val anonymousId: String?
            get() = instance?.anonymousId

        /**
         * Get the pending event count from the shared instance.
         */
        @JvmStatic
        @get:JvmName("getCurrentPendingEventCount")
        val pendingEventCount: Int
            get() = instance?.pendingEventCount ?: 0

        // region Super Properties (Static)

        /**
         * Set a single super property using the shared instance.
         */
        @JvmStatic
        @JvmName("setSuperPropertyStatic")
        fun setSuperProperty(key: String, value: Any?) {
            instance?.setSuperProperty(key, value)
                ?: MGMLogger.warn("MostlyGoodMetrics not configured. Call configure() first.")
        }

        /**
         * Set multiple super properties using the shared instance.
         */
        @JvmStatic
        @JvmName("setSuperPropertiesStatic")
        fun setSuperProperties(properties: Map<String, Any?>) {
            instance?.setSuperProperties(properties)
                ?: MGMLogger.warn("MostlyGoodMetrics not configured. Call configure() first.")
        }

        /**
         * Remove a single super property using the shared instance.
         */
        @JvmStatic
        @JvmName("removeSuperPropertyStatic")
        fun removeSuperProperty(key: String) {
            instance?.removeSuperProperty(key)
                ?: MGMLogger.warn("MostlyGoodMetrics not configured. Call configure() first.")
        }

        /**
         * Clear all super properties using the shared instance.
         */
        @JvmStatic
        @JvmName("clearSuperPropertiesStatic")
        fun clearSuperProperties() {
            instance?.clearSuperProperties()
                ?: MGMLogger.warn("MostlyGoodMetrics not configured. Call configure() first.")
        }

        /**
         * Get all current super properties from the shared instance.
         */
        @JvmStatic
        @JvmName("getSuperPropertiesStatic")
        fun getSuperProperties(): Map<String, Any?> {
            return instance?.getSuperProperties() ?: emptyMap()
        }

        // endregion

        // region A/B Testing (Static)

        /**
         * Get the variant for an experiment using the shared instance.
         * Returns the assigned variant ('a', 'b', etc.) or null if experiment not found.
         *
         * @param experimentName The name of the experiment
         * @return The assigned variant, or null if not found or SDK not configured
         */
        @JvmStatic
        @JvmName("getVariantStatic")
        fun getVariant(experimentName: String): String? {
            return instance?.getVariant(experimentName)
        }

        /**
         * Returns a suspend function that completes when experiments have been loaded.
         * Useful for waiting before calling getVariant() to ensure server-side experiments are available.
         */
        @JvmStatic
        suspend fun ready() {
            instance?.ready()
        }

        /**
         * Returns a callback-based function that completes when experiments have been loaded.
         * Useful for Java interop or when coroutines aren't available.
         */
        @JvmStatic
        @JvmName("readyWithCallback")
        fun ready(callback: () -> Unit) {
            instance?.ready(callback) ?: callback()
        }

        /**
         * Check if experiments have been loaded in the shared instance.
         */
        @JvmStatic
        @get:JvmName("getAreExperimentsLoaded")
        val areExperimentsLoaded: Boolean
            get() = instance?.areExperimentsLoaded ?: false

        // endregion

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

/**
 * User profile data for the identify() call.
 *
 * @property email The user's email address
 * @property name The user's display name
 */
data class UserProfile(
    val email: String? = null,
    val name: String? = null
)

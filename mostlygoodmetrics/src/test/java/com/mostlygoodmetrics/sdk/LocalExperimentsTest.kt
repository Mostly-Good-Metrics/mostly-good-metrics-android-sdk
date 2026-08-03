package com.mostlygoodmetrics.sdk

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for local experiment enrollment ([MGMExperimentMode.LOCAL]).
 *
 * Contract under test:
 * - SERVER mode stays the default and its behavior is unchanged.
 * - LOCAL mode buckets on device: bucket = first 8 bytes of
 *   SHA-256(utf8("<experiment_uuid>:<effective_user_id>")) interpreted as a
 *   big-endian UNSIGNED 64-bit integer; variant = variants[bucket % size].
 * - Inline configs via localExperiments() mean zero experiments network calls.
 * - Without inline configs, LOCAL mode fetches GET /v1/experiments/configs
 *   and caches the configs (stale-while-revalidate, ~1h throttle).
 * - Assignments are sticky per experiment UUID: persisted on first
 *   getVariant() and reused thereafter, including after identify().
 * - Exposure events are unchanged: $experiment_exposure with the raw
 *   experiment NAME, deduped per (user, experiment, variant).
 */
class LocalExperimentsTest {

    // region Golden vectors

    /**
     * Golden bucketing vectors shared with the server implementation
     * (experiment_hash_vectors.json). Do not edit by hand.
     */
    private data class GoldenVector(
        val experimentId: String,
        val userId: String,
        val variants: List<String>,
        val bucket: ULong,
        val expectedVariant: String
    )

    private val goldenVectors = listOf(
        GoldenVector(
            experimentId = "7b1e8a90-4c2d-4f6a-9e3b-2a1d5c8f0e71",
            userId = "user_123",
            variants = listOf("control", "treatment"),
            bucket = 11452140836674321702UL,
            expectedVariant = "control"
        ),
        GoldenVector(
            experimentId = "7b1e8a90-4c2d-4f6a-9e3b-2a1d5c8f0e71",
            userId = "\$anon_abc123def456",
            variants = listOf("control", "treatment"),
            bucket = 10935638356306450407UL,
            expectedVariant = "treatment"
        ),
        GoldenVector(
            experimentId = "3f9c2d11-8b7a-4e5f-a0c6-91d2e3f4a5b6",
            userId = "user_123",
            variants = listOf("a", "b", "c"),
            bucket = 3772238658190659659UL,
            expectedVariant = "c"
        ),
        GoldenVector(
            experimentId = "3f9c2d11-8b7a-4e5f-a0c6-91d2e3f4a5b6",
            userId = "chris@nihongo.example",
            variants = listOf("a", "b", "c"),
            bucket = 15293329125595004806UL,
            expectedVariant = "b"
        ),
        GoldenVector(
            experimentId = "c0ffee00-1234-5678-9abc-def012345678",
            userId = "u",
            variants = listOf("on", "off"),
            bucket = 5314609686893464838UL,
            expectedVariant = "on"
        ),
        GoldenVector(
            experimentId = "c0ffee00-1234-5678-9abc-def012345678",
            userId = "日本語ユーザー",
            variants = listOf("on", "off"),
            bucket = 15854517259962621242UL,
            expectedVariant = "on"
        ),
        GoldenVector(
            experimentId = "aaaaaaaa-bbbb-cccc-dddd-eeeeffff0000",
            // 64 trailing x's — must match the shared vector exactly
            userId = "user_with_long_id_" +
                "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
            variants = listOf("v1", "v2", "v3", "v4", "v5"),
            bucket = 16479651874404423415UL,
            expectedVariant = "v1"
        ),
        GoldenVector(
            experimentId = "aaaaaaaa-bbbb-cccc-dddd-eeeeffff0000",
            userId = "",
            variants = listOf("v1", "v2"),
            bucket = 2902893145859674316UL,
            expectedVariant = "v1"
        )
    )

    @Test
    fun `bucketing matches every golden vector byte-for-byte`() {
        goldenVectors.forEach { vector ->
            val bucket = LocalExperimentBucketing.bucket(vector.experimentId, vector.userId)
            assertEquals(
                "bucket(${vector.experimentId}, \"${vector.userId}\") must match the golden vector",
                vector.bucket,
                bucket
            )

            val config = MGMExperimentConfig(
                id = vector.experimentId,
                name = "golden-vector",
                variants = vector.variants
            )
            assertEquals(
                "variant for (${vector.experimentId}, \"${vector.userId}\") must match the golden vector",
                vector.expectedVariant,
                LocalExperimentBucketing.variantFor(config, vector.userId)
            )
        }
    }

    @Test
    fun `getVariant end-to-end matches golden vectors for identified and anonymous users`() {
        // Identified user (user_123) — persisted user_id drives the bucketing
        val identifiedVector = goldenVectors[0]
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("user_id", identifiedVector.userId).apply()
        val sdk = createLocalSdk(
            inline = listOf(
                MGMExperimentConfig(
                    id = identifiedVector.experimentId,
                    name = "button-color",
                    variants = identifiedVector.variants
                )
            ),
            prefs = prefs
        )
        assertEquals(identifiedVector.expectedVariant, sdk.getVariant("button-color"))
        sdk.shutdown()

        // Anonymous user — the stored anonymous ID is the effective user ID
        val anonVector = goldenVectors[1]
        val anonPrefs = FakeSharedPreferences()
        anonPrefs.edit().putString("anonymous_id", anonVector.userId).apply()
        val anonSdk = createLocalSdk(
            inline = listOf(
                MGMExperimentConfig(
                    id = anonVector.experimentId,
                    name = "button-color",
                    variants = anonVector.variants
                )
            ),
            prefs = anonPrefs
        )
        assertEquals(anonVector.expectedVariant, anonSdk.getVariant("button-color"))
        anonSdk.shutdown()
    }

    // endregion

    // region Helpers

    private val buttonColor = MGMExperimentConfig(
        id = "7b1e8a90-4c2d-4f6a-9e3b-2a1d5c8f0e71",
        name = "button-color",
        variants = listOf("control", "treatment")
    )

    private fun localConfiguration(
        inline: List<MGMExperimentConfig> = emptyList()
    ): MGMConfiguration =
        MGMConfiguration.Builder("test-api-key")
            .enableDebugLogging(false)
            .trackAppLifecycleEvents(false)
            .experimentMode(MGMExperimentMode.LOCAL)
            .localExperiments(inline)
            .build()

    private fun createLocalSdk(
        inline: List<MGMExperimentConfig> = emptyList(),
        network: ControllableConfigsNetworkClient = ControllableConfigsNetworkClient(
            ExperimentConfigsResult.Success(emptyList())
        ),
        prefs: FakeSharedPreferences = FakeSharedPreferences(),
        storage: EventStorage = InMemoryEventStorage(maxEvents = 100)
    ): MostlyGoodMetrics {
        return MostlyGoodMetrics.createForTesting(localConfiguration(inline), storage, network, prefs)
    }

    private fun awaitReady(sdk: MostlyGoodMetrics, timeoutMs: Long = 2_000): Boolean =
        runBlocking { sdk.ready(timeoutMs) }

    private fun exposureEvents(storage: InMemoryEventStorage): List<MGMEvent> =
        storage.fetchEvents(100).filter { it.name == "\$experiment_exposure" }

    // endregion

    // region Mode selection

    @Test
    fun `experiment mode defaults to SERVER`() {
        val config = MGMConfiguration.Builder("test-api-key").build()
        assertEquals(MGMExperimentMode.SERVER, config.experimentMode)
        assertTrue(config.localExperiments.isEmpty())
    }

    @Test
    fun `SERVER mode never calls the configs endpoint`() {
        val network = ControllableConfigsNetworkClient(
            ExperimentConfigsResult.Success(listOf(buttonColor)),
            variantsResult = ExperimentsResult.Success(mapOf("button-color" to "server-variant"))
        )
        val config = MGMConfiguration.Builder("test-api-key")
            .trackAppLifecycleEvents(false)
            .build()
        val sdk = MostlyGoodMetrics.createForTesting(
            config, InMemoryEventStorage(maxEvents = 100), network, FakeSharedPreferences()
        )
        assertTrue(awaitReady(sdk))

        assertEquals("server-variant", sdk.getVariant("button-color"))
        assertEquals(0, network.configFetchCount)
        assertEquals(1, network.variantsFetchCount)

        sdk.shutdown()
    }

    // endregion

    // region Inline configs (zero network)

    @Test
    fun `inline local experiments perform no network fetch at all`() {
        val network = ControllableConfigsNetworkClient(
            ExperimentConfigsResult.Success(emptyList())
        )
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("user_id", "user_123").apply()
        val sdk = createLocalSdk(inline = listOf(buttonColor), network = network, prefs = prefs)

        assertTrue("ready must resolve immediately with inline configs", awaitReady(sdk))
        assertEquals("control", sdk.getVariant("button-color"))

        Thread.sleep(50)
        assertEquals("Inline configs must skip the configs fetch", 0, network.configFetchCount)
        assertEquals("LOCAL mode must never fetch server assignments", 0, network.variantsFetchCount)

        sdk.shutdown()
    }

    // endregion

    // region Fetched configs

    @Test
    fun `LOCAL mode fetches configs and buckets on device`() {
        val network = ControllableConfigsNetworkClient(
            ExperimentConfigsResult.Success(listOf(buttonColor))
        )
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("user_id", "user_123").apply()
        val sdk = createLocalSdk(network = network, prefs = prefs)

        assertTrue(awaitReady(sdk))

        assertEquals("control", sdk.getVariant("button-color"))
        assertEquals(1, network.configFetchCount)
        assertEquals("LOCAL mode must never fetch server assignments", 0, network.variantsFetchCount)

        sdk.shutdown()
    }

    @Test
    fun `fetched configs are cached and served when the network hangs`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("user_id", "user_123").apply()

        val network1 = ControllableConfigsNetworkClient(
            ExperimentConfigsResult.Success(listOf(buttonColor))
        )
        val sdk1 = createLocalSdk(network = network1, prefs = prefs)
        assertTrue(awaitReady(sdk1))
        assertEquals("control", sdk1.getVariant("button-color"))
        sdk1.shutdown()

        // Simulated restart: the config cache must serve even if the fetch hangs
        val network2 = ControllableConfigsNetworkClient(
            ExperimentConfigsResult.Success(emptyList()),
            hangForever = true
        )
        val sdk2 = createLocalSdk(network = network2, prefs = prefs)
        assertEquals("control", sdk2.getVariant("button-color"))
        sdk2.shutdown()
    }

    @Test
    fun `getVariant returns fallback when configs fail to load`() {
        val network = ControllableConfigsNetworkClient(
            ExperimentConfigsResult.Failure(MGMError.NetworkError(Exception("offline")))
        )
        val sdk = createLocalSdk(network = network)

        assertTrue("ready must resolve on fetch failure, not hang", awaitReady(sdk))
        assertNull(sdk.getVariant("button-color"))
        assertEquals("control", sdk.getVariant("button-color", fallback = "control"))

        sdk.shutdown()
    }

    @Test
    fun `experiment with no variants serves the fallback`() {
        val empty = MGMExperimentConfig(
            id = "7b1e8a90-4c2d-4f6a-9e3b-2a1d5c8f0e71",
            name = "empty-experiment",
            variants = emptyList()
        )
        val sdk = createLocalSdk(inline = listOf(empty))

        assertEquals("fallback", sdk.getVariant("empty-experiment", fallback = "fallback"))

        sdk.shutdown()
    }

    // endregion

    // region Sticky assignments

    @Test
    fun `local assignment is sticky across identify`() {
        // Golden vectors: $anon_abc123def456 buckets to "treatment" while
        // user_123 buckets to "control" — if identify() re-bucketed, the
        // variant would flip.
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("anonymous_id", "\$anon_abc123def456").apply()
        val sdk = createLocalSdk(inline = listOf(buttonColor), prefs = prefs)

        assertEquals("treatment", sdk.getVariant("button-color"))

        sdk.identify("user_123")
        assertEquals(
            "Assignment must not re-bucket after identify()",
            "treatment",
            sdk.getVariant("button-color")
        )

        sdk.shutdown()
    }

    @Test
    fun `local assignment is sticky across a simulated restart`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("anonymous_id", "\$anon_abc123def456").apply()

        val sdk1 = createLocalSdk(inline = listOf(buttonColor), prefs = prefs)
        assertEquals("treatment", sdk1.getVariant("button-color"))
        sdk1.shutdown()

        // Restart identified as user_123 (buckets to "control" from scratch):
        // the persisted assignment must win.
        prefs.edit().putString("user_id", "user_123").apply()
        val sdk2 = createLocalSdk(inline = listOf(buttonColor), prefs = prefs)
        assertEquals(
            "Persisted assignment must be reused after restart",
            "treatment",
            sdk2.getVariant("button-color")
        )
        assertEquals(
            "treatment",
            prefs.getString("local_experiment_assignment_${buttonColor.id}", null)
        )

        sdk2.shutdown()
    }

    // endregion

    // region Exposure events

    @Test
    fun `exposure event uses the raw experiment name and is deduplicated`() {
        val storage = InMemoryEventStorage(maxEvents = 100)
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("user_id", "user_123").apply()
        val sdk = createLocalSdk(inline = listOf(buttonColor), prefs = prefs, storage = storage)

        repeat(3) { assertEquals("control", sdk.getVariant("button-color")) }

        val exposures = exposureEvents(storage)
        assertEquals("Exposure must be deduplicated", 1, exposures.size)
        val properties = exposures.first().properties
        assertNotNull(properties)
        assertEquals("button-color", properties!!["\$experiment_name"]?.toString()?.trim('"'))
        assertEquals("control", properties["\$variant"]?.toString()?.trim('"'))

        // Super property matches SERVER-mode behavior
        assertEquals("control", sdk.getSuperProperties()["\$experiment_button_color"])

        sdk.shutdown()
    }

    // endregion
}

/**
 * Network client test double with controllable experiment config fetch behavior.
 */
class ControllableConfigsNetworkClient(
    private val configsResult: ExperimentConfigsResult,
    private val variantsResult: ExperimentsResult = ExperimentsResult.Success(emptyMap()),
    private val hangForever: Boolean = false
) : NetworkClientInterface {
    @Volatile
    var configFetchCount: Int = 0
        private set

    @Volatile
    var variantsFetchCount: Int = 0
        private set

    override suspend fun sendEvents(payload: MGMEventsPayload): SendResult = SendResult.Success

    override suspend fun fetchExperiments(userId: String, anonymousId: String?): ExperimentsResult {
        variantsFetchCount++
        return variantsResult
    }

    override suspend fun fetchExperimentConfigs(): ExperimentConfigsResult {
        configFetchCount++
        if (hangForever) awaitCancellation()
        return configsResult
    }
}

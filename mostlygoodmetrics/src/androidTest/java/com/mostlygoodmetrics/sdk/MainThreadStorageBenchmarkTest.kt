package com.mostlygoodmetrics.sdk

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch

@RunWith(AndroidJUnit4::class)
class MainThreadStorageBenchmarkTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun measureTrackLatencyWithPreloadedStore() {
        val counts = listOf(100, 1_000, MGMConfiguration.DEFAULT_MAX_STORED_EVENTS)

        Log.i(TAG, "Main-thread track() latency (release build, median of 5)")
        Log.i(TAG, "| Pending events | Median |")
        Log.i(TAG, "|---:|---:|")

        counts.forEach { count ->
            val samples = (0 until 5).map { sample ->
                measureTrackLatency(count, sample)
            }.sorted()
            val median = samples[samples.size / 2]

            Log.i(TAG, "| $count | ${"%.3f".format(median)} ms |")
            check(median < MAX_MAIN_THREAD_LATENCY_MS) {
                "$count pending events: track() blocked the main thread for $median ms"
            }
        }
    }

    private fun measureTrackLatency(eventCount: Int, sample: Int): Double {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storageDir = File(context.filesDir, "mostlygoodmetrics")
        storageDir.deleteRecursively()
        check(storageDir.mkdirs())

        val events = (0 until eventCount).map { index ->
            MGMEvent(
                name = "queued_event",
                clientEventId = "queued-$index",
                timestamp = "2026-01-01T00:00:00.000Z"
            )
        }
        File(storageDir, "events.json").writeText(json.encodeToString(events))

        val storage = FileEventStorage(context, MGMConfiguration.DEFAULT_MAX_STORED_EVENTS)
        check(storage.eventCount() == eventCount)
        val configuration = MGMConfiguration.Builder("benchmark")
            .maxBatchSize(1_000)
            .maxStoredEvents(MGMConfiguration.DEFAULT_MAX_STORED_EVENTS)
            .trackAppLifecycleEvents(false)
            .build()
        val sdk = MostlyGoodMetrics.createForTesting(
            configuration = configuration,
            storage = storage,
            networkClient = NonCompletingNetworkClient()
        )

        var elapsedMs = 0.0
        val measured = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val startedAt = SystemClock.elapsedRealtimeNanos()
            sdk.track("main_thread_event", mapOf("sample" to sample))
            elapsedMs = (SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000.0
            measured.countDown()
        }
        measured.await()
        sdk.shutdown()
        check(storage.awaitPersistence())
        storage.closeForTesting()
        return elapsedMs
    }

    private class NonCompletingNetworkClient : NetworkClientInterface {
        override suspend fun sendEvents(payload: MGMEventsPayload): SendResult {
            awaitCancellation()
        }

        override suspend fun fetchExperiments(
            userId: String,
            anonymousId: String?
        ): ExperimentsResult = ExperimentsResult.Success(emptyMap())

        override suspend fun fetchExperimentConfigs(): ExperimentConfigsResult =
            ExperimentConfigsResult.Success(emptyList())
    }

    private companion object {
        const val TAG = "MGMStorageBenchmark"
        const val MAX_MAIN_THREAD_LATENCY_MS = 5.0
    }
}

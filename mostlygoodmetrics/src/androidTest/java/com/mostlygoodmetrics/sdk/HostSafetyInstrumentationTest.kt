package com.mostlygoodmetrics.sdk

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real Android looper, SharedPreferences, disk storage and production construction. */
@RunWith(AndroidJUnit4::class)
class HostSafetyInstrumentationTest {
    private lateinit var context: Context

    @Before
    fun prepareIsolatedTestApplication() {
        context = ApplicationProvider.getApplicationContext()
        // Never modify a customer application's SDK state.
        check(context.packageName.endsWith(".test")) { "Requires the isolated instrumentation APK" }
        MostlyGoodMetrics.reset()
        context.getSharedPreferences("mostly_good_metrics", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun cleanUp() {
        MostlyGoodMetrics.shared?.clearPendingEvents()
        MostlyGoodMetrics.reset()
    }

    private fun configure(provider: (() -> Map<String, Any?>)? = null, optedOut: Boolean = false) {
        val configuration = MGMConfiguration.Builder("offline-instrumentation")
            // All delivery attempts stay on the device and fail harmlessly.
            .baseUrl("http://127.0.0.1:1")
            .experimentMode(MGMExperimentMode.LOCAL)
            .localExperiments(emptyList())
            .maxBatchSize(1000)
            .maxStoredEvents(100)
            .trackAppLifecycleEvents(true)
            .contextProvider(provider)
            .optedOutByDefault(optedOut)
            .build()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            MostlyGoodMetrics.configure(context, configuration)
        }
    }

    @Test
    fun realStorageConcurrentTrackingAndFailingProviderKeepHostAlive() {
        val providerCalls = AtomicInteger()
        configure(provider = {
            if (providerCalls.incrementAndGet() % 7 == 0) error("Synthetic provider failure")
            mapOf("snapshot" to "stable")
        })
        val sdk = requireNotNull(MostlyGoodMetrics.shared)
        sdk.clearPendingEvents()
        val pool = Executors.newFixedThreadPool(6)
        try {
            val jobs = (0 until 6).map { worker ->
                pool.submit {
                    repeat(100) { index ->
                        val cycle = mutableListOf<Any?>()
                        cycle.add(cycle)
                        sdk.track("host_safety_event", mapOf("worker" to worker, "cycle" to cycle))
                        if (index % 11 == 0) sdk.identify("synthetic-$worker")
                        if (index % 29 == 0) sdk.resetIdentity()
                        if (index % 17 == 0) sdk.setSuperProperty("worker", worker)
                    }
                }
            }
            jobs.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertTrue(providerCalls.get() >= 600)
        assertTrue("The real stored queue must remain bounded", sdk.pendingEventCount <= 100)
        val completed = CountDownLatch(1)
        val onMain = AtomicBoolean()
        sdk.flush {
            onMain.set(Looper.myLooper() == Looper.getMainLooper())
            completed.countDown()
            error("Synthetic completion failure")
        }
        assertTrue("Flush completion must settle", completed.await(10, TimeUnit.SECONDS))
        assertTrue("Completion must use Android main looper", onMain.get())
        // The thrown completion must not kill or wedge the real host main loop.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            sdk.track("host_still_alive")
        }
    }

    @Test
    fun corruptNativeConsentFailsClosedAndExplicitOptInRecovers() {
        context.getSharedPreferences("mostly_good_metrics", Context.MODE_PRIVATE)
            .edit().putString("opted_out", "wrong-native-type").commit()
        configure()
        val sdk = requireNotNull(MostlyGoodMetrics.shared)
        assertTrue("Unknown corrupt consent must suppress tracking", sdk.isOptedOut)
        sdk.track("suppressed_native_event")
        sdk.optIn()
        assertFalse(sdk.isOptedOut)
        sdk.track("native_recovery_event")
        sdk.optOut()
        assertTrue(sdk.isOptedOut)
    }

    @Test
    fun optedOutThrowingCompletionUsesRealMainLooperAndDoesNotEscape() {
        configure(optedOut = true)
        val sdk = requireNotNull(MostlyGoodMetrics.shared)
        val completed = CountDownLatch(1)
        val onMain = AtomicBoolean()
        val worker = Executors.newSingleThreadExecutor()
        try {
            worker.submit {
                sdk.flush {
                    onMain.set(Looper.myLooper() == Looper.getMainLooper())
                    completed.countDown()
                    error("Synthetic opted-out completion failure")
                }
            }.get(10, TimeUnit.SECONDS)
            assertTrue(completed.await(10, TimeUnit.SECONDS))
            assertTrue(onMain.get())
            InstrumentationRegistry.getInstrumentation().runOnMainSync { sdk.optIn() }
            assertFalse(sdk.isOptedOut)
        } finally {
            worker.shutdownNow()
        }
    }
}

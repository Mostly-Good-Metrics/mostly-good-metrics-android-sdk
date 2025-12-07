package com.mostlygoodmetrics.sample

import android.app.Application
import com.mostlygoodmetrics.sdk.MGMConfiguration
import com.mostlygoodmetrics.sdk.MostlyGoodMetrics

class SampleApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Configure MostlyGoodMetrics SDK
        val config = MGMConfiguration.Builder("your-api-key-here")
            .environment("development")
            .enableDebugLogging(true)
            .trackAppLifecycleEvents(true)
            .build()

        MostlyGoodMetrics.configure(this, config)
    }
}

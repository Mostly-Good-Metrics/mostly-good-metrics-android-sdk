package com.mostlygoodmetrics.sdk

import android.util.Log

/**
 * Internal logger for the MostlyGoodMetrics SDK.
 * Logging is controlled by [MGMConfiguration.enableDebugLogging].
 */
internal object MGMLogger {
    private const val TAG = "MostlyGoodMetrics"

    var isEnabled: Boolean = false

    fun debug(message: String) {
        if (isEnabled) {
            Log.d(TAG, message)
        }
    }

    fun info(message: String) {
        if (isEnabled) {
            Log.i(TAG, message)
        }
    }

    fun warn(message: String) {
        if (isEnabled) {
            Log.w(TAG, message)
        }
    }

    fun error(message: String, throwable: Throwable? = null) {
        if (isEnabled) {
            if (throwable != null) {
                Log.e(TAG, message, throwable)
            } else {
                Log.e(TAG, message)
            }
        }
    }
}

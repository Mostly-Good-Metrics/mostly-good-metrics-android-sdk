package com.mostlygoodmetrics.sample

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mostlygoodmetrics.sample.databinding.ActivityMainBinding
import com.mostlygoodmetrics.sdk.MostlyGoodMetrics

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var eventCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupButtons()
        updateStatus()

        // Track screen view
        MostlyGoodMetrics.track("screen_viewed", mapOf("screen_name" to "main"))
    }

    private fun setupButtons() {
        binding.btnTrackEvent.setOnClickListener {
            eventCount++
            MostlyGoodMetrics.track(
                "button_clicked",
                mapOf(
                    "button_name" to "track_event",
                    "click_count" to eventCount
                )
            )
            showToast("Event tracked: button_clicked")
            updateStatus()
        }

        binding.btnTrackPurchase.setOnClickListener {
            MostlyGoodMetrics.track(
                "purchase_completed",
                mapOf(
                    "product_id" to "prod_123",
                    "product_name" to "Premium Subscription",
                    "price" to 9.99,
                    "currency" to "USD"
                )
            )
            showToast("Event tracked: purchase_completed")
            updateStatus()
        }

        binding.btnIdentify.setOnClickListener {
            val userId = binding.etUserId.text.toString().ifBlank { "user_${System.currentTimeMillis()}" }
            MostlyGoodMetrics.identify(userId)
            showToast("User identified: $userId")
            updateStatus()
        }

        binding.btnResetIdentity.setOnClickListener {
            MostlyGoodMetrics.resetIdentity()
            showToast("Identity reset")
            updateStatus()
        }

        binding.btnFlush.setOnClickListener {
            MostlyGoodMetrics.flush { result ->
                runOnUiThread {
                    result.fold(
                        onSuccess = {
                            showToast("Events flushed successfully")
                            updateStatus()
                        },
                        onFailure = { error ->
                            showToast("Flush failed: ${error.message}")
                        }
                    )
                }
            }
        }

        binding.btnNewSession.setOnClickListener {
            MostlyGoodMetrics.startNewSession()
            showToast("New session started")
            updateStatus()
        }

        binding.btnTrackCustom.setOnClickListener {
            val eventName = binding.etEventName.text.toString().ifBlank { "custom_event" }
            MostlyGoodMetrics.track(
                eventName,
                mapOf(
                    "custom_property" to "custom_value",
                    "timestamp" to System.currentTimeMillis()
                )
            )
            showToast("Event tracked: $eventName")
            updateStatus()
        }
    }

    private fun updateStatus() {
        val pendingCount = MostlyGoodMetrics.pendingEventCount
        val userId = MostlyGoodMetrics.userId ?: "Not identified"
        val sessionId = MostlyGoodMetrics.sessionId?.take(8) ?: "N/A"

        binding.tvStatus.text = buildString {
            appendLine("Pending Events: $pendingCount")
            appendLine("User ID: $userId")
            appendLine("Session: $sessionId...")
            appendLine("Events Tracked This Session: $eventCount")
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

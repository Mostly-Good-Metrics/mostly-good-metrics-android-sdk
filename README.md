# MostlyGoodMetrics Android SDK

A lightweight Android SDK for tracking analytics events with [MostlyGoodMetrics](https://mostlygoodmetrics.com).

## Table of Contents

- [Requirements](#requirements)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Configuration Options](#configuration-options)
- [Automatic Events](#automatic-events)
- [Automatic Context](#automatic-context)
- [Event Naming](#event-naming)
- [Properties](#properties)
- [Manual Flush](#manual-flush)
- [Automatic Behavior](#automatic-behavior)
- [Debug Logging](#debug-logging)
- [Thread Safety](#thread-safety)
- [Java Interop](#java-interop)
- [ProGuard / R8](#proguard--r8)
- [License](#license)

## Requirements

- Android SDK 21+ (Android 5.0 Lollipop)
- Kotlin 1.9+

## Installation

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("com.github.Mostly-Good-Metrics:mostly-good-metrics-android-sdk:1.0.0")
}
```

### Gradle (Groovy)

```groovy
dependencies {
    implementation 'com.github.Mostly-Good-Metrics:mostly-good-metrics-android-sdk:1.0.0'
}
```

> **Note:** Add JitPack repository to your `settings.gradle.kts`:
> ```kotlin
> dependencyResolutionManagement {
>     repositories {
>         maven { url = uri("https://jitpack.io") }
>     }
> }
> ```

## Quick Start

### 1. Initialize the SDK

Initialize once at app startup. Choose the approach that matches your app architecture:

**Application class (recommended):**

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MostlyGoodMetrics.configure(this, "mgm_proj_your_api_key")
    }
}
```

**Jetpack Compose (in MainActivity):**

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize before setContent
        MostlyGoodMetrics.configure(applicationContext, "mgm_proj_your_api_key")

        setContent {
            MyApp()
        }
    }
}
```

> **Note:** Using an `Application` class is preferred as it ensures the SDK is initialized before any Activity launches and enables automatic lifecycle tracking across all activities.

### 2. Track Events

```kotlin
// Simple event
MostlyGoodMetrics.track("button_clicked")

// Event with properties
MostlyGoodMetrics.track("purchase_completed", mapOf(
    "product_id" to "SKU123",
    "price" to 29.99,
    "currency" to "USD"
))
```

### 3. Identify Users

```kotlin
// Set user identity
MostlyGoodMetrics.identify("user_123")

// Reset identity (e.g., on logout)
MostlyGoodMetrics.resetIdentity()
```

That's it! Events are automatically batched and sent.

## Configuration Options

For more control, use `MGMConfiguration.Builder`:

```kotlin
val config = MGMConfiguration.Builder("mgm_proj_your_api_key")
    .baseUrl("https://mostlygoodmetrics.com")
    .environment("production")
    .maxBatchSize(100)
    .flushIntervalSeconds(30)
    .maxStoredEvents(10000)
    .enableDebugLogging(BuildConfig.DEBUG)
    .trackAppLifecycleEvents(true)
    .build()

MostlyGoodMetrics.configure(this, config)
```

| Option | Default | Description |
|--------|---------|-------------|
| `apiKey` | Required | Your API key |
| `baseUrl` | `https://mostlygoodmetrics.com` | API endpoint |
| `environment` | `"production"` | Environment name |
| `packageName` | App's package name | Override package identifier |
| `maxBatchSize` | `100` | Events per batch (1-1000) |
| `flushIntervalSeconds` | `30` | Auto-flush interval in seconds |
| `maxStoredEvents` | `10000` | Max cached events |
| `enableDebugLogging` | `false` | Enable logcat output |
| `trackAppLifecycleEvents` | `true` | Auto-track lifecycle events |

## Automatic Events

When `trackAppLifecycleEvents` is enabled (default), the SDK automatically tracks:

| Event | When | Properties |
|-------|------|------------|
| `$app_installed` | First launch after install | `$version` |
| `$app_updated` | First launch after version change | `$version`, `$previous_version` |
| `$app_opened` | App became active (foreground) | - |
| `$app_backgrounded` | App resigned active (background) | - |

## Automatic Context

Every event automatically includes:

| Field | Example | Description |
|-------|---------|-------------|
| `platform` | `"android"` | Platform |
| `os_version` | `"14"` | Android version |
| `app_version` | `"1.0.0 (42)"` | App version with build number |
| `environment` | `"production"` | Environment from configuration |
| `session_id` | `"uuid..."` | Unique session ID (per app launch) |
| `user_id` | `"user_123"` | User ID (if set via `identify()`) |
| `$device_type` | `"phone"` | Device type (phone, tablet, tv, watch) |
| `$device_model` | `"Pixel 8"` | Device model identifier |

> **Note:** The `$` prefix indicates reserved system events and properties. Avoid using `$` prefix for your own custom events.

## Event Naming

Event names must:
- Start with a letter (or `$` for system events)
- Contain only alphanumeric characters and underscores
- Be 255 characters or less

> **Reserved `$` prefix:** The `$` prefix is reserved for system events (e.g., `$app_opened`). Avoid using `$` for your own custom event names.

```kotlin
// Valid
MostlyGoodMetrics.track("button_clicked")
MostlyGoodMetrics.track("PurchaseCompleted")
MostlyGoodMetrics.track("step_1_completed")

// Invalid (will be ignored)
MostlyGoodMetrics.track("123_event")      // starts with number
MostlyGoodMetrics.track("event-name")     // contains hyphen
MostlyGoodMetrics.track("event name")     // contains space
```

## Properties

Events support various property types:

```kotlin
MostlyGoodMetrics.track("checkout", mapOf(
    "string_prop" to "value",
    "int_prop" to 42,
    "double_prop" to 3.14,
    "bool_prop" to true,
    "null_prop" to null,
    "list_prop" to listOf("a", "b", "c"),
    "nested" to mapOf(
        "key" to "value"
    )
))
```

**Limits:**
- String values: truncated to 1000 characters
- Nesting depth: max 3 levels
- Total properties size: max 10KB

## Manual Flush

Events are automatically flushed periodically and when the app backgrounds. You can also trigger a manual flush:

```kotlin
MostlyGoodMetrics.flush { result ->
    result.onSuccess {
        Log.d("Analytics", "Events flushed successfully")
    }
    result.onFailure { error ->
        Log.e("Analytics", "Flush failed", error)
    }
}
```

## Automatic Behavior

The SDK automatically handles the following without any additional configuration:

- **Event persistence** - Events are saved to disk and survive app restarts
- **Batch processing** - Events are grouped for efficient network usage
- **Periodic flush** - Events are sent every 30 seconds (configurable via `flushIntervalSeconds`)
- **Background flush** - Events are sent when the app goes to background
- **Retry on failure** - Failed requests are retried; events are preserved until successfully sent
- **Payload compression** - Large batches (>1KB) are automatically gzip compressed
- **Rate limiting** - Exponential backoff when rate limited by the server (respects `Retry-After` headers)
- **User ID persistence** - User identity set via `identify()` persists across app launches
- **Session management** - New session ID generated on each app launch
- **Deduplication** - Events include unique IDs (`client_event_id`) to prevent duplicate processing

## Debug Logging

Enable debug logging to see SDK activity in logcat:

```kotlin
val config = MGMConfiguration.Builder("mgm_proj_your_api_key")
    .enableDebugLogging(true)
    .build()
MostlyGoodMetrics.configure(this, config)
```

Output example:
```
D/MostlyGoodMetrics: Initialized with 3 cached events
D/MostlyGoodMetrics: Tracked event: button_clicked
D/MostlyGoodMetrics: Flushing 4 events
D/MostlyGoodMetrics: Successfully flushed 4 events
```

## Thread Safety

The SDK is fully thread-safe. You can call `track()` from any thread.

## Java Interop

The SDK works with Java:

```java
// Initialize
MostlyGoodMetrics.configure(context, "mgm_proj_your_api_key");

// Track
Map<String, Object> props = new HashMap<>();
props.put("button_name", "submit");
MostlyGoodMetrics.track("button_clicked", props);

// Identify
MostlyGoodMetrics.identify("user_123");
```

## ProGuard / R8

The SDK includes consumer ProGuard rules. No additional configuration needed.

## License

MIT

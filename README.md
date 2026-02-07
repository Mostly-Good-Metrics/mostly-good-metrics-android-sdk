# MostlyGoodMetrics Android SDK

A lightweight Android SDK for tracking analytics events with [MostlyGoodMetrics](https://mostlygoodmetrics.com).

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

Initialize once in your `Application` class:

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MostlyGoodMetrics.configure(this, "mgm_proj_your_api_key")
    }
}
```

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
    .baseUrl("https://ingest.mostlygoodmetrics.com")
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
| `baseUrl` | `https://ingest.mostlygoodmetrics.com` | API endpoint |
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
| `$app_installed` | First launch after install | `version` |
| `$app_updated` | First launch after version change | `version`, `previous_version` |
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

## Super Properties

Set properties that will be included with every event:

```kotlin
// Set a single super property
MostlyGoodMetrics.setSuperProperty("plan", "premium")

// Set multiple super properties
MostlyGoodMetrics.setSuperProperties(mapOf(
    "plan" to "premium",
    "tier" to "gold"
))

// Get current super properties
val props = MostlyGoodMetrics.getSuperProperties()

// Remove a super property
MostlyGoodMetrics.removeSuperProperty("plan")

// Clear all super properties
MostlyGoodMetrics.clearSuperProperties()
```

Super properties are persisted across app launches and merged with event properties (event properties take precedence).

## User Identification

```kotlin
// Basic identification
MostlyGoodMetrics.identify("user_123")

// Identification with profile data
MostlyGoodMetrics.identify("user_123", UserProfile(
    email = "user@example.com",
    name = "John Doe"
))

// Reset identity (e.g., on logout)
MostlyGoodMetrics.resetIdentity()
```

Profile data is sent via a debounced `$identify` event (only sent if data changed or after 24 hours).

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

The SDK automatically:

- **Persists events** to disk, surviving app restarts
- **Batches events** for efficient network usage
- **Flushes on interval** (default: every 30 seconds)
- **Flushes on background** when the app goes to background
- **Retries on failure** for network errors (events are preserved)
- **Compresses payloads** using gzip for requests > 1KB
- **Handles rate limiting** by respecting `Retry-After` headers
- **Persists user ID** across app launches
- **Generates session IDs** per app launch

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

// Track events
Map<String, Object> props = new HashMap<>();
props.put("button_name", "submit");
MostlyGoodMetrics.trackEvent("button_clicked", props);

// Identify users
MostlyGoodMetrics.identifyUser("user_123", null);

// Or with profile data
UserProfile profile = new UserProfile("user@example.com", "John Doe");
MostlyGoodMetrics.identifyUser("user_123", profile);

// Reset identity
MostlyGoodMetrics.resetUserIdentity();

// Flush events
MostlyGoodMetrics.flushEvents(null);
```

## ProGuard / R8

The SDK includes consumer ProGuard rules. No additional configuration needed.

## License

MIT

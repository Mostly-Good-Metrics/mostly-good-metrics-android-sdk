# MostlyGoodMetrics Android SDK

Analytics SDK for Android applications.

## Requirements

- Android SDK 21+ (Android 5.0 Lollipop)
- Kotlin 1.9+

## Installation

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("com.mostlygoodmetrics:sdk:1.0.0")
}
```

### Gradle (Groovy)

```groovy
dependencies {
    implementation 'com.mostlygoodmetrics:sdk:1.0.0'
}
```

## Quick Start

### Initialize the SDK

Initialize in your `Application` class:

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Simple initialization
        MostlyGoodMetrics.configure(this, "your-api-key")

        // Or with custom configuration
        val config = MGMConfiguration.Builder("your-api-key")
            .environment("production")
            .enableDebugLogging(BuildConfig.DEBUG)
            .build()
        MostlyGoodMetrics.configure(this, config)
    }
}
```

### Track Events

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

### Identify Users

```kotlin
// Set user identity
MostlyGoodMetrics.identify("user-123")

// Reset identity (e.g., on logout)
MostlyGoodMetrics.resetIdentity()
```

### Manual Flush

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

## Configuration Options

| Option | Default | Description |
|--------|---------|-------------|
| `apiKey` | Required | Your API key |
| `baseUrl` | `https://mostlygoodmetrics.com` | API endpoint |
| `environment` | `"production"` | Environment name |
| `packageName` | App's package name | Override bundle identifier |
| `maxBatchSize` | `100` | Events per batch (1-1000) |
| `flushIntervalSeconds` | `30` | Auto-flush interval |
| `maxStoredEvents` | `10000` | Max cached events |
| `enableDebugLogging` | `false` | Enable logcat output |
| `trackAppLifecycleEvents` | `true` | Auto-track lifecycle |

## Automatic Events

When `trackAppLifecycleEvents` is enabled (default), the SDK automatically tracks:

- `$app_installed` - First launch after install
- `$app_updated` - First launch after update
- `$app_opened` - App came to foreground
- `$app_backgrounded` - App went to background

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

## Thread Safety

The SDK is fully thread-safe. You can call `track()` from any thread.

## Java Interop

The SDK is designed for Kotlin but works with Java:

```java
// Initialize
MostlyGoodMetrics.configure(context, "your-api-key");

// Track
Map<String, Object> props = new HashMap<>();
props.put("button_name", "submit");
MostlyGoodMetrics.track("button_clicked", props);

// Identify
MostlyGoodMetrics.identify("user-123");
```

## ProGuard / R8

The SDK includes consumer ProGuard rules. No additional configuration needed.

## License

MIT License

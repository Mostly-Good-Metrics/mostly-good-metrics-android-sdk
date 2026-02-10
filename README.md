# MostlyGoodMetrics Android SDK

A lightweight Android SDK for tracking analytics events with [MostlyGoodMetrics](https://mostlygoodmetrics.com).

## Table of Contents

- [Requirements](#requirements)
- [Installation](#installation)
  - [Gradle (Kotlin DSL)](#gradle-kotlin-dsl)
  - [Gradle (Groovy)](#gradle-groovy)
  - [Alternative Package Managers](#alternative-package-managers)
- [Quick Start](#quick-start)
- [Configuration Options](#configuration-options)
- [User Identification](#user-identification)
- [Tracking Events](#tracking-events)
- [Automatic Events](#automatic-events)
- [Automatic Context](#automatic-context)
- [Event Naming](#event-naming)
- [Properties](#properties)
- [Debug Logging](#debug-logging)
- [Automatic Behavior](#automatic-behavior)
- [Manual Flush](#manual-flush)
- [Java Interop](#java-interop)
- [ProGuard / R8](#proguard--r8)
- [License](#license)

## Requirements

- Android SDK 21+ (Android 5.0 Lollipop)
- Kotlin 1.9+

## Installation

### Gradle (Kotlin DSL)

Add the dependency to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.Mostly-Good-Metrics:mostly-good-metrics-android-sdk:1.0.0")
}
```

Add JitPack repository to your `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
    }
}
```

### Gradle (Groovy)

Add the dependency to your `build.gradle`:

```groovy
dependencies {
    implementation 'com.github.Mostly-Good-Metrics:mostly-good-metrics-android-sdk:1.0.0'
}
```

Add JitPack repository to your `settings.gradle`:

```groovy
dependencyResolutionManagement {
    repositories {
        maven { url 'https://jitpack.io' }
    }
}
```

### Alternative Package Managers

#### Maven

Add the JitPack repository to your `pom.xml`:

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>
```

Add the dependency:

```xml
<dependency>
    <groupId>com.github.Mostly-Good-Metrics</groupId>
    <artifactId>mostly-good-metrics-android-sdk</artifactId>
    <version>1.0.0</version>
</dependency>
```

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

### 2. Identify Users

```kotlin
// Set user identity
MostlyGoodMetrics.identify("user_123")

// Reset identity (e.g., on logout)
MostlyGoodMetrics.resetIdentity()
```

### 3. Track Events

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

## User Identification

Associate events with specific users by calling `identify()`:

```kotlin
// Set user identity
MostlyGoodMetrics.identify("user_123")

// With profile data (email, name)
MostlyGoodMetrics.identify(
    "user_123",
    UserProfile(email = "user@example.com", name = "John Doe")
)
```

**Identity Persistence:**
- User IDs persist across app launches
- Profile data (`email`, `name`) is sent via a special `$identify` event
- Profile updates are debounced (only sent if changed or >24h since last send)

**Anonymous Users:**
- Before calling `identify()`, users are tracked with an auto-generated anonymous ID
- Format: `$anon_xxxxxxxxxxxx` (12 random alphanumeric characters)
- Anonymous IDs persist across app launches

**Reset Identity:**

Clear the user identity on logout:

```kotlin
MostlyGoodMetrics.resetIdentity()
```

This clears the persisted user ID and resets profile state. Events will use the anonymous ID until `identify()` is called again.

## Tracking Events

Track custom events with optional properties:

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

Events are automatically enriched with context (platform, OS version, device info, etc.) and batched for efficient delivery.

## Automatic Events

When `trackAppLifecycleEvents` is enabled (default), the SDK automatically tracks:

| Event | When | Properties |
|-------|------|------------|
| `$app_installed` | First launch after install | `$version` |
| `$app_updated` | First launch after version change | `$version`, `$previous_version` |
| `$app_opened` | App became active (foreground) | - |
| `$app_backgrounded` | App resigned active (background) | - |

## Automatic Context

Every event automatically includes the following context properties:

| Field | Example | Description |
|-------|---------|-------------|
| `platform` | `"android"` | Operating system platform |
| `os_version` | `"14"` | Android OS version (from `Build.VERSION.RELEASE`) |
| `app_version` | `"1.0.0"` | App version name from `PackageInfo.versionName` |
| `app_build_number` | `"42"` | App version code / build number from `PackageInfo.versionCode` |
| `environment` | `"production"` | Environment name from configuration |
| `session_id` | `"uuid..."` | Unique session ID (generated per app launch) |
| `user_id` | `"user_123"` or `"$anon_xxx"` | User ID (set via `identify()`) or anonymous ID |
| `device_manufacturer` | `"Google"` | Device manufacturer (from `Build.MANUFACTURER`) |
| `locale` | `"en_US"` | User's current locale from device settings |
| `timezone` | `"America/New_York"` | Device timezone identifier from system settings |
| `$device_type` | `"phone"` or `"tablet"` | Device form factor (based on screen size and configuration) |
| `$device_model` | `"Pixel 8"` | Device model name (from `Build.MODEL`) |
| `$sdk` | `"android"` | SDK identifier ("android" or wrapper name if applicable) |

> **Note:** The `$` prefix indicates reserved system properties and events. Avoid using `$` prefix for your own custom properties.

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

## Automatic Behavior

The SDK automatically handles the following without any additional configuration:

**Event Management:**
- **Event persistence** - Events are saved to disk and survive app restarts
- **Batch processing** - Events are grouped into batches (default: 100 events per batch)
- **Periodic flush** - Events are sent every 30 seconds (configurable via `flushIntervalSeconds`)
- **Automatic flush on batch size** - Events flush immediately when batch size is reached
- **Retry on failure** - Failed requests are retried; events are preserved until successfully sent
- **Payload compression** - Large batches (>1KB) are automatically gzip compressed
- **Rate limiting** - Exponential backoff when rate limited by the server (respects `Retry-After` headers)
- **Deduplication** - Events include unique IDs (`client_event_id`) to prevent duplicate processing

**Lifecycle Tracking:**
- **App lifecycle events** - Automatically tracks `$app_opened`, `$app_backgrounded`, `$app_installed`, and `$app_updated`
- **Background flush** - Events are automatically flushed when the app goes to background (via `ProcessLifecycleOwner`)
- **Session management** - New session ID generated on each app launch and persisted for the entire session
- **Install/update detection** - Tracks first install and version changes by comparing stored version with current

**User & Identity:**
- **User ID persistence** - User identity set via `identify()` persists across app launches in SharedPreferences
- **Anonymous ID** - Auto-generated anonymous ID (`$anon_xxxxxxxxxxxx`) for users before identification
- **Profile debouncing** - `$identify` events with profile data (email, name) are only sent if changed or >24h since last send

**Context Collection:**
- **Automatic context** - Every event includes platform, OS version, device info, locale, timezone, etc.
- **Super properties** - Set persistent properties that are included with every event
- **Dynamic context** - Context like app version and build number are collected at event time

**Thread Safety:**

The SDK is fully thread-safe. All methods can be called from any thread:
- Event tracking uses internal queues for safe concurrent access
- Flush operations are serialized to prevent race conditions
- Storage operations are atomic

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

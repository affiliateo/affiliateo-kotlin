# Affiliateo Kotlin SDK

Mobile affiliate attribution and session tracking for Android apps (Kotlin / Jetpack Compose).

## Installation

Add the dependency via JitPack. In your root `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
    }
}
```

In your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.NicoGrajales:affiliateo-kotlin:1.0.0")
}
```

## Usage

Initialize in your `Application` class or main `Activity`:

```kotlin
import com.affiliateo.sdk.Affiliateo

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Affiliateo.configure(
            context = this,
            campaignId = "YOUR_CAMPAIGN_ID"
        )
    }
}
```

Access the attribution state anywhere:

```kotlin
val state = Affiliateo.state
if (state.isMatched) {
    println("Referred by: ${state.refCode}")
}
```

## What it does

- **Identifies the device** using Android's built-in ANDROID_ID (no permissions needed)
- **Tracks sessions** automatically (app foreground / background)
- **Matches affiliate referrals** via fingerprint matching
- **Sets RevenueCat attributes** automatically if RevenueCat is installed

## Requirements

- Android API 24+ (Android 7.0)
- Kotlin 1.9+

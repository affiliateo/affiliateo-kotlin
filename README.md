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
    implementation("com.github.affiliateo:affiliateo-kotlin:4.3.0")
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

## Track screens (manual)

Screens are tracked when you call `Affiliateo.page(name)` per screen. This matches the Mixpanel / Amplitude / Datafast model. predictable, no ghost events polluting funnels.

```kotlin
@Composable
fun HomeScreen() {
    LaunchedEffect(Unit) {
        Affiliateo.page("HomeScreen")
    }
    YourScreenUI()
}
```

## Track custom events

For buttons or other moments that matter (signup, trial start, etc.):

```kotlin
Button(onClick = {
    Affiliateo.track("signup_completed")
    onNext()
}) {
    Text("Continue")
}
```

## What it does

- **Identifies the device** using Android's built-in ANDROID_ID (no permissions needed)
- **Tracks sessions** automatically (app foreground)
- **Matches affiliate referrals** via fingerprint matching
- **Sets RevenueCat attributes** automatically if RevenueCat is installed
- **IAP attribution** via Play Billing `obfuscatedAccountId`

## Requirements

- Android API 24+ (Android 7.0)
- Kotlin 1.9+

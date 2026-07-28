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
    implementation("com.github.affiliateo:affiliateo-kotlin:4.5.0")
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
            appId = "YOUR_APP_ID"
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

Screens are tracked when you call `Affiliateo.page(name)` per screen. This matches the Mixpanel / Amplitude model. predictable, no ghost events polluting funnels.

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

## Giving affiliates free access (optional)

App owners can switch complimentary access on for an individual affiliate from
their Affiliateo dashboard, which grants a promotional entitlement in their own
RevenueCat project. To make that possible, tell Affiliateo which RevenueCat
customer this device is:

```kotlin
import com.revenuecat.purchases.Purchases

// after Purchases.configure(...)
Affiliateo.setRevenueCatUser(Purchases.sharedInstance.appUserID)
```

Call it once, after RevenueCat has configured. Calling it on every launch is
fine and is a no-op after the first time.

Without this, Affiliateo can only match an affiliate to a RevenueCat customer
by email, which requires your app to be setting RevenueCat's `$email` attribute
*and* the affiliate to have used the same address they used on Affiliateo. When
that misses, the owner sees a disabled switch reading "hasn't opened your app
yet".

Notes:

- Separate from `identify()` on purpose. Sign-in and RevenueCat setup happen at
  different moments, and your app may do one without the other.
- Write-once per device. Sending a different ID for a device that is already
  bound is rejected, so a tampered client cannot repoint a device at another
  customer.
- No email or other PII is sent, same as `identify()`.

## Requirements

- Android API 24+ (Android 7.0)
- Kotlin 1.9+

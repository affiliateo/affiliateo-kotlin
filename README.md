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
    implementation("com.github.affiliateo:affiliateo-kotlin:4.10.0")
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

## Tag every purchase

`Affiliateo.state.obfuscatedAccountId` is the purchase tag for this phone (its
Affiliateo visitor id). Every user gets one, whether or not they came through
an affiliate, so always pass it to the purchase call. Google stamps it onto the
sale and on every renewal and refund after it, and Affiliateo ties each one to
this phone, and to the affiliate when there is one.

```kotlin
// Play Billing
val params = BillingFlowParams.newBuilder()
    .setProductDetailsParamsList(productDetailsParamsList)
    .apply { Affiliateo.state.obfuscatedAccountId?.let { setObfuscatedAccountId(it) } }
    .build()
billingClient.launchBillingFlow(activity, params)
```

It is null only until the SDK's first identify succeeds (and after `reset()`).

## Report purchases to your ad networks

When you run app campaigns on Meta, TikTok or Snapchat through Affiliateo,
Affiliateo tells those networks each time somebody FIRST pays (a
subscription's first charge, or a one-time purchase), so the campaigns learn
who pays rather than who installs. Set it up in Affiliateo: your ad network's
settings, **Send app sales**, then paste the network's id for your app.

There are two ways a payment reaches Affiliateo, and you can use either or
both. Affiliateo sends each payment once.

1. **Your payment provider (nothing to add here).** If RevenueCat, Google
   Play or Adapty is connected to your app in Affiliateo, first payments are
   reported on their own.
2. **One line on your paywall.** Right after a purchase succeeds:

```kotlin
// RevenueCat
Purchases.sharedInstance.purchaseWith(
    PurchaseParams.Builder(activity, pkg).build(),
    onError = { _, _ -> },
    onSuccess = { transaction, _ ->
        val orderId = transaction?.orderId
        if (orderId != null) {
            Affiliateo.purchaseMicros(
                transactionId = orderId,
                productId = pkg.product.id,
                priceAmountMicros = pkg.product.price.amountMicros,
                currency = pkg.product.price.currencyCode,
            )
        }
    },
)
```

`transactionId` is Google Play's order id ("GPA.…"). With Play Billing
directly, pass `purchase.orderId` and the price from the product details.
The paywall line is quicker and carries the phone's network address, which
the networks match on. It records no sale and moves no money: revenue and
commissions still come only from your payment provider.

**The advertising ID.** The networks match far better with the Google
advertising ID, and since Android 13 your app has to declare it:

```xml
<uses-permission android:name="com.google.android.gms.permission.AD_ID" />
```

The SDK does not declare it for you, because it is a statement your app makes
in its Play Store data safety answers. The id is only sent while the person
has ad personalisation on. If your app ships the Meta SDK, this SDK finds it
and sends its anonymous id with each payment, so Meta ties the payment to the
install its SDK saw; turn the Meta SDK's in-app purchase logging off (below).

**Installs come from the network's own SDK.** Affiliateo sends payments, not
installs. To run app campaigns on Meta, TikTok or Snapchat, add that network's
SDK (or a measurement partner like AppsFlyer) for installs, and keep **Send app
sales** on for payments.

**Send each network your sales one way only.** Keep that SDK's installs but turn
its purchase reporting off, or leave **Send app sales** off for that network.
Otherwise the network counts every sale twice: it only matches two reports of
one sale when both carry the same id, and its own SDK doesn't send the store's
transaction id. Affiliateo's own two ways are matched on it and go out once.

- **Meta:** in your app on Meta for Developers, under Settings > Basic, set the
  switch that logs in-app purchases automatically to No on the Android card.
  Leave `com.facebook.sdk.AutoLogAppEventsEnabled` on: turning it off stops
  installs too.
- **TikTok:** its Android SDK reports purchases only after `enableAutoIapTrack()`,
  `trackGooglePlayPurchase()` or a Purchase event your app tracks itself, so do
  none of those.
- **Snapchat:** don't send purchases from its SDK.
- **A measurement partner** (AppsFlyer, Adjust, Branch, Singular, Kochava) that
  forwards purchases to the network counts too: stop forwarding them, or leave
  **Send app sales** off for that network.

## What it does

- **Identifies the device** using Android's built-in ANDROID_ID (no permissions needed)
- **Tracks sessions** automatically (app foreground)
- **Matches affiliate referrals** via fingerprint matching
- **Sets RevenueCat attributes** automatically if RevenueCat is installed
- **IAP attribution** via Play Billing `obfuscatedAccountId`, for every user (see Tag every purchase)

## Giving affiliates free access

App owners can switch complimentary access on for an individual affiliate from
their Affiliateo dashboard, which grants a promotional entitlement in their own
RevenueCat project.

**Nothing to add to your code.** As of 4.7.0 the SDK reads your RevenueCat App
User ID itself, after its first identify and on every foreground after that. It
finds RevenueCat by reflection, so there is no dependency to add and nothing at
all happens in apps that don't use RevenueCat.

As of 4.8.2 that also holds in a shrunk release build. Android's shrinker (R8)
is allowed to rename RevenueCat's classes, and the SDK's lookup by name then
found nothing and said nothing: release builds stopped linking purchases to
affiliates, while debug builds (not shrunk) kept working. The SDK now ships the
rule that keeps those names, so there is still nothing to add. If you are on
4.8.1 or older and shrink your release build, update to 4.8.2.

Before 4.7.0 this needed a call you had to write yourself. It still exists if
you want to control the timing:

```kotlin
import com.revenuecat.purchases.Purchases

// after Purchases.configure(...), optional: the SDK already does this
Affiliateo.setRevenueCatUser(Purchases.sharedInstance.appUserID)
```

Sending the same id repeatedly is a no-op. RevenueCat issues an anonymous
placeholder until your app calls `Purchases.logIn()`; the SDK re-reads on
foreground and the server accepts exactly one upgrade from that placeholder to
the real id.

An affiliate still has to have opened your app through their own referral link
at least once, because that link is what tells us which device is theirs. Until
then the owner sees a disabled switch reading "hasn't opened your app yet".

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

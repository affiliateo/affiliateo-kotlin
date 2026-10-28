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
    implementation("com.github.affiliateo:affiliateo-kotlin:4.15.0")
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

## Sign-in and sign-out

Say who is signed in after sign-in (and on launch while someone is), and call
`logout()` on sign-out:

```kotlin
// after sign-in (email is optional, see Giving affiliates free access)
Affiliateo.identify(user.id, email = user.email)

// on sign-out
Affiliateo.logout()
```

The same person then counts as one customer across their devices. After
`logout()` the phone counts as a new anonymous customer, so whoever signs in
next (or nobody) isn't counted as the person who just left; everything already
recorded stays with them. Tracking carries on as before: same device, same
visitor id, same `obfuscatedAccountId`, no restart needed. If a different user
signs in without a sign-out in between, the phone switches to them on its own.

The SDK remembers the signed-in id and sends it again once per launch, and an
`identify()` made before `configure()` is kept and sent once it has run. Ids
that mean "nobody" (`"null"`, `"guest"`, `"0"`, an empty string, RevenueCat's
`$RCAnonymousID:...`, anything over 128 characters) are skipped, and nothing
is sent while the person has opted out. **No email address is ever sent:**
when you pass `email`, the SDK sends only its SHA-256 (see below), and nothing
else about the person.

### What changed in 4.12.0

- `identify(userId, email)` takes the signed-in person's email, for free
  access. Only its SHA-256 leaves the phone.
- New `Affiliateo.freeAccess()`, so apps that sell without RevenueCat can give
  affiliates free access (see Giving affiliates free access).

### What changed in 4.11.0

- New `logout()`, for sign-out (see above).
- `reset()` is now the same as `logout()`. It used to try to give the phone a
  new device id, which Android doesn't allow, and left `obfuscatedAccountId`
  null until the app restarted.
- `identify()` stores the id, re-sends it once per launch, keeps a call made
  before `configure()` (it used to be dropped), retries a sign-in that raced
  ahead of the first launch call, and skips placeholder ids.
- A different user signing in on the same phone now switches it to them. It
  used to be refused, so a shared phone stayed its first user forever.

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

It is null only until the SDK's first identify succeeds. Signing out keeps it.

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

## Live Chat (4.14.0)

Let the people using your app chat with you in real time. You answer from the
**Live Chat** inbox in your Affiliateo dashboard: add Live Chat from the App
Store there, then switch it on for this app under its settings (the gear).

```kotlin
// The chat bubble on every screen (after configure)
Affiliateo.showChatBubble()

// Or open the chat from your own button
button.setOnClickListener { Affiliateo.openChat(this) }

// Show your own button only while the chat is on (suspend)
val available = Affiliateo.chatAvailable()

// Take the bubble away again
Affiliateo.hideChatBubble()

// Keep it above your own tab bar or floating button (4.15.0)
Affiliateo.setChatBubblePadding(bottom = 72)

// Your own look, over the dashboard's (4.15.0); leave out what the dashboard should decide
Affiliateo.setChatBubbleLook(color = "#7C3AED", iconColor = "#FFFFFF", sendColor = "#007AFF", style = ChatBubbleStyle.GLASS, corner = ChatBubbleCorner.BOTTOM_LEFT, sizeDp = 56)

// The message box text in your own words and language (4.15.0): pass your own translation
Affiliateo.setChatPlaceholder(getString(R.string.chat_placeholder))
```

- Nothing shows until your code calls `showChatBubble()` or `openChat()`, so
  the store reviewer sees every feature your app has. Nothing shows either
  while Live Chat is off for this app in your dashboard.
- The bubble sits on top of each screen's content, Compose or Views (a
  dialog you open covers it). People can drag it and it stays where they
  leave it. It shows a red number for replies they have not read (asked for
  every 20 seconds while the bubble is on screen), and a green dot while
  someone on your team is online. On launch it shows at once from the last
  answer the phone kept, then checks with the server (4.15.0).
- In the Live Chat settings of your dashboard you choose, per app, the corner
  it starts in, its size, its style (the blue circle, or "Glass": a
  see-through blue glass circle, 4.15.0), a greeting that shows as one unread message (a red
  1) until the person first opens the chat, and the screens it shows on.
  Screen rules match the names you send with `Affiliateo.page()` or
  `AffiliateoScreen()` (`Settings`, `Paywall*`), so send them if you use rules.
- The chat screen is Affiliateo's hosted chat page in the person's language,
  in a sheet that slides up over the dimmed app (4.15.0; it was full screen),
  declared in the SDK's own manifest: nothing to add to yours. Back, the
  page's close button or a tap on the app around it closes it, and links you
  send open in the browser.
- The person needs no account. Their first message opens a thread, and the
  key to it is kept on the phone. Your inbox labels the thread with the
  device, the Affiliateo visitor and, after `identify()`, your user id; none
  of them while the person has opted out.
- Google Play Data safety: chat messages are **Messages, Other in-app
  messages**.

## What it does

- **Identifies the device** using Android's built-in ANDROID_ID (no permissions needed)
- **Tracks sessions** automatically (app foreground)
- **Matches affiliate referrals** via fingerprint matching
- **Sets RevenueCat attributes** automatically if RevenueCat is installed
- **IAP attribution** via Play Billing `obfuscatedAccountId`, for every user (see Tag every purchase)
- **Store prices** (4.13.0): once a day, in the background, a phone whose
  Play Store country is the US reports Google Play's own price for the app's
  products (with the app's own Play Billing 8, which RevenueCat brings), so
  your product cards on Affiliateo show the exact price with no Google Play
  key. Only the product ids, prices, currency and store country are sent;
  nothing about the person. Nothing to call.

## Giving affiliates free access

App owners can switch free access on for an individual affiliate from the
Affiliates table in their Affiliateo dashboard, choosing a plan and, if they
like, an end date.

### Apps on Google Play Billing, StoreKit, Adapty or Stripe (4.12.0)

Apple and Google have no way for anyone but the person to start a
subscription, so your app asks Affiliateo instead and unlocks itself:

```kotlin
// after sign-in: pass the email the person signed in with
Affiliateo.identify(user.id, email = user.email)

// wherever you decide what is unlocked (launch, paywall), in a coroutine
lifecycleScope.launch {
    val access = Affiliateo.freeAccess()
    if (access.active) unlockPaidFeatures(access.productId) // null means full access
}
```

- `active`: unlock when true.
- `productId`: the store product id of the plan the owner chose. `null` means
  full access.
- `expiresAt`: when the free access ends, as the ISO 8601 text Affiliateo
  sends (`"2026-11-17T12:00:00.000Z"`). `null` means no end date.

The affiliate is recognised by email: the address the owner typed when
switching it on, or else the one the affiliate uses on Affiliateo. **The
address never leaves the phone.** The SDK sends only its SHA-256 (trimmed and
lowercased first), and Affiliateo compares it with the same hash of the
owner's address.

`freeAccess()` never throws. It waits (up to 10 seconds) for the sign-in to
reach Affiliateo first, so calling it right after `identify` works. Each
answer is saved on the phone: when Affiliateo can't be reached, the saved one
is used while its end date is still ahead and it is at most 7 days old,
otherwise the answer is no access. `logout()`, or a different user signing in,
forgets it. Called before `configure()`, it answers no access.

### Apps on RevenueCat

Switching free access on grants a promotional entitlement in the owner's own
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
- No email or other PII is sent.

## Requirements

- Android API 24+ (Android 7.0)
- Kotlin 1.9+

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    // Without this, `gradle publishToMavenLocal` runs and produces NOTHING.
    // That is the literal "No build artifacts found" JitPack reported on the
    // first build it ever ran of this repo: an Android library module emits an
    // AAR, but nothing turns that AAR into a Maven artifact unless
    // maven-publish is applied and given a component to publish (see the
    // publishing blocks at the bottom of this file).
    id("maven-publish")
}

// 4.15.4: the chat sheet's links, frames and thread key (review S18). A link
//         leaves the sheet only when the person taps it (never for a script
//         or a redirect), and only to the browser, mail or phone app. Only
//         this app's own chat page (exactly /embed/chat/{appId} on
//         Affiliateo's origin) loads in the sheet and talks to the SDK, and
//         frames the page asks for load only from that origin. On a WebView
//         too old for WebMessageListener, the bridge answers only the chat
//         page's main frame (a random key only that frame gets). The thread
//         key moved from SharedPreferences (which Auto Backup copies) to a
//         file in the no-backup folder; a key an older version kept moves
//         over once and is deleted.
// 4.15.2: the chat bubble is for the app's pages. It was always a view in the
//         screen's own window (dialogs, bottom sheets and other screens cover
//         it); Affiliateo.chatBubbleLayer(context) now gives an app that
//         draws its own sheets or covers in its Compose content a page layer
//         under them for the bubble. Never on another library's screen over
//         the app's (Play Billing's purchase sheet). TalkBack says the bubble
//         in the phone's language (16 languages) with the unread count; the
//         red number keeps its size whatever the font setting. Only the chat
//         page's own main frame can talk to the SDK (androidx.webkit's
//         WebMessageListener, new dependency androidx.webkit 1.11.0).
// 4.15.1: Live Chat privacy on shared phones. identify() with a different
//         user (no logout() in between) forgets the last person's chat first,
//         as logout() does; signing in from anonymous keeps the person's own
//         thread, and a sign-out or switch made before configure() is caught
//         up there. The chat screen saves a thread key the page hands over
//         only while nobody signed out since it opened (its 250 ms slide-down
//         included). The chat page keeps nothing in the WebView's storage
//         (DOM storage is off), so no copy of the key or of the last messages
//         outlives logout(); a page that has to start over (memory pressure,
//         or Android ending the app) gets the key the app saved instead.
// 4.15.0: Live Chat opens as a sheet over the dimmed app, the owner's "Glass"
//         bubble style, setChatBubblePadding(bottom), setChatBubbleLook
//         (color, icon color, send color, style, corner, size),
//         setChatPlaceholder(text), the bubble at once on launch from the last
//         answer kept, and the green dot when the affiliate who brought this
//         person is online.
// 4.14.2: Live Chat's bubble draws its speech bubble at 60% of the circle
//         (it was half), as the website's bubble now does. logout() and
//         reset() forget the person's chat (its token, red number and open
//         screen), so the next person on the phone starts a new thread.
// 4.14.1: Live Chat's "auto" bubble size: small on a phone, medium on a
//         tablet (smallest width 600 dp and up), the size a new app starts
//         with. The red number and the green dot now scale with the bubble,
//         so a small bubble keeps the same look.
// 4.14.0: Live Chat. Affiliateo.showChatBubble() draws a draggable chat
//         bubble over the app's screens and Affiliateo.openChat(activity)
//         opens the chat from the app's own button; the business answers
//         from the Live Chat inbox in its dashboard. The chat screen is
//         Affiliateo's hosted chat page in a WebView (ChatActivity, declared
//         in this library's manifest). Nothing shows until the app calls one
//         of them, nor while the business has the chat off for the app. The
//         bubble shows unread replies and whether the team is online;
//         chatAvailable() answers whether the chat is on, hideChatBubble()
//         takes the bubble away. The owner picks, per app in the dashboard,
//         the corner it starts in, its size, a greeting that counts as one
//         unread message until the chat is first opened, and the screens it
//         shows on (matched against the names sent with page()). The red
//         number is asked for every 20 s while the bubble is on screen. Also: a shrunk release build of an app
//         without Play Billing no longer stops on "Missing class
//         com.android.billingclient" (consumer-rules.pro, a 4.13.0 slip).
// 4.13.0: Google Play's own price for the app's products. Once a day, in the
//         background, a phone whose Play Store country is the US reads the
//         products Affiliateo asks about with the app's own Play Billing (the
//         base plan's recurring price, never an offer's; one-time products
//         too) and reports them, so the app's product cards on Affiliateo
//         show the store's exact price without any Google Play key. Play
//         Billing 8 is compileOnly: an app without it, or with an older one,
//         reports nothing. Nothing about the person is sent, nothing to call,
//         never blocks or throws, nothing while opted out.
// 4.12.0: free access for apps that sell without RevenueCat (Google Play
//         Billing, StoreKit, Adapty, Stripe). identify(userId, email) takes
//         the signed-in person's email and sends only its SHA-256 (trimmed
//         and lowercased first), never the address. New suspend
//         Affiliateo.freeAccess() asks whether the app owner gave that person
//         free access and answers FreeAccess(active, productId, expiresAt),
//         the end as the ISO 8601 text the server sent; it never throws,
//         saves each answer on the phone, and when offline uses the saved
//         one while its end is ahead and it is at most 7 days old. logout()
//         forgets it. First unit tests (src/test).
// 4.11.0: sign-in and sign-out follow Affiliateo's customers (one person per
//         app, the RevenueCat model). New logout() for sign-out: the device
//         starts over as anonymous and tracking carries on with the same
//         device id, visitor id and obfuscatedAccountId. reset() is now the
//         same as logout() (it used to clear obfuscatedAccountId until the app
//         restarted). identify() skips placeholder ids ("null", "guest", "0",
//         $RCAnonymousID:...), is kept when called before configure(), is
//         stored and re-sent once per launch, and a sign-in that raced ahead
//         of the launch's first identify is sent again. A different user
//         signing in on the same device now switches it.
// 4.10.0: every user gets the purchase tag (the Affiliateo visitor id), so
//         sales from people who did not come through an affiliate are tied
//         to their phone too. state.obfuscatedAccountId is now the visitor id
//         for every device (it was only set for affiliate matches, from a
//         per-affiliate id the SDK minted and registered). Same name, no code
//         changes in the app.
// 4.8.2: ships a shrinker rule (consumer-rules.pro) that keeps the RevenueCat
//        names the SDK looks up at run time. Without it a shrunk (R8) release
//        build renamed them and RevenueCat tagging silently did nothing.
// 4.5.0: campaigns are now apps — configure(appId = ...) is the documented
//   spelling; campaignId = ... still works (deprecated alias).
// 4.4.1: RevenueCat attributes now include affiliateo_visitor_id on every
//        identify (not just affiliate-matched), so purchases link back to the
//        tracked visitor (per-buyer spend, funnels, ad ROAS).
// 4.4.0: version alignment — every Affiliateo SDK (web, React Native,
// Swift, Kotlin, Flutter) now ships the same version number. Identical
// source to 3.1.0.
// 3.1.0: Play Install Referrer capture — the SDK reads Google's install
// referrer once on first launch and sends it with /identify, so a paid-ad
// install (Meta / TikTok / Google Ads) gets its source labelled server-side
// with zero merchant work. Additive, no API changes.
// 3.0.0: event queue + reset/optOut/optIn/flush + Compose helper.
version = "4.15.4"

android {
    namespace = "com.affiliateo.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        // Shipped inside the AAR and applied by every app that shrinks its
        // release build. See the file for why it exists.
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Enable Compose so ComposeHelpers.kt compiles. The Compose runtime
    // itself is compileOnly (declared below) so we don't force compose
    // dependency on consumers who don't use Compose. Consumers that DO
    // use Compose will already have the runtime on their classpath.
    buildFeatures {
        compose = true
    }
    composeOptions {
        // Pin to a Kotlin 1.9.x-compatible compiler version. Update if
        // the Kotlin Gradle plugin in the parent project is bumped.
        kotlinCompilerExtensionVersion = "1.5.4"
    }

    // Declares WHICH build variant is publishable. An Android library builds
    // both debug and release; maven-publish refuses to guess between them, so
    // without this there is no `release` component for the publication below
    // to consume and the publish silently produces nothing.
    publishing {
        singleVariant("release") {
            // Ships sources alongside the AAR so consumers get readable
            // definitions and doc popups in Android Studio instead of
            // decompiled bytecode.
            withSourcesJar()
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // Google's Play Install Referrer — how the Play Store hands the app the
    // link (and its utm/click-id tags) that installed it. Tiny (~20KB).
    implementation("com.android.installreferrer:installreferrer:2.2")
    // The chat page's bridge for its own main frame only (4.15.2,
    // WebViewCompat.addWebMessageListener). Small, AndroidX's own.
    implementation("androidx.webkit:webkit:1.11.0")
    // Compose runtime is compileOnly: ComposeHelpers.kt compiles cleanly
    // here, but consumers without Compose in their app aren't forced to
    // pull in the (large) Compose runtime they don't use. Consumers WITH
    // Compose already have it on their classpath at runtime, so the
    // @Composable function resolves naturally.
    compileOnly("androidx.compose.runtime:runtime:1.5.4")
    // Play Billing is the APP's (RevenueCat brings it): compileOnly, so the
    // SDK adds no dependency and never forces a version. StorePrices checks
    // at run time that the app's Billing has the shape built against (8.x).
    compileOnly("com.android.billingclient:billing:8.3.0")

    // Unit tests (4.12.0): the email hash and the free access rules, on the
    // plain JVM. org.json is Android's own; the stub in android.jar throws
    // off a phone, so the tests get the real one.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
    // The Compose compiler plugin also runs over the tests and refuses to
    // without the runtime it is compileOnly above for.
    testImplementation("androidx.compose.runtime:runtime:1.5.4")
}

// Turns the release AAR into a Maven artifact JitPack can serve.
//
// afterEvaluate is required, not stylistic: the Android Gradle Plugin creates
// components["release"] while evaluating the android {} block above, so a
// publication that referenced it at configuration time would fail with
// "SoftwareComponent with name 'release' not found".
//
// groupId / version come from project properties because JitPack invokes
//   gradle -Pgroup=com.github.affiliateo -Pversion=<tag> publishToMavenLocal
// and the artifact has to carry the coordinates it was asked for. The
// fallbacks let a local `gradle publishToMavenLocal` work unchanged.
//
// artifactId is pinned to the REPOSITORY name rather than the Gradle module
// name (which is "affiliateo"), because the documented coordinate in the
// README is com.github.affiliateo:affiliateo-kotlin — that is what consumers
// already have in their build files, so the artifact has to answer to it.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = (project.findProperty("group") as String?) ?: "com.github.affiliateo"
                artifactId = "affiliateo-kotlin"
                version = (project.findProperty("version") as String?) ?: project.version.toString()
            }
        }
    }
}

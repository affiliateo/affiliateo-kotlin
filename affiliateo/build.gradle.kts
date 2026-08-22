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
version = "4.8.1"

android {
    namespace = "com.affiliateo.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
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
    // Compose runtime is compileOnly: ComposeHelpers.kt compiles cleanly
    // here, but consumers without Compose in their app aren't forced to
    // pull in the (large) Compose runtime they don't use. Consumers WITH
    // Compose already have it on their classpath at runtime, so the
    // @Composable function resolves naturally.
    compileOnly("androidx.compose.runtime:runtime:1.5.4")
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

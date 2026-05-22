plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// 3.0.0: event queue + reset/optOut/optIn/flush + Compose helper.
// Matches the @affiliateo/react-native 4.0.0 + @affiliateo/web 3.0.0 +
// affiliateo-swift parity work shipped at the same time. No API breaks
// for existing 2.x callers — page/track/identify keep the same signature,
// the new methods are additive.
version = "3.0.0"

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
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // Compose runtime is compileOnly: ComposeHelpers.kt compiles cleanly
    // here, but consumers without Compose in their app aren't forced to
    // pull in the (large) Compose runtime they don't use. Consumers WITH
    // Compose already have it on their classpath at runtime, so the
    // @Composable function resolves naturally.
    compileOnly("androidx.compose.runtime:runtime:1.5.4")
}

// Plugin and dependency resolution.
//
// Neither of these blocks existed before, which is why `gradle assemble` had
// never once succeeded — including on the commit that added the build check
// itself. affiliateo/build.gradle.kts declares `id("com.android.library")`
// with no version, and with no root build file and no repository to resolve it
// from, Gradle fails at configuration time with "Plugin [id:
// 'com.android.library'] was not found in any of the following sources".
// Nothing in the SDK source was wrong; the project was simply never buildable
// as committed.
pluginManagement {
    repositories {
        // google() first: the Android Gradle Plugin is published there and
        // nowhere else.
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // The module's own dependencies had nowhere to resolve from either.
    // coroutines lives on mavenCentral; installreferrer and the androidx
    // Compose runtime live on google.
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "affiliateo-kotlin"
include(":affiliateo")

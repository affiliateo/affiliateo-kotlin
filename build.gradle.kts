// Root build file. Declares the plugin VERSIONS once so
// affiliateo/build.gradle.kts can apply them without repeating a version,
// which is the standard Android library layout and the piece this repo never
// had. `apply false` means the plugins are resolved here but only applied in
// the module that asks for them.
//
// These three are a MATCHED SET. Do not bump one on its own:
//
//   Kotlin 1.9.20 <-> Compose compiler 1.5.4. The Compose compiler is
//     released against one exact Kotlin version, and the value lives in
//     affiliateo/build.gradle.kts as kotlinCompilerExtensionVersion. A
//     mismatch is a hard build failure with an explicit message naming both
//     versions, so change them together or not at all.
//
//   AGP 8.2.2 requires Gradle 8.2+ and JDK 17, and supports compileSdk 34
//     (set in the module). Both the Gradle and Java versions are pinned
//     explicitly in .github/workflows/build.yml rather than inherited from
//     whatever the runner image happens to ship, because that is exactly the
//     kind of drift that makes a green build turn red on an unrelated day.
plugins {
    id("com.android.library") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.20" apply false
}

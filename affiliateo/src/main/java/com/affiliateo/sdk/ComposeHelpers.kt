package com.affiliateo.sdk

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * One-line screen tracking for Jetpack Compose. Replaces the old pattern of:
 *
 *   @Composable
 *   fun HomeScreen() {
 *       LaunchedEffect(Unit) { Affiliateo.page("HomeScreen") }
 *       // ...
 *   }
 *
 * With:
 *
 *   @Composable
 *   fun HomeScreen() {
 *       AffiliateoScreen("HomeScreen")
 *       // ...
 *   }
 *
 * Mirrors the @affiliateo/react-native useAffiliateoScreen hook and the
 * Swift .trackedScreen() ViewModifier. The LaunchedEffect keys on the
 * screenName so navigating to a fresh screen of the same composable
 * (e.g. navigating from User#42 to User#99 in a NavigationStack) is
 * correctly counted as a new visit; navigating BACK to the same screen
 * name is also counted, which matches the historical "every entry to
 * this screen is a page view" semantics every analytics tool uses.
 *
 * Metadata is keyed via JSON-string so a caller passing a fresh map
 * literal each render doesn't re-fire — only a logically-different
 * payload triggers a fresh page() call.
 */
@Composable
fun AffiliateoScreen(screenName: String, metadata: Map<String, Any>? = null) {
    // Stringify the metadata for the dep key so map identity doesn't
    // force re-fires. Cheap on small maps and matches the RN hook's
    // dependency-tracking pattern.
    val metaKey = metadata?.toString() ?: ""
    LaunchedEffect(screenName, metaKey) {
        if (screenName.isNotEmpty()) {
            Affiliateo.page(screenName, metadata)
        }
    }
}

package com.affiliateo.sdk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import kotlinx.coroutines.*

/**
 * Main entry point for the Affiliateo SDK.
 *
 * Initialize in your Application class or main Activity:
 *
 * ```kotlin
 * Affiliateo.configure(
 *     context = this,
 *     campaignId = "YOUR_CAMPAIGN_ID"
 * )
 * ```
 *
 * Access the attribution state:
 * ```kotlin
 * val state = Affiliateo.state
 * if (state.isMatched) {
 *     println("Referred by: ${state.refCode}")
 * }
 * ```
 */
object Affiliateo {

    private var client: AffiliateoClient? = null
    private var deviceId: String? = null
    private var campaignId: String? = null
    private var scope: CoroutineScope? = null
    private var configured = false

    var state: AffiliateoState = AffiliateoState(
        refCode = null,
        isMatched = false,
        isLoading = true,
        visitorId = null
    )
        private set

    /**
     * Configure and start the Affiliateo SDK.
     * Call this once in your Application.onCreate() or main Activity.
     */
    fun configure(
        context: Context,
        campaignId: String,
        apiUrl: String = "https://affiliateo.com"
    ) {
        if (configured) return
        configured = true

        this.campaignId = campaignId
        this.client = AffiliateoClient(apiUrl)
        this.deviceId = DeviceId.get(context.applicationContext)
        this.scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        // Identify on startup + auto-fire one screen_view so session_time has
        // >= 2 timestamps (otherwise max - min = 0).
        scope?.launch {
            identify(context.applicationContext)
            sendEvent(MobileEvent(
                type = "screen_view",
                screen = "[Entry]",
                metadata = mapOf("auto" to true)
            ))
        }

        // Listen for app foreground/background. We deliberately fire a
        // screen_view on background (overriding the older "server uses 10-min
        // timeout" design) so the server has a real "last activity" timestamp
        // close to when the user actually left.
        val app = context.applicationContext as? Application ?: return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var activeCount = 0

            override fun onActivityStarted(activity: Activity) {
                if (activeCount == 0) {
                    // App came to foreground
                    scope?.launch {
                        sendSessionEvent("session_start")
                    }
                }
                activeCount++
            }

            override fun onActivityStopped(activity: Activity) {
                activeCount--
                if (activeCount == 0) {
                    // App moved to background — fire one final screen_view so
                    // session_time has an accurate "last activity" stamp.
                    scope?.launch {
                        sendEvent(MobileEvent(
                            type = "screen_view",
                            screen = "[Background]",
                            metadata = mapOf("reason" to "background")
                        ))
                    }
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /**
     * Fire a screen_view event for a specific screen.
     * Call from `onResume()` in Activities or `onResume()` in Fragments.
     */
    @JvmStatic
    @JvmOverloads
    fun page(screenName: String, metadata: Map<String, Any>? = null) {
        scope?.launch {
            sendEvent(MobileEvent(
                type = "screen_view",
                screen = screenName,
                metadata = metadata
            ))
        }
    }

    /**
     * Fire a custom event with arbitrary name + metadata.
     */
    @JvmStatic
    @JvmOverloads
    fun track(eventName: String, metadata: Map<String, Any>? = null) {
        val merged = mutableMapOf<String, Any>("event" to eventName)
        metadata?.let { merged.putAll(it) }
        scope?.launch {
            sendEvent(MobileEvent(type = "custom", metadata = merged))
        }
    }

    private suspend fun sendEvent(event: MobileEvent) {
        val client = client ?: return
        val deviceId = deviceId ?: return
        val campaignId = campaignId ?: return
        try {
            client.sendEvents(campaignId, deviceId, listOf(event))
        } catch (_: Exception) { }
    }

    private suspend fun identify(context: Context) {
        val client = client ?: return
        val deviceId = deviceId ?: return
        val campaignId = campaignId ?: return

        try {
            val deviceInfo = DeviceInfoCollector.collect(context)
            val result = client.identify(campaignId, deviceId, deviceInfo)

            state = AffiliateoState(
                refCode = result.refCode,
                isMatched = result.matched,
                isLoading = false,
                visitorId = result.visitorId
            )

            // Auto-set RevenueCat attribute if matched
            if (result.refCode != null) {
                setRevenueCatAttribute(result.refCode)
            }
        } catch (_: Exception) {
            state = state.copy(isLoading = false)
        }
    }

    private suspend fun sendSessionEvent(type: String) {
        val client = client ?: return
        val deviceId = deviceId ?: return
        val campaignId = campaignId ?: return

        try {
            client.sendEvents(campaignId, deviceId, listOf(MobileEvent(type = type)))
        } catch (_: Exception) { }
    }

    private fun setRevenueCatAttribute(refCode: String) {
        // Try to set RevenueCat attribute via reflection (no hard dependency)
        try {
            val purchasesClass = Class.forName("com.revenuecat.purchases.Purchases")
            val sharedInstance = purchasesClass.getMethod("getSharedInstance").invoke(null)
            val setAttributes = sharedInstance.javaClass.getMethod("setAttributes", Map::class.java)
            setAttributes.invoke(sharedInstance, mapOf("affiliateo_ref" to refCode))
        } catch (_: Exception) { }
    }
}

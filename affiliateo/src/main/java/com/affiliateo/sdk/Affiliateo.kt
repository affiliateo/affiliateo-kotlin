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

        // Identify on startup
        scope?.launch {
            identify(context.applicationContext)
        }

        // Listen for app foreground/background
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
                // No session_end — server handles inactivity via 10-minute timeout
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
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

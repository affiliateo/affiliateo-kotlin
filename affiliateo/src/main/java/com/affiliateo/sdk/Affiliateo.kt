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

        // Identify on startup. Screens are NOT auto-tracked. the host app
        // calls Affiliateo.page(name) per screen, matching the Mixpanel /
        // Amplitude / Datafast mobile model. predictable + debuggable +
        // no ghost events.
        scope?.launch {
            identify(context.applicationContext)
        }

        // Keep the server-side session alive on foreground. The server's
        // start_mobile_session RPC handles rotation based on the 10-minute
        // inactivity timeout. No background screen_view. that was a ghost
        // event that polluted funnels.
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

    /**
     * Link this anonymous device install to a merchant user_id so the
     * funnel can stitch the same person across devices, reinstalls,
     * and the anonymous to logged-in handoff. Call once after sign-in.
     * Idempotent: safe to call on every app launch when a user is
     * signed in.
     *
     * user_id only. the SDK does NOT accept, collect, or transmit
     * email or any other PII.
     */
    @JvmStatic
    fun identify(userId: String) {
        val cleanId = userId.trim()
        if (cleanId.isEmpty() || cleanId.length > 128) return
        val client = client ?: return
        val deviceId = deviceId ?: return
        val campaignId = campaignId ?: return
        scope?.launch {
            try {
                client.identifyUser(campaignId, deviceId, cleanId)
            } catch (_: Exception) { }
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

            // Mint and register the Play Billing obfuscatedAccountId for
            // native Google Play attribution. Persisted per (campaignId, refCode)
            // so the customer's purchase chain (initial sub + renewals + refunds)
            // all carry the same id Google stamped at first purchase.
            //
            // Best-effort: a failed registration here means the next app
            // launch retries (the backend dedups via the unique constraint
            // on mobile_app_visitors.google_obfuscated_account_id).
            var obfuscatedAccountId: String? = null
            if (result.refCode != null) {
                obfuscatedAccountId = getOrMintObfuscatedAccountId(
                    context, campaignId, result.refCode
                )
                scope?.launch {
                    try {
                        client.registerGoogleAccountId(
                            campaignId, result.visitorId, obfuscatedAccountId
                        )
                    } catch (_: Exception) { /* best effort */ }
                }
            }

            state = AffiliateoState(
                refCode = result.refCode,
                isMatched = result.matched,
                isLoading = false,
                visitorId = result.visitorId,
                obfuscatedAccountId = obfuscatedAccountId
            )

            // Auto-set RevenueCat attribute if matched
            if (result.refCode != null) {
                setRevenueCatAttribute(result.refCode)
            }
        } catch (_: Exception) {
            state = state.copy(isLoading = false)
        }
    }

    /**
     * Get or mint a stable UUID v4 for (campaignId, refCode). Persisted via
     * SharedPreferences so the same id is reused across launches for the
     * same affiliate match.
     */
    private fun getOrMintObfuscatedAccountId(
        context: Context,
        campaignId: String,
        refCode: String
    ): String {
        val prefs = context.getSharedPreferences("affiliateo", Context.MODE_PRIVATE)
        val key = "google_obfuscated_account_id:$campaignId:$refCode"
        val stored = prefs.getString(key, null)
        if (stored != null && isUuidV4(stored)) return stored
        val fresh = java.util.UUID.randomUUID().toString()
        prefs.edit().putString(key, fresh).apply()
        return fresh
    }

    private val uuidRegex = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        RegexOption.IGNORE_CASE
    )
    private fun isUuidV4(s: String): Boolean = uuidRegex.matches(s)

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

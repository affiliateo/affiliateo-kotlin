package com.affiliateo.sdk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.*

/**
 * Main entry point for the Affiliateo SDK.
 *
 * Initialize in your Application class or main Activity:
 *
 * ```kotlin
 * Affiliateo.configure(
 *     context = this,
 *     appId = "YOUR_APP_ID"
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
    private var apiUrl: String = "https://affiliateo.com"
    private var scope: CoroutineScope? = null
    private var configured = false
    private var queue: EventQueue? = null
    private var appContext: Context? = null

    // Persistent opt-out flag. Mirrors @affiliateo/react-native 4.0.0,
    // @affiliateo/web 3.0.0, and affiliateo-swift parity. Stored in
    // SharedPreferences so the flag survives app restarts. Hot-path
    // checks happen via the volatile in-memory mirror so we don't pay
    // a SharedPreferences read on every track() call.
    private const val OPT_OUT_KEY = "affiliateo_opt_out"
    private const val OPT_OUT_PREFS = "affiliateo"
    @Volatile private var optedOut: Boolean = false

    // Debug flag. When true, every SDK decision (init, page, track,
    // identify, flush, opt in/out, reset) is printed to Logcat via
    // Log.d("Affiliateo", ...). Only useful during development.
    // ship with debug=false (the default) so production apps don't
    // emit verbose log lines AND don't leak SDK internals to anyone
    // running `adb logcat`. Mirrors @affiliateo/web and affiliateo-swift.
    private const val LOG_TAG = "Affiliateo"
    @Volatile private var debug: Boolean = false

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
     *
     * Pass your app ID via [appId]. The [campaignId] parameter is the
     * pre-4.5.0 name for the same value (Affiliateo campaigns are now
     * called apps) and keeps working; [appId] wins when both are set.
     */
    fun configure(
        context: Context,
        appId: String? = null,
        apiUrl: String = "https://affiliateo.com",
        debug: Boolean = false,
        flushIntervalMs: Long = 5_000L,
        maxQueueSize: Int = 100,
        campaignId: String? = null
    ) {
        val resolvedAppId = appId ?: campaignId
        if (resolvedAppId == null) {
            Log.e(LOG_TAG, "Missing appId — pass your app ID to Affiliateo.configure().")
            return
        }
        if (configured) return
        configured = true

        this.appContext = context.applicationContext
        this.campaignId = resolvedAppId
        this.apiUrl = if (apiUrl.endsWith("/")) apiUrl.dropLast(1) else apiUrl
        this.client = AffiliateoClient(apiUrl)
        this.deviceId = DeviceId.get(context.applicationContext)
        this.scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        // Queue tuning — both clamped inside EventQueue.init so out-of-range
        // values don't break the queue (min 1s flush / max 60s, size [10, 1000]).
        this.queue = EventQueue(
            context.applicationContext,
            flushIntervalMs = flushIntervalMs,
            maxQueueSize = maxQueueSize,
        )
        // Pick up the debug flag BEFORE any other side effect so the next
        // log() call (in the opted-out branch or the identify launch
        // below) actually fires when the merchant turned it on.
        this.debug = debug
        log("init", "app=$resolvedAppId, device=${this.deviceId}")

        // Hydrate opt-out flag from disk BEFORE anything else touches
        // the network. A previously opted-out user staying opted out is
        // the whole point of persistence.
        val prefs = context.applicationContext.getSharedPreferences(OPT_OUT_PREFS, Context.MODE_PRIVATE)
        optedOut = prefs.getString(OPT_OUT_KEY, null) == "true"

        if (optedOut) {
            log("blocked: opted out (call optIn() to re-enable)")
            // Opted-out path: skip identify + foreground ping entirely.
            // Set isLoading=false so any host UI gated on it unblocks.
            // Public methods stay available and noop until optIn() flips
            // the flag back.
            state = AffiliateoState(
                refCode = null,
                isMatched = false,
                isLoading = false,
                visitorId = null,
            )
            return
        }

        // Identify on startup. Screens are NOT auto-tracked. the host app
        // calls Affiliateo.page(name) per screen, matching the Mixpanel /
        // Amplitude mobile model. predictable + debuggable +
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
                if (activeCount == 0 && !optedOut) {
                    // App came to foreground
                    scope?.launch {
                        sendSessionEvent("session_start")
                    }
                    // Re-read the RevenueCat id. The one seen at identify is
                    // usually the anonymous placeholder — RevenueCat only swaps
                    // it for the real id when the app calls logIn, and sign-in
                    // almost always happens after we started. Unchanged ids are
                    // a no-op server-side, so the repeat is free.
                    reportRevenueCatAppUserId()
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
        if (optedOut) return
        log("page", "screen=$screenName, metadata=$metadata")
        enqueueEvent(mapOf<String, Any?>(
            "type" to "screen_view",
            "timestamp" to nowIso(),
            "screen" to screenName,
            "metadata" to metadata,
        ))
    }

    /**
     * Fire a custom event with arbitrary name + metadata.
     */
    @JvmStatic
    @JvmOverloads
    fun track(eventName: String, metadata: Map<String, Any>? = null) {
        if (optedOut) return
        log("track", "event=$eventName, metadata=$metadata")
        val merged = mutableMapOf<String, Any>("event" to eventName)
        metadata?.let { merged.putAll(it) }
        enqueueEvent(mapOf<String, Any?>(
            "type" to "custom",
            "timestamp" to nowIso(),
            "metadata" to merged,
        ))
    }

    /**
     * Wipe the device identity. Drains pending events first (they land
     * server-side under the OLD device_id which is correct), then clears
     * the queue, regenerates the device_id, and resets state. Call on
     * app logout when a different user might sign in afterwards.
     */
    @JvmStatic
    fun reset() {
        val ctx = appContext ?: return
        log("reset")
        scope?.launch {
            queue?.flush()
            queue?.clear()
            // Clear the device_id cache so DeviceId.get() mints a fresh one
            // (the SharedPreferences UUID fallback). The platform androidId
            // is tied to the install and we can't change it; only the
            // UUID fallback gets fresh entropy.
            ctx.getSharedPreferences("affiliateo", Context.MODE_PRIVATE)
                .edit().remove("device_id").apply()
            deviceId = DeviceId.get(ctx)
            state = AffiliateoState(
                refCode = null,
                isMatched = false,
                isLoading = false,
                visitorId = null,
            )
        }
    }

    /**
     * Stop tracking on this device. Sets the persistent opt-out flag in
     * SharedPreferences and silences ALL subsequent page / track /
     * identify calls until optIn() is called. Survives app restart.
     * Pending queued events are dropped. Use for GDPR/CCPA consent.
     */
    @JvmStatic
    fun optOut() {
        val ctx = appContext ?: return
        log("optOut")
        optedOut = true
        ctx.getSharedPreferences(OPT_OUT_PREFS, Context.MODE_PRIVATE)
            .edit().putString(OPT_OUT_KEY, "true").apply()
        queue?.clear()
    }

    /**
     * Re-enable tracking after a previous optOut(). To resume the auto
     * session_start that fires on app foreground, the host should restart
     * the app or call configure() again on a fresh process.
     */
    @JvmStatic
    fun optIn() {
        val ctx = appContext ?: return
        log("optIn")
        optedOut = false
        ctx.getSharedPreferences(OPT_OUT_PREFS, Context.MODE_PRIVATE)
            .edit().remove(OPT_OUT_KEY).apply()
    }

    /**
     * Force-drain the event queue immediately. Useful before a known
     * unrecoverable transition (entering an in-app purchase flow, app
     * about to be backgrounded for a long time). Best-effort: if offline
     * the flush noops and events stay queued for the next retry.
     *
     * Coroutine-suspending. Wrap in `runBlocking` from Java callers if
     * needed (rare — most callers are already in a coroutine scope).
     */
    @JvmStatic
    suspend fun flush() {
        log("flush requested")
        queue?.flush()
    }

    /**
     * Internal debug logger. No-op unless debug=true was passed to configure().
     * Goes through android.util.Log.d so the standard Logcat filter
     * (`adb logcat -s Affiliateo`) picks it up cleanly. Single-arg form for
     * messages without payload; two-arg form for messages with data.
     */
    private fun log(msg: String, data: String? = null) {
        if (!debug) return
        if (data != null) {
            Log.d(LOG_TAG, "$msg | $data")
        } else {
            Log.d(LOG_TAG, msg)
        }
    }

    private fun enqueueEvent(event: Map<String, Any?>) {
        val cid = campaignId ?: return
        val did = deviceId ?: return
        val q = queue ?: return
        q.enqueue(
            endpoint = "$apiUrl/api/v1/mobile/event",
            payload = mapOf<String, Any?>(
                "campaign_id" to cid,
                "device_id" to did,
                "events" to listOf(event),
            )
        )
    }

    private fun nowIso(): String {
        // SimpleDateFormat is not thread-safe across instances but
        // we're already serializing through a single coroutine scope
        // for tracking calls. java.time.Instant would be cleaner but
        // requires API 26+ desugaring; SimpleDateFormat works on all
        // supported API levels.
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return fmt.format(java.util.Date())
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
        if (optedOut) return
        val cleanId = userId.trim()
        if (cleanId.isEmpty() || cleanId.length > 128) return
        val client = client ?: return
        val deviceId = deviceId ?: return
        val campaignId = campaignId ?: return
        log("identify (user)", "user_id=$cleanId")
        scope?.launch {
            try {
                client.identifyUser(campaignId, deviceId, cleanId)
            } catch (_: Exception) { }
        }
    }

    /**
     * Report the host app's RevenueCat App User ID
     * (`Purchases.sharedInstance.appUserID`). Call once after RevenueCat has
     * configured; safe to call on every launch.
     *
     * Lets an app owner grant this affiliate complimentary access to the app
     * from their Affiliateo dashboard. Separate from [identify] on purpose:
     * sign-in and RevenueCat configuration happen at different moments, and an
     * app may do one without the other.
     *
     * No email or other PII is sent, same as [identify].
     */
    @JvmStatic
    fun setRevenueCatUser(appUserId: String) {
        if (optedOut) return
        val rcId = appUserId.trim()
        // 255 matches the server. RevenueCat's anonymous form
        // ($RCAnonymousID:<32 hex>) is already ~50 characters.
        if (rcId.isEmpty() || rcId.length > 255) return
        val client = client ?: return
        val deviceId = deviceId ?: return
        val campaignId = campaignId ?: return
        log("setRevenueCatUser", "revenuecat_user_id=$rcId")
        scope?.launch {
            try {
                client.identifyRevenueCatUser(campaignId, deviceId, rcId)
            } catch (_: Exception) { }
        }
    }

    private suspend fun identify(context: Context) {
        val client = client ?: return
        val deviceId = deviceId ?: return
        val campaignId = campaignId ?: return

        try {
            val deviceInfo = DeviceInfoCollector.collect(context)
            // Play Install Referrer: read once per install (cached after) so a
            // paid-ad install gets its source labelled server-side. Best-effort;
            // never blocks identify for more than the Play Store handshake.
            val installReferrer = try { InstallReferrer.get(context) } catch (_: Exception) { null }
            val result = client.identify(campaignId, deviceId, deviceInfo, installReferrer)

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
            log("identify success", "visitor=${result.visitorId}, matched=${result.matched}, ref=${result.refCode}")

            // Auto-set RevenueCat subscriber attributes. The visitor id goes
            // on EVERY identify (matched or organic): the webhook stamps it
            // onto the conversion row, powering the per-buyer Spent column,
            // funnels journeys, and ad ROAS/LTV joins. The ref code rides
            // along only when the install matched an affiliate.
            setRevenueCatAttributes(result.visitorId, result.refCode)

            // …and read back WHICH RevenueCat customer this device is, the only
            // thing that lets an app owner grant this affiliate free access
            // from their Affiliateo dashboard. Read rather than waiting to be
            // told: this shipped as a setRevenueCatUser() call the merchant had
            // to add themselves, and an install step that gets skipped leaves
            // the feature silently dead with nothing to say why.
            reportRevenueCatAppUserId()
        } catch (_: Exception) {
            log("identify failed (network error)")
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

    private fun sendSessionEvent(type: String) {
        // Route through the queue so foreground pings survive a flaky
        // network the way regular page/track events do. The server's
        // start_mobile_session RPC is idempotent so a duplicate from a
        // queue retry just no-ops.
        enqueueEvent(mapOf<String, Any?>(
            "type" to type,
            "timestamp" to nowIso(),
        ))
    }

    private fun setRevenueCatAttributes(visitorId: String, refCode: String?) {
        // Try to set RevenueCat attributes via reflection (no hard dependency).
        // affiliateo_visitor_id links RevenueCat webhook events back to this
        // device's tracked visitor; without it every conversion lands with
        // visitor_id NULL and per-buyer analytics stay dark.
        try {
            val purchasesClass = Class.forName("com.revenuecat.purchases.Purchases")
            val sharedInstance = purchasesClass.getMethod("getSharedInstance").invoke(null)
            val setAttributes = sharedInstance.javaClass.getMethod("setAttributes", Map::class.java)
            val attributes = buildMap {
                put("affiliateo_visitor_id", visitorId)
                if (refCode != null) put("affiliateo_ref", refCode)
            }
            setAttributes.invoke(sharedInstance, attributes)
        } catch (_: Exception) { }
    }

    /**
     * The host app's RevenueCat App User ID, read straight from RevenueCat via
     * the same reflection the attributes call above uses — no hard dependency,
     * and null for the majority of apps that don't ship RevenueCat at all.
     *
     * `getSharedInstance` throws when the host app hasn't configured RevenueCat
     * yet, which the catch turns into null. That is the right answer rather
     * than an error: the next foreground tries again.
     */
    private fun readRevenueCatAppUserId(): String? {
        return try {
            val purchasesClass = Class.forName("com.revenuecat.purchases.Purchases")
            val sharedInstance = purchasesClass.getMethod("getSharedInstance").invoke(null) ?: return null
            val raw = sharedInstance.javaClass.getMethod("getAppUserID").invoke(sharedInstance) as? String
                ?: return null
            val trimmed = raw.trim()
            // 255 matches the server cap. RevenueCat's anonymous form
            // ($RCAnonymousID:<32 hex>) is already ~50 characters.
            if (trimmed.isEmpty() || trimmed.length > 255) null else trimmed
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Send it, when there is one. Safe to call as often as we like: an
     * unchanged id is a no-op server-side, so every call site can fire and
     * forget.
     *
     * Called on identify AND on every foreground, because the id we see first
     * is usually not the final one — RevenueCat hands out an anonymous
     * placeholder until the app calls logIn, and sign-in almost always happens
     * after we started. The server permits exactly one upgrade from that
     * placeholder to a real id.
     */
    private fun reportRevenueCatAppUserId() {
        val rcId = readRevenueCatAppUserId() ?: return
        // Straight through the public method: it already carries the opt-out
        // check, the null guards on client/deviceId/campaignId, and the
        // logging. Duplicating those here would be a second copy to keep in
        // step for no gain.
        setRevenueCatUser(rcId)
    }
}

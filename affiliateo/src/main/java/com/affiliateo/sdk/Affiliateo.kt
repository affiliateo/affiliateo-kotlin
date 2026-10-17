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

    // Who is signed in (4.11.0), kept in the same SharedPreferences file as
    // the opt-out flag so the SDK can re-send it once per launch, and a
    // sign-out the server has not heard yet. Same key names as the React
    // Native and Swift SDKs.
    private const val USER_ID_KEY = "affiliateo_user_id"
    private const val LOGOUT_PENDING_KEY = "affiliateo_logout_pending"
    /** The signed-in user id, mirrored in SharedPreferences. */
    @Volatile private var userId: String? = null
    /** A sign-out the server has not heard yet (offline, opted out, or made before configure()). */
    @Volatile private var logoutPending = false
    /** identify() or logout() ran before configure() could read the stored values. */
    @Volatile private var userTouched = false
    /** This launch's first /api/v1/mobile/identify succeeded, so a sign-in can link to the device. */
    @Volatile private var firstOpenDone = false
    // Main-thread only (every coroutine below runs on the Main scope):
    /** The user the server confirmed (linked) this launch: identify with the same id sends nothing. */
    private var syncedUser: String? = null
    /** The user id whose identify-user call is on its way. */
    private var userInFlight: String? = null
    /** A logout call on its way; a sign-in waits for it. */
    private var logoutJob: Job? = null

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

        // Who was signed in at the end of the last launch, and a sign-out the
        // server never heard. An identify() or logout() made before configure()
        // wins, except a sign-in while opted out, which does nothing.
        if (userTouched && !(optedOut && userId != null)) {
            saveUserState()
        } else {
            userId = AffiliateoUserId.usable(prefs.getString(USER_ID_KEY, null))
            logoutPending = userId == null && prefs.getString(LOGOUT_PENDING_KEY, null) == "true"
        }

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
                    // A person can switch ad personalisation off (or back on)
                    // in the phone's settings while the app is away.
                    reportAdIdentifiersIfChanged()
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
     * Report a purchase to the ad networks this app sends its sales to. Call
     * it on your paywall, right after a purchase succeeds:
     *
     * ```kotlin
     * // RevenueCat
     * Purchases.sharedInstance.purchaseWith(PurchaseParams.Builder(activity, pkg).build(),
     *     onError = { _, _ -> },
     *     onSuccess = { transaction, _ ->
     *         val orderId = transaction?.orderId
     *         if (orderId != null) {
     *             Affiliateo.purchaseMicros(
     *                 transactionId = orderId,
     *                 productId = pkg.product.id,
     *                 priceAmountMicros = pkg.product.price.amountMicros,
     *                 currency = pkg.product.price.currencyCode,
     *             )
     *         }
     *     })
     * ```
     *
     * [transactionId] is Google Play's ORDER id for this payment ("GPA.…").
     * Affiliateo keys the report on it, so a payment your payment provider
     * ALSO reports (RevenueCat, Google Play or Adapty connected in Affiliateo)
     * goes to each network once. [price] is in normal units (59.99).
     *
     * Optional when a payment provider is connected to the app in Affiliateo:
     * first payments are then reported without it. What this call adds is speed
     * and the phone's own network address, which the networks match on. It
     * records no sale and moves no money.
     */
    @JvmStatic
    @JvmOverloads
    fun purchase(
        transactionId: String,
        productId: String,
        price: Double,
        currency: String,
        kind: PurchaseKind = PurchaseKind.SUBSCRIPTION,
    ) {
        if (optedOut) return
        val txId = transactionId.trim()
        if (txId.isEmpty() || txId.length > 200) return
        val code = currency.trim().uppercase()
        if (code.length != 3 || price.isNaN() || price < 0) return
        val ctx = appContext ?: return
        val cid = campaignId ?: return
        val did = deviceId ?: return
        val q = queue ?: return
        log("purchase", "transaction=$txId, product=$productId, price=$price, currency=$code")
        scope?.launch {
            // The ids as they are NOW, off the main thread.
            val ids = withContext(Dispatchers.IO) { AdIdentifiers.read(ctx) }
            q.enqueue(
                endpoint = "$apiUrl/api/v1/mobile/purchase",
                payload = mapOf<String, Any?>(
                    "campaign_id" to cid,
                    "device_id" to did,
                    "transaction_id" to txId,
                    "product_id" to productId,
                    // A string, so a Double never turns into 59.989999999999995.
                    "price" to java.math.BigDecimal.valueOf(price).toPlainString(),
                    "currency" to code,
                    "kind" to kind.wire,
                    "platform" to "android",
                ) + ids.toMap(),
            )
            // The paywall is often the last screen before the person leaves;
            // do not wait for the timer.
            q.flush()
        }
    }

    /** [purchase] with the price in micros, the unit Google Play Billing and RevenueCat hand you. */
    @JvmStatic
    @JvmOverloads
    fun purchaseMicros(
        transactionId: String,
        productId: String,
        priceAmountMicros: Long,
        currency: String,
        kind: PurchaseKind = PurchaseKind.SUBSCRIPTION,
    ) = purchase(transactionId, productId, priceAmountMicros / 1_000_000.0, currency, kind)

    /** What the last identify (or ad-ids call) told the server about ad tracking. */
    @Volatile private var lastSentTracking: Boolean? = null
    @Volatile private var adIdsSent = false

    private fun reportAdIdentifiersIfChanged() {
        if (!adIdsSent) return
        val ctx = appContext ?: return
        val cid = campaignId ?: return
        val did = deviceId ?: return
        val q = queue ?: return
        scope?.launch {
            val ids = withContext(Dispatchers.IO) { AdIdentifiers.read(ctx) }
            if (ids.trackingAllowed == lastSentTracking) return@launch
            lastSentTracking = ids.trackingAllowed
            log("ad ids changed", "tracking_allowed=${ids.trackingAllowed}")
            q.enqueue(
                endpoint = "$apiUrl/api/v1/mobile/ad-ids",
                payload = mapOf<String, Any?>("campaign_id" to cid, "device_id" to did) + ids.toMap(),
            )
        }
    }

    /**
     * The user signed out (4.11.0). Call it on sign-out: this device starts
     * over as a new anonymous customer of the app, so whoever signs in next
     * (or nobody) is not counted as the person who just left, and everything
     * already recorded stays with them. Forgets the stored user id.
     *
     * Tracking carries on: same device id, same visitor id, same
     * obfuscatedAccountId, nothing to restart. A sign-out made offline (or
     * while opted out, or before configure()) is sent after the next
     * launch's identify.
     */
    @JvmStatic
    fun logout() {
        log("logout requested")
        userTouched = true
        userId = null
        logoutPending = true
        saveUserState()
        scope?.launch {
            syncedUser = null
            sendLogout()
        }
    }

    /**
     * The same as [logout] since 4.11.0. It used to try to mint a new device
     * id (ANDROID_ID cannot be changed) and cleared the state, which left
     * obfuscatedAccountId null until the app restarted. Kept so existing apps
     * keep compiling and now get the sign-out they meant.
     */
    @JvmStatic
    fun reset() = logout()

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
        // Dedup key, stamped once HERE so it is fixed for the life of the
        // queued payload. Every retry then sends the identical id and the
        // server, which holds a unique index on it, keeps exactly one row.
        //
        // This is the choke point every event type flows through (page,
        // track, session pings), so stamping here rather than at each call
        // site means a new event type cannot forget it. It has to be at
        // enqueue and not at send: the queue persists across app launches,
        // and an id minted at send time would differ on every attempt, which
        // is precisely the duplicate we are trying to stop.
        //
        // Without it, a request the server received and wrote but whose
        // response was lost on a flaky connection comes back on the next
        // flush and counts twice. For a `custom` event that is a duplicated
        // funnel conversion.
        val identified = if (event.containsKey("event_id")) event else
            event + ("event_id" to java.util.UUID.randomUUID().toString())
        q.enqueue(
            endpoint = "$apiUrl/api/v1/mobile/event",
            payload = mapOf<String, Any?>(
                "campaign_id" to cid,
                "device_id" to did,
                "events" to listOf(identified),
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
     * Say who is signed in. Call after sign-in, and on launch while someone
     * is signed in. The same person then counts as ONE customer across
     * devices, reinstalls, and the anonymous to signed-in handoff. A
     * different user signing in on this device switches it to them (since
     * 4.11.0; it used to be refused).
     *
     * The SDK stores the id and re-sends it once per launch; called before
     * configure(), it is kept and sent after configure's identify. A sign-in
     * that raced ahead of the launch's first identify is sent again right
     * after it. Does nothing while opted out, and skips ids that mean
     * "nobody" ("null", "undefined", "guest", "0", RevenueCat's
     * $RCAnonymousID:...).
     *
     * user_id only. the SDK does NOT accept, collect, or transmit
     * email or any other PII.
     */
    @JvmStatic
    fun identify(userId: String) {
        if (optedOut) {
            log("identify skipped: opted out")
            return
        }
        val uid = AffiliateoUserId.usable(userId)
        if (uid == null) {
            log("identify skipped: not a user id (empty, over 128 characters, or a placeholder like \"null\", \"guest\" or \"0\")", "user_id=$userId")
            return
        }
        userTouched = true
        this.userId = uid
        // A sign-in replaces a sign-out that never reached the server: a
        // different person signing in switches the device anyway.
        logoutPending = false
        saveUserState()
        // Before configure(): sent once configure's identify has finished.
        scope?.launch {
            // Already confirmed this launch: nothing to send.
            if (syncedUser == uid) return@launch
            sendIdentifyUser(uid)
        }
    }

    /** Mirror the signed-in user and the pending sign-out to SharedPreferences. */
    private fun saveUserState() {
        val ctx = appContext ?: return
        ctx.getSharedPreferences(OPT_OUT_PREFS, Context.MODE_PRIVATE).edit().apply {
            val uid = userId
            if (uid != null) putString(USER_ID_KEY, uid) else remove(USER_ID_KEY)
            if (logoutPending) putString(LOGOUT_PENDING_KEY, "true") else remove(LOGOUT_PENDING_KEY)
        }.apply()
    }

    /**
     * POST /api/v1/mobile/identify-user for the signed-in user. A 400 is not
     * retried (and the stored id is forgotten); a network or server error
     * leaves it stored for the next launch; NotLinked is sent once more when
     * this launch's first identify finished while it was on its way.
     * Runs on the Main scope.
     */
    private suspend fun sendIdentifyUser(uid: String) {
        // Already on its way: that call's answer decides what happens next.
        if (userInFlight == uid) return
        userInFlight = uid
        try {
            repeat(2) {
                // A sign-out still on its way lands first, so it cannot undo this.
                logoutJob?.join()
                val client = client ?: return
                val did = deviceId ?: return
                val cid = campaignId ?: return
                if (optedOut || userId != uid) return
                val beforeFirstOpen = !firstOpenDone
                val result = try {
                    client.identifyUser(cid, did, uid)
                } catch (_: Exception) {
                    log("identify failed (network), sent again next launch", "user_id=$uid")
                    return
                }
                // Signed out, or somebody else signed in, while this was in flight.
                if (userId != uid) return
                when (result) {
                    is IdentifyUserResult.Linked -> {
                        syncedUser = uid
                        log("identify (user)", "user_id=$uid, outcome=${result.outcome}")
                        return
                    }
                    IdentifyUserResult.Refused -> {
                        log("identify refused by the server (400), not retried", "user_id=$uid")
                        userId = null
                        saveUserState()
                        return
                    }
                    IdentifyUserResult.NotLinked -> {
                        if (!(beforeFirstOpen && firstOpenDone)) {
                            log("identify: device not known yet, sent again after the first identify", "user_id=$uid")
                            return
                        }
                        log("identify: device known now, sending again", "user_id=$uid")
                    }
                }
            }
        } finally {
            if (userInFlight == uid) userInFlight = null
        }
    }

    /**
     * POST /api/v1/mobile/logout. The pending flag is cleared only once the
     * server has it; until then the next launch sends it again. Runs on the
     * Main scope.
     */
    private suspend fun sendLogout() {
        if (optedOut) return
        val client = client ?: return
        val did = deviceId ?: return
        val cid = campaignId ?: return
        val s = scope ?: return
        val job = s.launch {
            try {
                val outcome = client.logout(cid, did)
                log("logout", "outcome=$outcome")
                // Nobody signed in meanwhile (a sign-in clears the flag itself).
                if (userId == null) {
                    logoutPending = false
                    saveUserState()
                }
            } catch (_: Exception) {
                log("logout failed (network), sent again next launch")
            }
        }
        logoutJob = job
        job.join()
        if (logoutJob === job) logoutJob = null
    }

    /**
     * After this launch's first identify: the stored user if the server has
     * not confirmed it yet (which also covers a sign-in that answered
     * NotLinked), or a sign-out it never heard. Runs on the Main scope.
     */
    private suspend fun syncSignedInUser() {
        val uid = userId
        if (uid != null) {
            if (syncedUser != uid) sendIdentifyUser(uid)
        } else if (logoutPending) {
            sendLogout()
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
            // Off the main thread: the Play services lookup blocks.
            val adIdentifiers = withContext(Dispatchers.IO) { AdIdentifiers.read(context) }
            val result = client.identify(campaignId, deviceId, deviceInfo, installReferrer, adIdentifiers)
            lastSentTracking = adIdentifiers.trackingAllowed
            adIdsSent = true

            // Google Play native attribution (4.10.0). The purchase tag is the
            // visitor id itself, for EVERY device, matched or organic (a UUID,
            // well inside Play's 64-character limit). The app reads it via
            // state.obfuscatedAccountId and passes it to
            // BillingFlowParams.setObfuscatedAccountId(), Google stamps it onto
            // the whole purchase chain (first sub, renewals, refunds), and the
            // server matches it to this visitor directly, so nothing is
            // registered from here. Ids minted per affiliate before 4.10.0
            // keep working server side, and a chain bought with one keeps
            // carrying it.
            val obfuscatedAccountId = result.visitorId.takeIf { it.isNotEmpty() && it.length <= 64 }

            state = AffiliateoState(
                refCode = result.refCode,
                isMatched = result.matched,
                isLoading = false,
                visitorId = result.visitorId,
                obfuscatedAccountId = obfuscatedAccountId
            )
            log("identify success", "visitor=${result.visitorId}, matched=${result.matched}, ref=${result.refCode}")

            // The server knows this device now: bring it up to date on who is
            // signed in.
            firstOpenDone = true
            scope?.launch { syncSignedInUser() }

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

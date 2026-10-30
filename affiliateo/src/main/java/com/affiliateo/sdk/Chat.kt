package com.affiliateo.sdk

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Live Chat (4.14.0): the app's users chat in real time with the business,
 * which answers from the Live Chat inbox in its Affiliateo dashboard.
 *
 *     Affiliateo.showChatBubble()       the bubble on the app's pages
 *     Affiliateo.openChat(activity)     or open it from your own button
 *
 * Nothing shows until the app calls one of them: an app must not grow a
 * visible feature its store reviewer never saw. And nothing shows while the
 * business has the chat switched off for this app, which the SDK asks the
 * server about.
 *
 * The owner's choices come with the config (ChatRules.Bubble): the corner the
 * bubble starts in, its size, the screens it shows on (matched against the
 * name the app last passed to Affiliateo.page()), and a greeting that counts
 * as one unread message until the chat is first opened. While the bubble is
 * on screen and the chat closed, the red number is asked for every
 * UNREAD_POLL_MS (Android has no WebSocket of its own, and the SDK adds no
 * library for one).
 *
 * The bubble is a view the SDK adds on top of each screen's content
 * (android.R.id.content) as it comes up, never a window of its own, so a
 * dialog, a bottom sheet or a new screen the app opens covers it. An app that
 * draws its own sheets inside its Compose content gives the bubble a page
 * layer under them (Affiliateo.chatBubbleLayer, 4.15.2). The visitor's key to their thread (a token the server
 * hands out with their first message) is kept in a file in the app's
 * no-backup folder (4.15.4, review S18; ChatTokenStore), never in a backup.
 * Affiliateo.logout() drops it (4.14.2), so the next person on the phone
 * starts a new thread.
 */
internal object LiveChat {
    private const val LOG_TAG = "Affiliateo"
    private const val BUBBLE_TAG = "affiliateo_chat_bubble"
    /** The SDK's one SharedPreferences file (Affiliateo.kt OPT_OUT_PREFS). */
    private const val PREFS = "affiliateo"
    /** How often the red number is asked for while the bubble is on screen. */
    private const val UNREAD_POLL_MS = 20_000L

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var installed = false
    private var resumed: WeakReference<Activity>? = null
    private var startedCount = 0

    // Main thread only from here on.
    private var config: ChatRules.Config? = null
    private var configCheckedAt = 0L
    private var configJob: Deferred<ChatRules.Config>? = null
    private var unreadCheckedAt = 0L
    private var unread = 0
    /** Online for this person alone (their own affiliate counts), from the unread check, beside the config's. */
    private var visitorOnline = false
    /** The greeting nobody on this phone has seen yet: one unread message. */
    private var greetingPending = false
    /** The name the app last passed to page(). */
    private var currentScreen: String? = null
    private var wantsBubble = false
    /** Dp the app keeps free above the bottom bars (its own tab bar or floating button). */
    private var bottomPaddingDp = 0
    /** The app's own look for the bubble (setChatBubbleLook), over the dashboard's. */
    private var look = ChatRules.BubbleLook()
    /** The app's own message box text (setChatPlaceholder), over the dashboard's. */
    private var placeholder: String? = null
    private var screenOpen = false
    /** The chat screen while it is up, so a sign-out can close it. */
    private var chatScreen: WeakReference<ChatActivity>? = null
    private var poll: Job? = null

    private val env: ChatRules.Env? get() = Affiliateo.chatEnv()

    private fun log(msg: String) = Affiliateo.chatLog(msg)

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /**
     * Called by configure(), opted out or not: the chat works while opted out
     * too, it only leaves the tracking ids out of the page.
     */
    fun install(app: Application) {
        if (installed) return
        installed = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (startedCount == 0) cameToFront()
                startedCount++
            }

            override fun onActivityStopped(activity: Activity) {
                startedCount--
                if (startedCount == 0) stopPolling()
            }

            override fun onActivityResumed(activity: Activity) {
                resumed = WeakReference(activity)
                if (activity !is ChatActivity) placeBubble()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        // A showChatBubble() made before configure().
        if (wantsBubble) showBubble()
    }

    // ── What the app calls ────────────────────────────────────────────────

    fun showBubble() = onMain {
        wantsBubble = true
        // Placed once configure() runs (install).
        val e = env ?: return@onMain
        // At once from the last answer this phone kept, then checked with the
        // server (turned off there, it goes away again).
        val ctx = Affiliateo.chatContext()
        if (config == null && ctx != null) {
            prefs(ctx).getString(ChatRules.configKey(e.appId), null)?.let { saved ->
                val answer = ChatRules.parseConfig(saved)
                if (answer.enabled) {
                    config = answer
                    placeBubble()
                }
            }
        }
        scope.launch {
            refreshConfig(force = configCheckedAt == 0L)
            placeBubble()
            refreshUnread(force = true)
        }
    }

    fun setLook(next: ChatRules.BubbleLook) = onMain {
        look = next
        placeBubble()
    }

    fun setPlaceholder(text: String?) = onMain {
        placeholder = text
    }

    fun setBottomPadding(dp: Int) = onMain {
        bottomPaddingDp = dp.coerceIn(0, 400)
        bubbleIn(resumed?.get())?.let {
            it.bottomPaddingDp = bottomPaddingDp
            it.requestPlace()
        }
    }

    fun hideBubble() = onMain {
        wantsBubble = false
        placeBubble()
    }

    fun open(activity: Activity) = onMain {
        if (env == null) {
            Log.w(LOG_TAG, "openChat: call Affiliateo.configure() first")
            return@onMain
        }
        val ref = WeakReference(activity)
        scope.launch {
            if (!refreshConfig(force = false).enabled) {
                log("openChat skipped, the chat is off for this app")
                return@launch
            }
            ref.get()?.let { present(it) }
        }
    }

    suspend fun available(): Boolean = withContext(Dispatchers.Main) {
        if (env == null) false else refreshConfig(force = false).enabled
    }

    /** The app called page(): the owner's screen rules follow it. */
    fun screenChanged(name: String) = onMain {
        if (name.isEmpty() || name == currentScreen) return@onMain
        currentScreen = name
        if (wantsBubble && config != null) placeBubble()
    }

    /**
     * The person signed out (Affiliateo.logout()). Their token goes right
     * away, then their red number, the poll and an open chat, so whoever
     * signs in next starts a new thread. The bubble's place and the greeting
     * seen belong to the phone, not the person: they stay.
     */
    fun forget() {
        // First, on the caller's thread (4.15.1): an open chat screen saves no
        // key it is handed from now on, even while it is still sliding away.
        ChatSignOuts.bump()
        val e = env ?: return
        val ctx = Affiliateo.chatContext() ?: return
        ChatTokenStore.of(ctx).clear(e.appId)
        onMain {
            stopPolling()
            unreadCheckedAt = 0L
            visitorOnline = false
            setUnread(0)
            chatScreen?.get()?.finish()
        }
    }

    // ── The server ────────────────────────────────────────────────────────

    /** Whether the business has the chat on for this app. A network failure keeps the last answer; anything but a 200 reads as off. */
    private suspend fun refreshConfig(force: Boolean): ChatRules.Config {
        configJob?.let { return it.await() }
        val cached = config
        if (!force && cached != null && System.currentTimeMillis() - configCheckedAt < ChatRules.CONFIG_MAX_AGE_MS) return cached
        val e = env ?: return cached ?: ChatRules.Config.OFF
        val job = scope.async {
            withContext(Dispatchers.IO) {
                try {
                    val conn = URL("${e.apiUrl}/api/live-chat/config?app=${ChatRules.encode(e.appId)}").openConnection() as HttpURLConnection
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 10_000
                    conn.useCaches = !force
                    try {
                        if (conn.responseCode == 200) {
                            val text = conn.inputStream.bufferedReader().readText()
                            Affiliateo.chatContext()?.let { prefs(it).edit().putString(ChatRules.configKey(e.appId), text).apply() }
                            ChatRules.parseConfig(text)
                        } else {
                            ChatRules.Config.OFF
                        }
                    } finally {
                        conn.disconnect()
                    }
                } catch (err: Exception) {
                    null
                }
            } ?: run {
                log("config check failed (network)")
                config ?: ChatRules.Config.OFF
            }
        }
        configJob = job
        val answer = try {
            job.await()
        } finally {
            configJob = null
        }
        config = answer
        configCheckedAt = System.currentTimeMillis()
        // Somebody who already wrote has seen the greeting.
        val ctx = Affiliateo.chatContext()
        val greetingId = answer.bubble.greetingId
        greetingPending = answer.enabled && greetingId != null && ctx != null &&
            storedToken(ctx, e.appId) == null && prefs(ctx).getString(ChatRules.greetingKey(e.appId), null) != greetingId
        return answer
    }

    /** Replies the person has not seen, for the red number on the bubble. */
    private suspend fun refreshUnread(force: Boolean) {
        val e = env ?: return
        val ctx = Affiliateo.chatContext() ?: return
        val token = storedToken(ctx, e.appId) ?: return
        if (!force && System.currentTimeMillis() - unreadCheckedAt < ChatRules.UNREAD_MAX_AGE_MS) return
        unreadCheckedAt = System.currentTimeMillis()
        val text = withContext(Dispatchers.IO) {
            try {
                val conn = URL("${e.apiUrl}/api/live-chat/unread").openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.useCaches = false
                conn.setRequestProperty("Authorization", "Bearer $token")
                try {
                    if (conn.responseCode == 200) conn.inputStream.bufferedReader().readText() else null
                } finally {
                    conn.disconnect()
                }
            } catch (err: Exception) {
                null
            }
        } ?: return
        val count = ChatRules.parseUnread(text) ?: return
        // A sign-out while this was on its way: the count was the last person's.
        if (storedToken(ctx, e.appId) != token) return
        visitorOnline = ChatRules.parseUnreadOnline(text) ?: false
        // An open chat has just shown them everything.
        if (!screenOpen) setUnread(count) else onMain { paintBubble() }
    }

    /** The owner may have switched the chat on or off while the app was away, and a reply may have come in. */
    private fun cameToFront() {
        if (!wantsBubble || env == null) return
        scope.launch {
            refreshConfig(force = false)
            placeBubble()
            refreshUnread(force = false)
        }
    }

    fun setUnread(count: Int) = onMain {
        unread = count
        paintBubble()
    }

    private fun paintBubble() {
        bubbleIn(resumed?.get())?.update(unread + if (greetingPending) 1 else 0, config?.online == true || visitorOnline)
    }

    // ── The live red number ───────────────────────────────────────────────

    /** Asks for the red number every UNREAD_POLL_MS while the bubble is on screen, the app is in front and the chat is closed. */
    private fun startPolling() {
        if (poll?.isActive == true) return
        val ctx = Affiliateo.chatContext() ?: return
        val e = env ?: return
        if (storedToken(ctx, e.appId) == null) return
        poll = scope.launch {
            while (isActive) {
                delay(UNREAD_POLL_MS)
                if (startedCount == 0 || screenOpen) break
                refreshUnread(force = true)
            }
        }
    }

    private fun stopPolling() {
        poll?.cancel()
        poll = null
    }

    // ── Token (ChatTokenStore), greeting and position (SharedPreferences) ──

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun storedToken(context: Context, appId: String): String? = ChatTokenStore.of(context).token(appId)

    fun saveToken(context: Context, appId: String, token: String) {
        ChatTokenStore.of(context).save(appId, token)
    }

    /** Where the person left the bubble, in dp from the right and bottom: the React Native bubble's {"x":..,"y":..}. */
    fun savedOffset(context: Context, appId: String): Pair<Float, Float>? {
        val raw = prefs(context).getString(ChatRules.positionKey(appId), null) ?: return null
        return try {
            val json = JSONObject(raw)
            Pair(json.getDouble("x").toFloat(), json.getDouble("y").toFloat())
        } catch (e: Exception) {
            null
        }
    }

    fun saveOffset(context: Context, appId: String, x: Float, y: Float) {
        val json = JSONObject().put("x", x.toDouble()).put("y", y.toDouble())
        prefs(context).edit().putString(ChatRules.positionKey(appId), json.toString()).apply()
    }

    // ── The bubble ────────────────────────────────────────────────────────

    private fun bubbleIn(activity: Activity?): ChatBubbleView? {
        if (activity == null) return null
        layerOf(activity)?.findViewWithTag<ChatBubbleView>(BUBBLE_TAG)?.let { return it }
        return activity.findViewById<ViewGroup>(android.R.id.content)?.findViewWithTag(BUBBLE_TAG)
    }

    /** The page layers apps gave the bubble (Affiliateo.chatBubbleLayer), newest last. */
    private val layers = mutableListOf<WeakReference<FrameLayout>>()

    /** A layer for the app's pages that the bubble lives in (4.15.2, review E1); see Affiliateo.chatBubbleLayer. */
    fun bubbleLayer(context: Context): FrameLayout = FrameLayout(context).also { layer ->
        layers.removeAll { it.get() == null }
        layers.add(WeakReference(layer))
        // Only the bubble takes touches: the layer itself lets them through.
        layer.isClickable = false
        layer.isFocusable = false
        layer.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = onMain { placeBubble() }
            override fun onViewDetachedFromWindow(v: View) {}
        })
    }

    /** The activity a view's context belongs to (Compose hands AndroidView a wrapped one). */
    private fun activityOf(context: Context): Activity? {
        var c: Context? = context
        while (c is android.content.ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    /** This screen's page layer, when the app put one on it. */
    private fun layerOf(activity: Activity): FrameLayout? =
        layers.mapNotNull { it.get() }.lastOrNull { it.isAttachedToWindow && activityOf(it.context) === activity }

    /**
     * Puts the bubble on the screen in front, or takes it off, to match what
     * the app asked, what the server said and which screen this is.
     *
     * THE BUBBLE IS FOR THE APP'S PAGES (4.15.2, review E1). It is a view in the
     * screen's own window, never a window of its own, so dialogs, bottom
     * sheets and screens with windows of their own cover it. It goes in the
     * app's page layer when the app put one on this screen (chatBubbleLayer),
     * so sheets and covers the app draws in its own Compose tree cover it too;
     * otherwise on top of the screen's content, as before.
     */
    private fun placeBubble() {
        val activity = resumed?.get() ?: return
        if (activity is ChatActivity || activity.isFinishing) return
        // Never on another library's screen over the app's own (Play Billing's
        // purchase sheet): the bubble stays on the app's screen under it (review E16).
        if (!ChatRules.hostsBubble(activity.javaClass.name)) return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val layer = layerOf(activity)
        val container: ViewGroup = layer ?: content
        // One left on top of the content from before the layer came goes.
        if (layer != null) {
            (0 until content.childCount).map { content.getChildAt(it) }.filter { it.tag == BUBBLE_TAG }.forEach { content.removeView(it) }
        }
        var existing = (0 until container.childCount).map { container.getChildAt(it) }.firstOrNull { it.tag == BUBBLE_TAG } as? ChatBubbleView
        val cfg = config
        val e = env
        val style = ChatRules.applying(look, cfg?.bubble ?: ChatRules.Bubble.STANDARD)
        if (!wantsBubble || screenOpen || e == null || cfg == null || !cfg.enabled || !cfg.showBubble ||
            !ChatRules.bubbleShowsOn(style.pageMode, style.pageRules, currentScreen)
        ) {
            existing?.let { container.removeView(it) }
            stopPolling()
            return
        }
        // A phone gets the owner's phone size ("auto" makes it smaller), a tablet the other.
        val size = if (activity.resources.configuration.smallestScreenWidthDp < 600) style.sizePhone else style.size
        val glass = style.style == "glass"
        val colors = Pair(style.color, style.iconColor)
        if (existing != null && (existing.sizeDp != size || existing.position != style.position || existing.glass != glass || existing.colors != colors)) {
            container.removeView(existing)
            existing = null
        }
        val bubble = existing ?: ChatBubbleView(activity, e.appId, size, style.position, glass, colors) { open(activity) }.also {
            it.tag = BUBBLE_TAG
            it.bottomPaddingDp = bottomPaddingDp
            container.addView(it)
        }
        if (bubble.bottomPaddingDp != bottomPaddingDp) {
            bubble.bottomPaddingDp = bottomPaddingDp
            bubble.requestPlace()
        }
        bubble.bringToFront()
        paintBubble()
        startPolling()
    }

    // ── The chat screen ───────────────────────────────────────────────────

    private fun present(activity: Activity) {
        if (screenOpen) return
        val e = env ?: return
        val dark = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val url = ChatRules.chatUrl(
            env = e,
            token = storedToken(activity, e.appId),
            platform = "android",
            language = Locale.getDefault().toLanguageTag(),
            theme = if (dark) "dark" else "light",
            sendColor = look.sendColor,
            placeholder = placeholder,
        )
        // They see the greeting now; a new one counts again.
        config?.bubble?.greetingId?.let { prefs(activity).edit().putString(ChatRules.greetingKey(e.appId), it).apply() }
        greetingPending = false
        screenOpen = true
        placeBubble()
        val intent = Intent(activity, ChatActivity::class.java)
            .putExtra(ChatActivity.EXTRA_URL, url)
            .putExtra(ChatActivity.EXTRA_API_URL, e.apiUrl)
            .putExtra(ChatActivity.EXTRA_APP_ID, e.appId)
            .putExtra(ChatActivity.EXTRA_DARK, dark)
            .putExtra(ChatActivity.EXTRA_DEBUG, e.debug)
        try {
            activity.startActivity(intent)
            // The sheet slides itself up: no window animation on top of it.
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(0, 0)
        } catch (err: Exception) {
            screenOpen = false
            Log.w(LOG_TAG, "openChat: the chat screen did not open (${err.javaClass.simpleName})")
        }
    }

    fun chatOpened(activity: ChatActivity) = onMain {
        screenOpen = true
        chatScreen = WeakReference(activity)
    }

    fun chatClosed() = onMain {
        screenOpen = false
        chatScreen = null
        unread = 0
        placeBubble()
    }
}

/**
 * The floating chat bubble. It starts in the owner's corner; press, hold and
 * move to drag it, and it stays where it was left from then on. The view is
 * 4 dp bigger than the circle on each side so the red number and the green
 * dot fit inside it.
 */
@SuppressLint("ViewConstructor")
internal class ChatBubbleView(
    context: Context,
    private val appId: String,
    /** The circle, in dp. */
    val sizeDp: Int,
    /** The owner's corner: bottom-right, bottom-left, top-right or top-left. */
    val position: String,
    /** The owner's "Glass" style: a see-through glass circle tinted with the bubble color. */
    val glass: Boolean,
    /** The owner's circle and icon colors (#rrggbb), as the config gave them. */
    val colors: Pair<String, String>,
    private val onTap: () -> Unit,
) : FrameLayout(context) {
    /** Dp the app keeps free above the bottom bars: the bubble starts above them and is never dragged into them. */
    var bottomPaddingDp = 0
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Float) = (value * density).toInt()

    private val circlePx = dp(sizeDp.toFloat())
    private val padPx = dp(4f)
    /** The red number and the green dot, scaled with the circle. */
    private val badge = ChatRules.badgeMetrics(sizeDp)
    private val boxPx = circlePx + 2 * padPx

    private val pill = TextView(context)
    private val dot = View(context)
    /** What TalkBack says, in the person's language (4.15.2, review E14). */
    private val words = ChatRules.chatWords(Locale.getDefault().toLanguageTag())

    /**
     * Distance of the circle from the content's right and bottom edges, in dp,
     * system bars included, once the person has dragged it (now or on an
     * earlier launch). Until then the bubble follows the owner's corner.
     */
    private var dragged: Pair<Float, Float>? = LiveChat.savedOffset(context, appId)
    private var downX = 0f
    private var downY = 0f
    private var startOffset = Pair(0f, 0f)
    private var dragging = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        layoutParams = LayoutParams(boxPx, boxPx, Gravity.BOTTOM or Gravity.END)
        clipChildren = false
        clipToPadding = false
        isClickable = true
        isFocusable = true
        contentDescription = words.chat
        // The bubble casts the shadow itself, shaped like the circle: a
        // shadow cast by a view inside it would be cut off square at this
        // view's edges.
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(padPx, padPx, padPx + circlePx, padPx + circlePx)
            }
        }
        val tint = ChatRules.colorInt(colors.first)
        if (!glass) {
            // The solid circle casts its own shadow, in its color; glass carries
            // its edge with the stroke and the light at the top instead.
            elevation = dp(8f).toFloat()
            if (Build.VERSION.SDK_INT >= 28) {
                outlineSpotShadowColor = tint
                outlineAmbientShadowColor = tint
            }
        }
        addView(
            BubbleCircle(context, circlePx, tint, ChatRules.colorInt(colors.second), glass),
            LayoutParams(circlePx, circlePx).apply { leftMargin = padPx; topMargin = padPx },
        )

        pill.setTextColor(Color.WHITE)
        // In dp like the pill around it (4.15.2, review E19): in sp a large font
        // setting grew the number out of its pill. The other copies do the same.
        pill.setTextSize(TypedValue.COMPLEX_UNIT_DIP, badge.pillFont)
        pill.typeface = Typeface.DEFAULT_BOLD
        pill.gravity = Gravity.CENTER
        pill.minWidth = dp(badge.pillHeight)
        pill.setPadding(dp(badge.pillPadding), 0, dp(badge.pillPadding), 0)
        pill.includeFontPadding = false
        pill.background = GradientDrawable().apply {
            setColor(Color.parseColor("#EF4444"))
            cornerRadius = dp(badge.pillHeight) / 2f
            setStroke(dp(badge.border).coerceAtLeast(1), Color.WHITE)
        }
        pill.visibility = GONE
        pill.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        // The box is 4 dp bigger than the circle on each side; the pill sits pillOffset out from the circle.
        addView(
            pill,
            LayoutParams(LayoutParams.WRAP_CONTENT, dp(badge.pillHeight), Gravity.TOP or Gravity.END).apply {
                topMargin = padPx - dp(badge.pillOffset)
                rightMargin = padPx - dp(badge.pillOffset)
            },
        )

        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#22C55E"))
            setStroke(dp(badge.border).coerceAtLeast(1), Color.WHITE)
        }
        dot.visibility = GONE
        addView(
            dot,
            LayoutParams(dp(badge.dot), dp(badge.dot)).apply {
                leftMargin = padPx - dp(badge.dotOffset)
                topMargin = padPx - dp(badge.dotOffset)
            },
        )
    }

    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name

    fun update(unread: Int, online: Boolean) {
        val text = ChatRules.badgeText(unread)
        if (text != null) {
            pill.text = text
            pill.visibility = VISIBLE
            dot.visibility = GONE
            contentDescription = words.unread(unread)
        } else {
            pill.visibility = GONE
            dot.visibility = if (online) VISIBLE else GONE
            contentDescription = words.chat
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestApplyInsets()
        post { place() }
    }

    fun requestPlace() = post { place() }

    /** In the owner's corner, or where the person left it. */
    private fun place() {
        val spot = dragged
        if (spot != null) {
            move(spot)
            return
        }
        val params = layoutParams as? LayoutParams ?: return
        val (bottom, right, top, left) = barOverlap()
        val margin = dp(20f) - padPx
        val isTop = position.startsWith("top")
        val isLeft = position.endsWith("left")
        params.gravity = (if (isTop) Gravity.TOP else Gravity.BOTTOM) or (if (isLeft) Gravity.START else Gravity.END)
        params.topMargin = if (isTop) margin + top else 0
        params.bottomMargin = if (isTop) 0 else margin + bottom + dp(bottomPaddingDp.toFloat())
        params.leftMargin = if (isLeft) margin + left else 0
        params.rightMargin = if (isLeft) 0 else margin + right
        layoutParams = params
    }

    /** How much of the system bars covers the content's bottom, right, top and left edges, in px. */
    private fun barOverlap(): IntArray {
        val parent = parent as? View ?: return intArrayOf(0, 0, 0, 0)
        val insets = parent.rootWindowInsets ?: return intArrayOf(0, 0, 0, 0)
        val bottom: Int
        val right: Int
        val top: Int
        val left: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            bottom = bars.bottom; right = bars.right; top = bars.top; left = bars.left
        } else {
            @Suppress("DEPRECATION")
            bottom = insets.systemWindowInsetBottom
            @Suppress("DEPRECATION")
            right = insets.systemWindowInsetRight
            @Suppress("DEPRECATION")
            top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            left = insets.systemWindowInsetLeft
        }
        // A content view that already sits inside the bars needs no extra room.
        val loc = IntArray(2)
        parent.getLocationInWindow(loc)
        val root = parent.rootView
        val belowContent = root.height - (loc[1] + parent.height)
        val rightOfContent = root.width - (loc[0] + parent.width)
        return intArrayOf(
            (bottom - belowContent).coerceAtLeast(0),
            (right - rightOfContent).coerceAtLeast(0),
            (top - loc[1]).coerceAtLeast(0),
            (left - loc[0]).coerceAtLeast(0),
        )
    }

    /** Where the circle is now, as dp from the content's right and bottom edges. */
    private fun currentOffset(): Pair<Float, Float> {
        val parent = parent as? View ?: return Pair(20f, 20f)
        return Pair((parent.width - right + padPx) / density, (parent.height - bottom + padPx) / density)
    }

    private fun clamp(point: Pair<Float, Float>): Pair<Float, Float> {
        val parent = parent as? View ?: return point
        val (bottom, right, top, left) = barOverlap()
        val width = parent.width / density
        val height = parent.height / density
        val minX = right / density + 8f
        val minY = bottom / density + bottomPaddingDp + 8f
        val maxX = (width - sizeDp - left / density - 8f).coerceAtLeast(minX)
        val maxY = (height - sizeDp - top / density - 8f).coerceAtLeast(minY)
        return Pair(point.first.coerceIn(minX, maxX), point.second.coerceIn(minY, maxY))
    }

    /** To a spot given as dp from the right and bottom edges. */
    private fun move(point: Pair<Float, Float>) {
        val next = clamp(point)
        val params = layoutParams as? LayoutParams ?: return
        params.gravity = Gravity.BOTTOM or Gravity.END
        params.topMargin = 0
        params.leftMargin = 0
        params.rightMargin = (next.first * density).toInt() - padPx
        params.bottomMargin = (next.second * density).toInt() - padPx
        layoutParams = params
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                startOffset = dragged ?: currentOffset()
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!dragging && Math.abs(dx) + Math.abs(dy) < slop) return true
                dragging = true
                val next = clamp(Pair(startOffset.first - dx / density, startOffset.second - dy / density))
                dragged = next
                move(next)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    dragged?.let { LiveChat.saveOffset(context, appId, it.first, it.second) }
                } else {
                    performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        onTap()
        return true
    }
}

/**
 * The bubble's circle in the owner's colors, with the website's speech bubble
 * in the icon color filling 60% of it. Solid: the color, a little lighter at
 * the top left (the website's gradient). Glass: see-through, tinted with the
 * color, light at the top like glass, with a fine white edge (Android has no
 * Liquid Glass, so this is its glass look).
 */
@SuppressLint("ViewConstructor")
internal class BubbleCircle(
    context: Context,
    private val sizePx: Int,
    color: Int,
    iconColor: Int,
    private val glass: Boolean,
) : View(context) {
    private val density = context.resources.displayMetrics.density
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val shine = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val edge = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = density
        this.color = Color.argb(140, 255, 255, 255)
    }
    private val glyph = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = iconColor }
    private val bubblePath = speechBubble(sizePx.toFloat())

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        val size = sizePx.toFloat()
        if (glass) {
            fill.color = Color.argb(150, Color.red(color), Color.green(color), Color.blue(color))
            shine.shader = android.graphics.LinearGradient(
                0f, 0f, 0f, size,
                Color.argb(90, 255, 255, 255), Color.argb(0, 255, 255, 255),
                android.graphics.Shader.TileMode.CLAMP,
            )
        } else {
            // color-mix(in oklab, color 55%, white), close enough in plain RGB.
            fun light(c: Int) = (c * 0.55f + 255 * 0.45f).toInt()
            val lighter = Color.rgb(light(Color.red(color)), light(Color.green(color)), light(Color.blue(color)))
            fill.shader = android.graphics.LinearGradient(0f, 0f, size, size, lighter, color, android.graphics.Shader.TileMode.CLAMP)
        }
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val r = sizePx / 2f
        canvas.drawCircle(r, r, r, fill)
        if (glass) {
            canvas.drawCircle(r, r, r, shine)
            canvas.drawCircle(r, r, r - edge.strokeWidth / 2, edge)
        }
        canvas.drawPath(bubblePath, glyph)
    }

    companion object {
        /** The website bubble's speech bubble (public/chat.js, a 24-point drawing) filling 60% of a circle of `size`. */
        fun speechBubble(size: Float): android.graphics.Path {
            val scale = size * 0.6f / 24f
            val inset = size * 0.2f
            fun x(v: Float) = inset + v * scale
            fun y(v: Float) = inset + v * scale
            return android.graphics.Path().apply {
                moveTo(x(12f), y(2.5f))
                cubicTo(x(17.25f), y(2.5f), x(21.5f), y(6.4f), x(21.5f), y(11.25f))
                cubicTo(x(21.5f), y(16.1f), x(17.25f), y(20f), x(12f), y(20f))
                cubicTo(x(10.9f), y(20f), x(9.85f), y(19.83f), x(8.87f), y(19.52f))
                cubicTo(x(7.3f), y(20.9f), x(5.2f), y(21.55f), x(3.05f), y(21.45f))
                cubicTo(x(4.35f), y(20.55f), x(5.2f), y(19.3f), x(5.45f), y(17.85f))
                cubicTo(x(3.6f), y(16.25f), x(2.5f), y(13.87f), x(2.5f), y(11.25f))
                cubicTo(x(2.5f), y(6.4f), x(6.75f), y(2.5f), x(12f), y(2.5f))
                close()
            }
        }
    }
}


package com.affiliateo.sdk

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
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
import android.widget.ImageView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
 *     Affiliateo.showChatBubble()       the bubble on every screen
 *     Affiliateo.openChat(activity)     or open it from your own button
 *
 * Nothing shows until the app calls one of them: an app must not grow a
 * visible feature its store reviewer never saw. And nothing shows while the
 * business has the chat switched off for this app, which the SDK asks the
 * server about.
 *
 * The bubble is a view the SDK adds on top of each screen's content
 * (android.R.id.content) as it comes up, so a dialog or a new screen the app
 * opens covers it. The visitor's key to their thread (a token the server
 * hands out with their first message) is kept in the SDK's own
 * SharedPreferences, like everything else it keeps.
 */
internal object LiveChat {
    private const val LOG_TAG = "Affiliateo"
    private const val BUBBLE_TAG = "affiliateo_chat_bubble"
    /** The SDK's one SharedPreferences file (Affiliateo.kt OPT_OUT_PREFS). */
    private const val PREFS = "affiliateo"

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
    private var wantsBubble = false
    private var screenOpen = false

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
        if (env == null) return@onMain
        scope.launch {
            refreshConfig(force = config == null)
            placeBubble()
            refreshUnread(force = true)
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
                        if (conn.responseCode == 200) ChatRules.parseConfig(conn.inputStream.bufferedReader().readText())
                        else ChatRules.Config.OFF
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
        return answer
    }

    /** Replies the person has not seen, for the red number on the bubble. */
    private suspend fun refreshUnread(force: Boolean) {
        val e = env ?: return
        val ctx = Affiliateo.chatContext() ?: return
        val token = storedToken(ctx, e.appId) ?: return
        if (!force && System.currentTimeMillis() - unreadCheckedAt < ChatRules.UNREAD_MAX_AGE_MS) return
        unreadCheckedAt = System.currentTimeMillis()
        val count = withContext(Dispatchers.IO) {
            try {
                val conn = URL("${e.apiUrl}/api/live-chat/unread").openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.useCaches = false
                conn.setRequestProperty("Authorization", "Bearer $token")
                try {
                    if (conn.responseCode == 200) ChatRules.parseUnread(conn.inputStream.bufferedReader().readText()) else null
                } finally {
                    conn.disconnect()
                }
            } catch (err: Exception) {
                null
            }
        } ?: return
        // An open chat has just shown them everything.
        if (!screenOpen) setUnread(count)
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
        bubbleIn(resumed?.get())?.update(unread, config?.online == true)
    }

    // ── Token and position, in the SDK's SharedPreferences ───────────────

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun storedToken(context: Context, appId: String): String? =
        prefs(context).getString(ChatRules.tokenKey(appId), null)?.takeIf { ChatRules.isToken(it) }

    fun saveToken(context: Context, appId: String, token: String) {
        if (ChatRules.isToken(token)) prefs(context).edit().putString(ChatRules.tokenKey(appId), token).apply()
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

    private fun bubbleIn(activity: Activity?): ChatBubbleView? =
        activity?.findViewById<ViewGroup>(android.R.id.content)?.findViewWithTag(BUBBLE_TAG)

    /** Puts the bubble on the screen in front, or takes it off, to match what the app asked and what the server said. */
    private fun placeBubble() {
        val activity = resumed?.get() ?: return
        if (activity is ChatActivity || activity.isFinishing) return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val existing = content.findViewWithTag<ChatBubbleView>(BUBBLE_TAG)
        val cfg = config
        val e = env
        if (!wantsBubble || screenOpen || e == null || cfg == null || !cfg.enabled || !cfg.showBubble) {
            existing?.let { content.removeView(it) }
            return
        }
        val bubble = existing ?: ChatBubbleView(activity, e.appId) { open(activity) }.also {
            it.tag = BUBBLE_TAG
            content.addView(it, FrameLayout.LayoutParams(it.boxPx, it.boxPx, Gravity.BOTTOM or Gravity.END))
        }
        bubble.bringToFront()
        bubble.update(unread, cfg.online)
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
        )
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
        } catch (err: Exception) {
            screenOpen = false
            Log.w(LOG_TAG, "openChat: the chat screen did not open (${err.javaClass.simpleName})")
        }
    }

    fun chatOpened() = onMain { screenOpen = true }

    fun chatClosed() = onMain {
        screenOpen = false
        unread = 0
        placeBubble()
    }
}

/**
 * The floating chat bubble. Press, hold and move to drag it; it remembers
 * where it was left. The view is 4 dp bigger than the 56 dp circle on each
 * side so the red number and the green dot fit inside it.
 */
@SuppressLint("ViewConstructor")
internal class ChatBubbleView(
    context: Context,
    private val appId: String,
    private val onTap: () -> Unit,
) : FrameLayout(context) {
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Float) = (value * density).toInt()

    private val circlePx = dp(56f)
    private val padPx = dp(4f)
    val boxPx = circlePx + 2 * padPx

    private val pill = TextView(context)
    private val dot = View(context)

    /** Distance of the circle from the content's right and bottom edges, in dp, system bars included. Null until laid out. */
    private var offsetDp: Pair<Float, Float>? = null
    private var downX = 0f
    private var downY = 0f
    private var startOffset = Pair(0f, 0f)
    private var dragging = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        clipChildren = false
        clipToPadding = false
        isClickable = true
        isFocusable = true
        contentDescription = "Chat"
        // The bubble casts the shadow itself, shaped like the circle: a
        // shadow cast by a view inside it would be cut off square at this
        // view's edges.
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(padPx, padPx, padPx + circlePx, padPx + circlePx)
            }
        }
        elevation = dp(8f).toFloat()
        if (Build.VERSION.SDK_INT >= 28) {
            outlineSpotShadowColor = Color.parseColor("#1754D8")
            outlineAmbientShadowColor = Color.parseColor("#1754D8")
        }

        val art = ImageView(context)
        val png = Base64.decode(ChatBubbleImage.PNG_BASE64, Base64.DEFAULT)
        art.setImageBitmap(BitmapFactory.decodeByteArray(png, 0, png.size))
        art.scaleType = ImageView.ScaleType.FIT_XY
        art.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        art.clipToOutline = true
        art.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(art, LayoutParams(circlePx, circlePx).apply { leftMargin = padPx; topMargin = padPx })

        pill.setTextColor(Color.WHITE)
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        pill.typeface = Typeface.DEFAULT_BOLD
        pill.gravity = Gravity.CENTER
        pill.minWidth = dp(20f)
        pill.setPadding(dp(5f), 0, dp(5f), 0)
        pill.includeFontPadding = false
        pill.background = GradientDrawable().apply {
            setColor(Color.parseColor("#EF4444"))
            cornerRadius = dp(10f).toFloat()
            setStroke(dp(2f), Color.WHITE)
        }
        pill.visibility = GONE
        pill.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(pill, LayoutParams(LayoutParams.WRAP_CONTENT, dp(20f), Gravity.TOP or Gravity.END))

        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#22C55E"))
            setStroke(dp(2f), Color.WHITE)
        }
        dot.visibility = GONE
        addView(dot, LayoutParams(dp(16f), dp(16f)).apply { leftMargin = dp(2f); topMargin = dp(2f) })
    }

    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name

    fun update(unread: Int, online: Boolean) {
        val text = ChatRules.badgeText(unread)
        if (text != null) {
            pill.text = text
            pill.visibility = VISIBLE
            dot.visibility = GONE
            contentDescription = "Chat, $text"
        } else {
            pill.visibility = GONE
            dot.visibility = if (online) VISIBLE else GONE
            contentDescription = "Chat"
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestApplyInsets()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (offsetDp == null) post { move(LiveChat.savedOffset(context, appId) ?: defaultOffset()) }
    }

    /** How much of the system bars covers the content's bottom, right and top edges, in px. */
    private fun barOverlap(): IntArray {
        val parent = parent as? View ?: return intArrayOf(0, 0, 0)
        val insets = parent.rootWindowInsets ?: return intArrayOf(0, 0, 0)
        val bottom: Int
        val right: Int
        val top: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            bottom = bars.bottom; right = bars.right; top = bars.top
        } else {
            @Suppress("DEPRECATION")
            bottom = insets.systemWindowInsetBottom
            @Suppress("DEPRECATION")
            right = insets.systemWindowInsetRight
            @Suppress("DEPRECATION")
            top = insets.systemWindowInsetTop
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
        )
    }

    private fun defaultOffset(): Pair<Float, Float> {
        val (bottom, right) = barOverlap()
        return Pair(20f + right / density, 20f + bottom / density)
    }

    private fun clamp(point: Pair<Float, Float>): Pair<Float, Float> {
        val parent = parent as? View ?: return point
        val (bottom, right, top) = barOverlap()
        val width = parent.width / density
        val height = parent.height / density
        val minX = right / density + 8f
        val minY = bottom / density + 8f
        val maxX = (width - 56f - 8f).coerceAtLeast(minX)
        val maxY = (height - 56f - top / density - 8f).coerceAtLeast(minY)
        return Pair(point.first.coerceIn(minX, maxX), point.second.coerceIn(minY, maxY))
    }

    private fun move(point: Pair<Float, Float>) {
        val next = clamp(point)
        offsetDp = next
        val params = layoutParams as? MarginLayoutParams ?: return
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
                startOffset = offsetDp ?: defaultOffset()
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!dragging && Math.abs(dx) + Math.abs(dy) < slop) return true
                dragging = true
                move(Pair(startOffset.first - dx / density, startOffset.second - dy / density))
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    offsetDp?.let { LiveChat.saveOffset(context, appId, it.first, it.second) }
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

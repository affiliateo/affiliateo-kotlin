package com.affiliateo.sdk

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Live Chat's screen (4.14.0): Affiliateo's hosted chat page in a WebView, in
 * a sheet that slides up over the dimmed app (4.15.0; it was full screen).
 * The page's close button, Back and a tap on the app around it close it, and
 * links the business sends open in the browser when the person taps them.
 * Only this app's own chat page loads in it (4.15.4, review S18). Declared in
 * the library's manifest (a see-through theme, so the app shows around the
 * sheet), so the app adds nothing.
 */
class ChatActivity : Activity() {
    companion object {
        internal const val EXTRA_URL = "com.affiliateo.sdk.chat.URL"
        internal const val EXTRA_API_URL = "com.affiliateo.sdk.chat.API_URL"
        internal const val EXTRA_APP_ID = "com.affiliateo.sdk.chat.APP_ID"
        internal const val EXTRA_DARK = "com.affiliateo.sdk.chat.DARK"
        internal const val EXTRA_DEBUG = "com.affiliateo.sdk.chat.DEBUG"
        private const val SLIDE_MS = 250L
    }

    private lateinit var url: String
    private lateinit var apiUrl: String
    private lateinit var appId: String
    private lateinit var root: FrameLayout
    private lateinit var scrim: View
    private lateinit var sheet: FrameLayout
    private var webView: WebView? = null
    /** An older WebView's bridge key (4.15.4, review S18), handed to the chat page's main frame only; null with WebMessageListener. */
    private var fallbackKey: String? = null
    private var background = Color.WHITE
    private var leaving = false
    // The sign-out count when this screen opened (4.15.1): a key the page
    // hands over after a sign-out (or a switch of person) is not saved.
    private var openedAt = ChatSignOuts.current

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        val dark = intent.getBooleanExtra(EXTRA_DARK, false)
        super.onCreate(savedInstanceState)
        url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        apiUrl = intent.getStringExtra(EXTRA_API_URL) ?: return finish()
        appId = intent.getStringExtra(EXTRA_APP_ID) ?: return finish()
        // Recreated (Android ended the app while it was away): the first load
        // already happened, and the thread it started is the key the app saved.
        if (savedInstanceState != null) url = ChatRules.reloadUrl(url, LiveChat.storedToken(this, appId))
        LiveChat.chatOpened(this)

        background = if (dark) Color.parseColor("#111214") else Color.WHITE
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        root = FrameLayout(this)
        // The app around the sheet, dimmed; a tap there closes the chat.
        scrim = View(this).apply {
            setBackgroundColor(Color.argb(102, 0, 0, 0))
            setOnClickListener { finish() }
        }
        root.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val radius = dp(20).toFloat()
        sheet = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(this@ChatActivity.background)
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            // Taps inside the sheet never reach the dimmed app behind it.
            isClickable = true
        }
        // A tablet gets a narrower sheet in the middle.
        val width = if (resources.configuration.smallestScreenWidthDp >= 600) dp(600) else ViewGroup.LayoutParams.MATCH_PARENT
        root.addView(sheet, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        setContentView(root)
        fitInsideBars(dark)

        if (intent.getBooleanExtra(EXTRA_DEBUG, false)) WebView.setWebContentsDebuggingEnabled(true)
        buildWebView()

        // Slides up the first time; a recreated screen is simply there.
        if (savedInstanceState == null) {
            scrim.alpha = 0f
            scrim.animate().alpha(1f).setDuration(SLIDE_MS).start()
            sheet.post {
                sheet.translationY = sheet.height.toFloat()
                sheet.animate().translationY(0f).setDuration(SLIDE_MS).start()
            }
        }
    }

    /** Slides the sheet down, then closes. */
    override fun finish() {
        if (leaving || !::sheet.isInitialized || sheet.height == 0) {
            super.finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
            return
        }
        leaving = true
        scrim.animate().alpha(0f).setDuration(SLIDE_MS).start()
        sheet.animate().translationY(sheet.height.toFloat()).setDuration(SLIDE_MS).withEndAction {
            super.finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }.start()
    }

    /**
     * The sheet starts a little below the status bar and keeps its page above
     * the navigation bar and the keyboard, whether or not the app draws edge
     * to edge.
     */
    private fun fitInsideBars(dark: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = Color.TRANSPARENT
            // Light icons over the dimmed app at the top; the navigation bar sits on the page.
            val lightNav = WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(
                if (dark) 0 else lightNav,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or lightNav,
            )
            root.setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val keyboard = insets.getInsets(WindowInsets.Type.ime())
                (sheet.layoutParams as FrameLayout.LayoutParams).topMargin = bars.top + dp(12)
                sheet.setPadding(bars.left, 0, bars.right, maxOf(bars.bottom, keyboard.bottom))
                sheet.requestLayout()
                WindowInsets.CONSUMED
            }
        } else {
            // adjustResize (the manifest) makes room for the keyboard here.
            root.fitsSystemWindows = true
            (sheet.layoutParams as FrameLayout.LayoutParams).topMargin = dp(12)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView() {
        val web = WebView(this)
        web.setBackgroundColor(background)
        web.settings.javaScriptEnabled = true
        // The page keeps nothing on the phone (4.15.1): no copy of the
        // thread's key or of its last messages outlives a sign-out. The key
        // lives in the SDK's own storage only, which logout() clears.
        // (WebStorage.deleteOrigin would not clear localStorage, and
        // deleteAllData would wipe the app's other web content too.)
        web.settings.domStorageEnabled = false
        web.settings.setSupportMultipleWindows(false)
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        addBridge(web)
        web.webViewClient = object : WebViewClient() {
            /**
             * ChatRules.navigation decides (4.15.4, review S18): this app's
             * own chat page loads, frames stay on the chat's origin, and a
             * link leaves the sheet only when the person taps it, never for a
             * script or a redirect.
             */
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                when (ChatRules.navigation(request.url.toString(), request.isForMainFrame, request.hasGesture(), apiUrl, appId)) {
                    ChatRules.Navigation.LOAD -> false
                    ChatRules.Navigation.OPEN_OUTSIDE -> {
                        openOutside(request.url)
                        true
                    }
                    ChatRules.Navigation.BLOCK -> true
                }

            override fun onPageCommitVisible(view: WebView, url: String?) = giveKey(view, url)

            override fun onPageFinished(view: WebView, url: String?) = giveKey(view, url)

            /**
             * The page's process ended (memory pressure): start it again,
             * without fresh=1 and with the key the app saved since it opened
             * (the page keeps none itself). Not once closing or signed out.
             */
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (view === webView) {
                    sheet.removeView(view)
                    view.destroy()
                    webView = null
                    if (!isFinishing && !leaving && ChatSignOuts.current == openedAt) {
                        url = ChatRules.reloadUrl(url, LiveChat.storedToken(this@ChatActivity, appId))
                        buildWebView()
                    }
                }
                return true
            }
        }
        sheet.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        webView = web
        web.loadUrl(url)
        url = ChatRules.withoutFresh(url)
    }

    /**
     * window.AffiliateoChatBridge for the chat page. ONLY THE CHAT PAGE ITSELF
     * (4.15.2, review E15): where the WebView can, the bridge is offered to
     * the page's own origin only and answers its main frame only (androidx
     * WebMessageListener), so a frame inside the page cannot reach the SDK.
     *
     * An older WebView (4.15.4, review S18) gets plain JavaScript
     * interfaces, which Android puts in every frame. So the one that listens
     * answers only messages that carry a random key, and only the chat page's
     * main frame gets the key: giveKey runs ChatRules.fallbackBridgeScript
     * there, which gives the page a window.AffiliateoChatBridge that adds it.
     * A frame from another site cannot read the main frame, so it never
     * learns the key. Until then window.AffiliateoChatBridge is HostSign,
     * which does nothing: it only tells the page an app opened it, as before.
     */
    private fun addBridge(web: WebView) {
        val origin = ChatRules.pageOrigin(apiUrl)
        if (origin != null && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            fallbackKey = null
            WebViewCompat.addWebMessageListener(web, ChatRules.BRIDGE_NAME, setOf(origin)) { _, message, _, isMainFrame, _ ->
                val json = message.data
                if (isMainFrame && json != null) received(json)
            }
        } else {
            val key = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            fallbackKey = key
            web.addJavascriptInterface(HostSign(), ChatRules.BRIDGE_NAME)
            web.addJavascriptInterface(Bridge(this, key), ChatRules.FALLBACK_BRIDGE_NAME)
        }
    }

    /**
     * An older WebView (4.15.4, review S18): the chat page's main frame gets
     * window.AffiliateoChatBridge with the key, as soon as the page shows and
     * again once it has loaded (the same either time).
     */
    private fun giveKey(view: WebView, url: String?) {
        val key = fallbackKey ?: return
        if (view !== webView || url == null || !ChatRules.isChatPage(url, apiUrl, appId)) return
        val script = ChatRules.fallbackBridgeScript(key, apiUrl) ?: return
        view.evaluateJavascript(script, null)
    }

    internal fun received(json: String) {
        // Closing (a sign-out among others): a late token is not kept. Not
        // only once Android says so: the sheet slides down for 250 ms first
        // (leaving), and a sign-out since this screen opened counts at once.
        if (isFinishing || leaving || ChatSignOuts.current != openedAt) return
        // Only this app's own chat page may speak (4.15.4, review S18): never
        // another app's chat page, whose key would replace this person's.
        val current = webView?.url ?: return
        if (!ChatRules.isChatPage(current, apiUrl, appId)) return
        when (val message = ChatRules.parseHostMessage(json)) {
            is ChatRules.HostMessage.Token -> LiveChat.saveToken(this, appId, message.token)
            ChatRules.HostMessage.Read -> LiveChat.setUnread(0)
            ChatRules.HostMessage.Close -> finish()
            else -> {}
        }
    }

    private fun openOutside(target: Uri) {
        if (!ChatRules.opensOutside(target.toString())) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, target))
        } catch (e: ActivityNotFoundException) {
            // Nothing on the phone opens it.
        }
    }

    override fun onPause() {
        super.onPause()
        // Early, so the screen underneath gets its bubble back as it comes up.
        if (isFinishing) LiveChat.chatClosed()
    }

    override fun onDestroy() {
        webView?.let {
            // Only set once onCreate got that far.
            if (::sheet.isInitialized) sheet.removeView(it)
            it.destroy()
        }
        webView = null
        super.onDestroy()
    }

    /**
     * An older WebView's window.AffiliateoChatBridge until the chat page has
     * shown (4.15.4, review S18). The chat page reads its thread key from its
     * address only when an app opened it, which it tells by this name, so it
     * is there from the first line of the page, as before. Android puts it in
     * every frame, so it does nothing; the page's own replaces it.
     */
    private class HostSign {
        @Suppress("UNUSED_PARAMETER")
        @JavascriptInterface
        fun postMessage(json: String?) {
            // A frame inside the page, or the page before it has shown: nothing happens.
        }
    }

    /**
     * An older WebView's plain interface (window.__affiliateoChatNative),
     * called on a WebView thread by the page's window.AffiliateoChatBridge
     * with the key. Without the key (a frame inside the page), nothing happens.
     */
    private class Bridge(activity: ChatActivity, private val key: String) {
        private val ref = WeakReference(activity)

        @JavascriptInterface
        fun postMessage(given: String?, json: String?) {
            if (given == null || json == null) return
            if (!MessageDigest.isEqual(given.toByteArray(Charsets.UTF_8), key.toByteArray(Charsets.UTF_8))) return
            val activity = ref.get() ?: return
            activity.runOnUiThread { activity.received(json) }
        }
    }
}

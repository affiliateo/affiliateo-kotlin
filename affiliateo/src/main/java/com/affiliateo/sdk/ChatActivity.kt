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
import java.lang.ref.WeakReference

/**
 * Live Chat's screen (4.14.0): Affiliateo's hosted chat page in a WebView, in
 * a sheet that slides up over the dimmed app (4.15.0; it was full screen).
 * The page's close button, Back and a tap on the app around it close it, and
 * links the business sends open in the browser. Declared in the library's
 * manifest (a see-through theme, so the app shows around the sheet), so the
 * app adds nothing.
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
    private var background = Color.WHITE
    private var leaving = false

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        val dark = intent.getBooleanExtra(EXTRA_DARK, false)
        super.onCreate(savedInstanceState)
        url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        apiUrl = intent.getStringExtra(EXTRA_API_URL) ?: return finish()
        appId = intent.getStringExtra(EXTRA_APP_ID) ?: return finish()
        // Recreated (Android ended the app while it was away): the first load already happened.
        if (savedInstanceState != null) url = ChatRules.withoutFresh(url)
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
        web.settings.domStorageEnabled = true
        web.settings.setSupportMultipleWindows(false)
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.addJavascriptInterface(Bridge(this), ChatRules.BRIDGE_NAME)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val target = request.url.toString()
                if (!request.isForMainFrame || ChatRules.isChatPage(target, apiUrl)) return false
                openOutside(request.url)
                return true
            }

            /** The page's process ended (memory pressure): start it again, without fresh=1. The page keeps the thread's token in its own storage too. */
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (view === webView) {
                    sheet.removeView(view)
                    view.destroy()
                    webView = null
                    if (!isFinishing) buildWebView()
                }
                return true
            }
        }
        sheet.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        webView = web
        web.loadUrl(url)
        url = ChatRules.withoutFresh(url)
    }

    internal fun received(json: String) {
        // Closing (a sign-out among others): a late token is not kept.
        if (isFinishing) return
        // Only the chat page itself may speak.
        val current = webView?.url ?: return
        if (!ChatRules.isChatPage(current, apiUrl)) return
        when (val message = ChatRules.parseHostMessage(json)) {
            is ChatRules.HostMessage.Token -> LiveChat.saveToken(this, appId, message.token)
            ChatRules.HostMessage.Read -> LiveChat.setUnread(0)
            ChatRules.HostMessage.Close -> finish()
            else -> {}
        }
    }

    private fun openOutside(target: Uri) {
        val scheme = target.scheme?.lowercase() ?: return
        if (scheme !in setOf("http", "https", "mailto", "tel")) return
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

    /** window.AffiliateoChatBridge.postMessage(json), called on a WebView thread. */
    private class Bridge(activity: ChatActivity) {
        private val ref = WeakReference(activity)

        @JavascriptInterface
        fun postMessage(json: String) {
            val activity = ref.get() ?: return
            activity.runOnUiThread { activity.received(json) }
        }
    }
}

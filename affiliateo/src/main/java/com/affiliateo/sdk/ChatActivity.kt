package com.affiliateo.sdk

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
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
 * Live Chat's screen (4.14.0): Affiliateo's hosted chat page in a WebView,
 * full screen over the app. Its own header has the close button, Back closes
 * it too, and links the business sends open in the browser. Declared in the
 * library's manifest, so the app adds nothing.
 */
class ChatActivity : Activity() {
    companion object {
        internal const val EXTRA_URL = "com.affiliateo.sdk.chat.URL"
        internal const val EXTRA_API_URL = "com.affiliateo.sdk.chat.API_URL"
        internal const val EXTRA_APP_ID = "com.affiliateo.sdk.chat.APP_ID"
        internal const val EXTRA_DARK = "com.affiliateo.sdk.chat.DARK"
        internal const val EXTRA_DEBUG = "com.affiliateo.sdk.chat.DEBUG"
    }

    private lateinit var url: String
    private lateinit var apiUrl: String
    private lateinit var appId: String
    private lateinit var root: FrameLayout
    private var webView: WebView? = null
    private var background = Color.WHITE

    override fun onCreate(savedInstanceState: Bundle?) {
        val dark = intent.getBooleanExtra(EXTRA_DARK, false)
        setTheme(if (dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        apiUrl = intent.getStringExtra(EXTRA_API_URL) ?: return finish()
        appId = intent.getStringExtra(EXTRA_APP_ID) ?: return finish()
        LiveChat.chatOpened()

        background = if (dark) Color.parseColor("#111214") else Color.WHITE
        root = FrameLayout(this)
        root.setBackgroundColor(background)
        setContentView(root)
        fitInsideBars(dark)

        if (intent.getBooleanExtra(EXTRA_DEBUG, false)) WebView.setWebContentsDebuggingEnabled(true)
        buildWebView()
    }

    /**
     * The page sits between the status bar and the navigation bar, and above
     * the keyboard, whether or not the app draws edge to edge.
     */
    private fun fitInsideBars(dark: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = Color.TRANSPARENT
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (dark) 0 else light, light)
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsets.CONSUMED
            }
        } else {
            // adjustResize (the manifest) makes room for the keyboard here.
            root.fitsSystemWindows = true
            @Suppress("DEPRECATION")
            window.statusBarColor = background
            if (!dark) {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
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

            /** The page's process ended (memory pressure): start it again. The page keeps the thread's token in its own storage too. */
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (view === webView) {
                    root.removeView(view)
                    view.destroy()
                    webView = null
                    if (!isFinishing) buildWebView()
                }
                return true
            }
        }
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        webView = web
        web.loadUrl(url)
    }

    internal fun received(json: String) {
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
            if (::root.isInitialized) root.removeView(it)
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

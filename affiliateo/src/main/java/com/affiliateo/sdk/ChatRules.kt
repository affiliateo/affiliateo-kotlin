package com.affiliateo.sdk

import org.json.JSONObject
import java.net.URLEncoder

/**
 * The parts of Live Chat (4.14.0) that need no screen, kept apart so they run
 * in plain JVM unit tests. The screens are in Chat.kt and ChatActivity.kt.
 *
 * The chat SCREEN is Affiliateo's hosted page (/embed/chat/{app}) in a
 * WebView, the same page the website bubble and the React Native, Swift and
 * Flutter SDKs open, so it looks and behaves the same everywhere. Everything
 * about the person rides in the URL fragment, which never reaches a server
 * log.
 */
internal object ChatRules {
    /** How long a config answer counts before the next foreground asks again. */
    const val CONFIG_MAX_AGE_MS = 60_000L
    /** How long an unread count counts before the next foreground asks again. */
    const val UNREAD_MAX_AGE_MS = 30_000L
    /** The name the chat page posts to: window.AffiliateoChatBridge.postMessage. */
    const val BRIDGE_NAME = "AffiliateoChatBridge"

    /** What Live Chat needs to know about this install. The ids are labels for the business's inbox, never credentials. */
    data class Env(
        val appId: String,
        val apiUrl: String,
        val deviceId: String?,
        val visitorId: String?,
        val userId: String?,
        val optedOut: Boolean,
        val debug: Boolean,
    )

    /** What /api/live-chat/config answered. */
    data class Config(
        /** The business has the chat on for this app. */
        val enabled: Boolean,
        /** The bubble should show (on, and not hidden by "only while someone is online"). */
        val showBubble: Boolean,
        /** Someone on the business's team is online right now. */
        val online: Boolean,
    ) {
        companion object {
            val OFF = Config(enabled = false, showBubble = false, online = false)
        }
    }

    /** What the chat page tells the app. */
    sealed class HostMessage {
        object Ready : HostMessage()
        /** The server opened a thread and handed out its token: keep it. */
        data class Token(val token: String) : HostMessage()
        /** The person saw every reply. */
        object Read : HostMessage()
        /** The page's close button. */
        object Close : HostMessage()
        object Message : HostMessage()
    }

    // ── Storage, the same key names as the React Native SDK ───────────────

    fun tokenKey(appId: String) = "affiliateo_chat_token_$appId"
    fun positionKey(appId: String) = "affiliateo_chat_pos_$appId"

    // ── The token ─────────────────────────────────────────────────────────

    private val TOKEN = Regex("^lct_[A-Za-z0-9_-]{43}$")

    /** True for a string shaped like a token the server minted. */
    fun isToken(value: String?): Boolean = value != null && TOKEN.matches(value)

    // ── Server answers ────────────────────────────────────────────────────

    fun parseConfig(text: String): Config {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            return Config.OFF
        }
        if (!json.optBoolean("enabled", false)) return Config.OFF
        return Config(
            enabled = true,
            showBubble = json.optBoolean("show_bubble", false),
            online = json.optBoolean("online", false),
        )
    }

    fun parseUnread(text: String): Int? {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            return null
        }
        if (!json.has("unread")) return null
        val unread = json.optInt("unread", -1)
        return if (unread >= 0) unread else null
    }

    // ── The chat page ─────────────────────────────────────────────────────

    /**
     * The chat page for this app. The ids are left out while the person has
     * opted out of tracking; the token goes in only when there is one.
     */
    fun chatUrl(env: Env, token: String?, platform: String, language: String, theme: String?): String {
        val fragment = mutableListOf("p=$platform", "close=1")
        fun add(key: String, value: String?) {
            if (!value.isNullOrEmpty()) fragment.add("$key=${encode(value)}")
        }
        if (!env.optedOut) {
            add("d", env.deviceId)
            add("v", env.visitorId)
            add("u", env.userId)
        }
        if (isToken(token)) add("t", token)
        add("theme", theme)
        return "${base(env.apiUrl)}/embed/chat/${encode(env.appId)}?lang=${encode(language)}#${fragment.joinToString("&")}"
    }

    /**
     * Whether the WebView may load this address itself. Anything else is a
     * link the business sent, which opens in the browser so the chat stays.
     */
    fun isChatPage(url: String, apiUrl: String): Boolean =
        url == "about:blank" || url.startsWith("${base(apiUrl)}/embed/chat/")

    /** One message from the page: a JSON string such as {"type":"affiliateo:chat-token","token":"lct_..."}. */
    fun parseHostMessage(text: String): HostMessage? {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            return null
        }
        return when (json.optString("type")) {
            "affiliateo:chat-ready" -> HostMessage.Ready
            "affiliateo:chat-token" -> {
                val token = if (json.isNull("token")) null else json.optString("token")
                if (isToken(token)) HostMessage.Token(token!!) else null
            }
            "affiliateo:chat-read" -> HostMessage.Read
            "affiliateo:chat-close" -> HostMessage.Close
            "affiliateo:chat-message" -> HostMessage.Message
            else -> null
        }
    }

    /** The red number on the bubble: up to 9, then "9+". */
    fun badgeText(unread: Int): String? = when {
        unread <= 0 -> null
        unread > 9 -> "9+"
        else -> unread.toString()
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun base(apiUrl: String) = apiUrl.trimEnd('/')

    /** Like encodeURIComponent: a space is %20, never +. */
    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

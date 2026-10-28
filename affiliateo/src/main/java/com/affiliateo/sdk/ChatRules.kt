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
        /** The owner's corner, size and screens for the bubble. */
        val bubble: Bubble = Bubble.STANDARD,
    ) {
        companion object {
            val OFF = Config(enabled = false, showBubble = false, online = false)
        }
    }

    /** The owner's say over the bubble (the website's lib/live-chat/bubble-options.ts; KEEP IN STEP). */
    data class Bubble(
        /** bottom-right, bottom-left, top-right or top-left. */
        val position: String = "bottom-right",
        /** The circle on a tablet, in dp: 48, 56 or 64. */
        val size: Int = 56,
        /** all, only or except. */
        val pageMode: String = "all",
        /** Screen names, * matching anything. */
        val pageRules: List<String> = emptyList(),
        /** Set when the greeting counts as one unread message until the chat is first opened; a new greeting has a new id. */
        val greetingId: String? = null,
        /** The circle on a phone (smaller than size for the owner's "auto"). */
        val sizePhone: Int = size,
        /** "standard" (a solid circle) or "glass" (a see-through glass circle), tinted with [color]. */
        val style: String = "standard",
        /** The owner's circle color and the speech bubble's inside it, #rrggbb. */
        val color: String = "#1754d8",
        val iconColor: String = "#ffffff",
    ) {
        companion object {
            val STANDARD = Bubble()
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
    /** The server's last answer about the chat (4.15.0): the bubble shows from it at once on the next launch while a fresh one is asked for. */
    fun configKey(appId: String) = "affiliateo_chat_config_$appId"

    fun greetingKey(appId: String) = "affiliateo_chat_greeting_seen_$appId"

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
            bubble = parseBubble(json.optJSONObject("bubble")),
        )
    }

    /** The config's bubble, anything missing or odd read as the old fixed bubble. */
    fun parseBubble(b: JSONObject?): Bubble {
        if (b == null) return Bubble.STANDARD
        val position = b.optString("position").takeIf { it in setOf("bottom-left", "top-right", "top-left") } ?: "bottom-right"
        val size = b.optInt("size_points", 56).takeIf { it in 40..80 } ?: 56
        val mode = b.optString("page_mode").takeIf { it == "only" || it == "except" } ?: "all"
        val rules = mutableListOf<String>()
        b.optJSONArray("page_rules")?.let { array ->
            for (i in 0 until array.length()) (array.opt(i) as? String)?.let { rules.add(it) }
        }
        val greetingId = if (b.isNull("greeting_id")) null else b.optString("greeting_id").takeIf { it.isNotEmpty() }
        val phone = b.optInt("size_points_phone", size).takeIf { it in 40..80 } ?: size
        val style = if (b.optString("style") == "glass") "glass" else "standard"
        val color = b.optString("color").takeIf { HEX.matches(it) } ?: "#1754d8"
        val iconColor = b.optString("icon_color").takeIf { HEX.matches(it) } ?: "#ffffff"
        return Bubble(position, size, mode, rules, greetingId, phone, style, color, iconColor)
    }

    private val HEX = Regex("^#[0-9a-fA-F]{6}$")

    /** What the app's own code asks for (setChatBubbleLook, 4.15.0); each set field wins over the dashboard's choice, each null keeps it. */
    data class BubbleLook(
        val color: String? = null,
        val iconColor: String? = null,
        val style: String? = null,
        val position: String? = null,
        /** One size for phones and tablets alike, in dp. */
        val sizeDp: Int? = null,
    )

    /** The dashboard's bubble with the app's own choices on top. */
    fun applying(look: BubbleLook, bubble: Bubble): Bubble = bubble.copy(
        color = look.color ?: bubble.color,
        iconColor = look.iconColor ?: bubble.iconColor,
        style = look.style ?: bubble.style,
        position = look.position ?: bubble.position,
        size = look.sizeDp ?: bubble.size,
        sizePhone = look.sizeDp ?: bubble.sizePhone,
    )

    /** A color as the code may write it ("#7C3AED", "7c3aed", "#fa0") as #rrggbb, lowercase; null for anything else. */
    fun hex(value: String): String? {
        var v = value.trim().lowercase().removePrefix("#")
        if (!v.all { it in '0'..'9' || it in 'a'..'f' }) return null
        if (v.length == 3) v = v.map { "$it$it" }.joinToString("")
        return if (v.length == 6) "#$v" else null
    }

    /** A #rrggbb color as an Android color int (opaque); Affiliateo's blue for anything else. */
    fun colorInt(hex: String): Int {
        if (!HEX.matches(hex)) return 0xFF1754D8.toInt()
        return (0xFF000000 or hex.substring(1).toLong(16)).toInt()
    }

    /** The red number and the green dot for a circle of `size` dp, scaled from the 56 dp bubble's (the website's badgeMetrics; the same numbers). */
    data class BadgeMetrics(
        val pillHeight: Float,
        val pillFont: Float,
        val pillPadding: Float,
        val pillOffset: Float,
        val dot: Float,
        val dotOffset: Float,
        val border: Float,
    )

    fun badgeMetrics(size: Int): BadgeMetrics {
        val s = size / 56.0
        fun round(v: Double) = Math.round(v).toFloat()
        return BadgeMetrics(
            pillHeight = round(20 * s),
            pillFont = Math.round(11 * s * 2) / 2f,
            pillPadding = round(5 * s),
            pillOffset = round(4 * s),
            dot = round(16 * s),
            dotOffset = round(2 * s),
            border = if (s < 1) 1.5f else 2f,
        )
    }

    // ── Which screens ─────────────────────────────────────────────────────

    /** A name compared without case and without a trailing slash ("/" stays). */
    private fun comparable(value: String): String {
        val v = value.trim().lowercase()
        return if (v.length > 1 && v.endsWith("/")) v.dropLast(1) else v
    }

    /**
     * Whether one rule matches the current screen (the name the app last
     * passed to page()). * matches any run of characters; a rule ending in
     * slash-star ("/blog/" then a star) also matches the part in front of it.
     */
    fun pageRuleMatches(rule: String, place: String): Boolean {
        val r = comparable(rule)
        if (r.isEmpty()) return false
        val p = comparable(place)
        if (r.endsWith("/*") && comparable(r.dropLast(2).ifEmpty { "/" }) == p) return true
        val pattern = r.split("*").joinToString(".*") { Regex.escape(it) }
        return Regex("^$pattern$", RegexOption.DOT_MATCHES_ALL).matches(p)
    }

    /** Whether the bubble shows on this screen. An unknown screen (null) shows nothing under "only" and the bubble under "except". */
    fun bubbleShowsOn(mode: String, rules: List<String>, place: String?): Boolean {
        if (mode != "only" && mode != "except") return true
        val hit = place != null && rules.any { pageRuleMatches(it, place) }
        return if (mode == "only") hit else !hit
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
     * Without one, fresh=1 tells the page to drop a token it kept itself (the
     * last person's, after a sign-out) and start a new thread.
     */
    fun chatUrl(env: Env, token: String?, platform: String, language: String, theme: String?, colors: Pair<String, String>? = null): String {
        // sheet=1: the page slides up as a sheet (4.15.0) and keeps room at its top.
        val fragment = mutableListOf("p=$platform", "close=1", "sheet=1")
        fun add(key: String, value: String?) {
            if (!value.isNullOrEmpty()) fragment.add("$key=${encode(value)}")
        }
        if (!env.optedOut) {
            add("d", env.deviceId)
            add("v", env.visitorId)
            add("u", env.userId)
        }
        if (isToken(token)) add("t", token) else fragment.add("fresh=1")
        add("theme", theme)
        // The colors the app's own code set (the page colors the person's messages and the send arrow with them, over the dashboard's).
        if (colors != null) {
            add("c", colors.first)
            add("ic", colors.second)
        }
        return "${base(env.apiUrl)}/embed/chat/${encode(env.appId)}?lang=${encode(language)}#${fragment.joinToString("&")}"
    }

    /** The same page without fresh=1. It is for the first load only: a reload must keep the thread the page has started since. */
    fun withoutFresh(url: String): String {
        val hash = url.indexOf('#')
        if (hash < 0) return url
        return url.substring(0, hash + 1) + url.substring(hash + 1).split("&").filter { it != "fresh=1" }.joinToString("&")
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

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
    /**
     * An older WebView's plain JavaScript interface (4.15.4, review S18). It
     * reaches every frame, so it answers only messages that carry the key
     * the SDK hands the chat page's main frame alone (fallbackBridgeScript).
     */
    const val FALLBACK_BRIDGE_NAME = "__affiliateoChatNative"

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
        /** The Live Chat app's name ("Copydraw"): the title of its notifications (4.17.0). */
        val appName: String? = null,
        /** The owner's greeting as written, and the versions they wrote for other languages ("es", "de"; the config's optional "greetings"), for the greeting notification (4.17.0). */
        val greeting: String? = null,
        val greetings: Map<String, String> = emptyMap(),
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
        /** Seconds between the bubble's wiggles (4.17.0); 0 is off, and so is a config without the field. */
        val wiggleSeconds: Int = 0,
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

    /**
     * Whether a sign-in switches this phone from one signed-in person to a
     * DIFFERENT one (4.15.1). Then the last person's chat thread is forgotten
     * first, as logout() does; signing in from anonymous (nobody before)
     * keeps the person's own thread.
     */
    fun switchesPerson(previous: String?, next: String): Boolean =
        !previous.isNullOrEmpty() && previous != next

    /** Where versions before 4.15.4 kept the thread key in SharedPreferences; ChatTokenStore moves a key found there. */
    fun tokenKey(appId: String) = "affiliateo_chat_token_$appId"

    /**
     * The file in the no-backup folder that holds this app's thread key
     * (4.15.4, review S18): the app id itself when it is plain letters,
     * digits, dots, dashes and underscores, its SHA-256 otherwise.
     */
    fun tokenFileName(appId: String): String {
        val plain = appId.isNotEmpty() && appId.length <= 100 && appId.all { it.isLetterOrDigit() && it.code < 128 || it in "._-" }
        if (plain) return "chat_token_$appId"
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(appId.toByteArray(Charsets.UTF_8))
        return "chat_token_" + digest.joinToString("") { "%02x".format(it) }
    }
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
            appName = textOf(json.optJSONObject("app"), "name"),
            greeting = textOf(json, "greeting"),
            greetings = parseGreetings(json.optJSONObject("greetings")),
        )
    }

    /** A text field that is there and not empty (a JSON null reads as nothing, never as the word "null"). */
    private fun textOf(json: JSONObject?, key: String): String? {
        if (json == null || json.isNull(key)) return null
        return (json.opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** The greeting's versions by language, as the config carries them: {"es":"Hola","de":"Hallo"}. */
    private fun parseGreetings(json: JSONObject?): Map<String, String> {
        if (json == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (key in json.keys()) textOf(json, key)?.let { out[key.lowercase()] = it }
        return out
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
        return Bubble(position, size, mode, rules, greetingId, phone, style, color, iconColor, wiggleSeconds(b.optInt("wiggle_seconds", 0)))
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
        /** The chat's send button and the person's own messages. */
        val sendColor: String? = null,
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

    /** The message box text as the code may pass it: on one line, trimmed, up to 60 characters; null when empty. */
    fun placeholder(text: String?): String? {
        val clean = (text ?: "").split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        return if (clean.isEmpty()) null else clean.take(60)
    }

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

    /**
     * Whether someone who answers THIS person is online, the affiliate who
     * brought them included (4.15.0); null when the answer leaves it out (no
     * affiliate brought them: the config's online decides).
     */
    fun parseUnreadOnline(text: String): Boolean? {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            return null
        }
        return if (json.has("online") && !json.isNull("online")) json.optBoolean("online") else null
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

    /**
     * Whether the bubble belongs on the app's pages at all: the app asked for
     * it and the chat is on with its bubble showing. A config that says the
     * chat is off (a fresh read or the app channel) takes it away.
     */
    fun showsBubble(wanted: Boolean, config: Config?): Boolean =
        wanted && config != null && config.enabled && config.showBubble

    // ── The live sockets ──────────────────────────────────────────────────

    /** What a frame on the thread's socket means for the red number. */
    enum class ThreadFrame {
        /** A reply from the team: one more unread. */
        TEAM_MESSAGE,

        /** The person read everything (in the chat, here or on another device). */
        VISITOR_READ,
    }

    /** The thread's socket (the visitor's key goes in its Authorization header, never in the address). */
    fun threadStreamUrl(apiUrl: String): String = "${wsBase(apiUrl)}/api/live-chat/stream"

    /**
     * The app's own live channel, for everyone with the bubble on screen: a
     * change of the owner's on/off switch or of a teammate's Online switch
     * arrives on it at once as a config frame. It needs no key.
     */
    fun appChannelUrl(apiUrl: String, appId: String): String = "${wsBase(apiUrl)}/api/live-chat/app-stream?app=${encode(appId)}"

    private fun wsBase(apiUrl: String) = base(apiUrl).replaceFirst(Regex("^http"), "ws")

    /** A frame on the thread's socket, as the Swift SDK reads it; null for anything else ("pong" included). */
    fun parseThreadFrame(text: String): ThreadFrame? {
        val frame = try {
            JSONObject(text)
        } catch (e: Exception) {
            return null
        }
        if (frame.optString("kind") != "live_chat") return null
        return when (frame.optString("event")) {
            "message" -> if (frame.optJSONObject("message")?.optString("sender") == "team") ThreadFrame.TEAM_MESSAGE else null
            "read" -> if (frame.optString("by") == "visitor") ThreadFrame.VISITOR_READ else null
            else -> null
        }
    }

    /**
     * A config frame from the app channel: its config is exactly what
     * /api/live-chat/config answers, read with the same parser, paired with
     * the JSON to keep as the phone's saved answer. Null for any other frame.
     */
    fun parseAppFrame(text: String): Pair<Config, String>? {
        val frame = try {
            JSONObject(text)
        } catch (e: Exception) {
            return null
        }
        if (frame.optString("kind") != "live_chat" || frame.optString("event") != "config") return null
        val saved = frame.optJSONObject("config")?.toString() ?: return null
        return Pair(parseConfig(saved), saved)
    }

    /** Whether the app channel should be open: the app shows the bubble, it is in front, the chat is on, and the server has not refused it since the last config read. */
    fun appChannelWanted(wantsBubble: Boolean, inFront: Boolean, config: Config?, refused: Boolean): Boolean =
        wantsBubble && inFront && !refused && config?.enabled == true

    /**
     * A refusal of the app channel (no such app, or its chat is off): it is
     * not tried again until a config read says the chat is on. A full server
     * (429) or a timeout (408) is tried again later.
     */
    fun refusesAppChannel(status: Int?): Boolean = status != null && status in 400..499 && status != 408 && status != 429

    /**
     * The wait before try number [tries] (1 and up) to reach the app channel
     * again: 2 s doubling up to 60 s, plus up to a quarter more ([jitter] 0
     * to 1), so phones do not all come back at once.
     */
    fun appChannelDelayMs(tries: Int, jitter: Double): Long {
        val base = minOf(60_000L, 2_000L shl (tries.coerceIn(1, 7) - 1))
        return base + (base * 0.25 * jitter.coerceIn(0.0, 1.0)).toLong()
    }

    /** The wait before the thread's socket tries again, as the Swift SDK's: 2 s doubling up to 60 s. */
    fun threadRetryMs(tries: Int): Long = minOf(60_000L, 1_000L shl tries.coerceIn(1, 6))

    // ── The chat page ─────────────────────────────────────────────────────

    /**
     * The chat page for this app. The ids are left out while the person has
     * opted out of tracking; the token goes in only when there is one.
     * Without one, fresh=1 tells the page to drop a token it kept itself (the
     * last person's, after a sign-out) and start a new thread.
     */
    fun chatUrl(env: Env, token: String?, platform: String, language: String, theme: String?, sendColor: String? = null, placeholder: String? = null): String {
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
        // The send color the app's own code set (the page colors the send arrow and the person's messages with it, over the dashboard's).
        if (sendColor != null) add("sc", sendColor)
        // The message box text the app's own code set, in its own language.
        if (placeholder != null) add("ph", placeholder)
        return "${base(env.apiUrl)}/embed/chat/${encode(env.appId)}?lang=${encode(language)}#${fragment.joinToString("&")}"
    }

    /** The same page without fresh=1. It is for the first load only: a reload must keep the thread the page has started since. */
    fun withoutFresh(url: String): String {
        val hash = url.indexOf('#')
        if (hash < 0) return url
        return url.substring(0, hash + 1) + url.substring(hash + 1).split("&").filter { it != "fresh=1" }.joinToString("&")
    }

    /**
     * The page again after its WebView had to start over (4.15.1): without
     * fresh=1, and with the thread's key the app saved since the screen
     * opened, over any older one. The page keeps nothing itself (its DOM
     * storage is off), so a thread started on this screen comes back only
     * this way. No key saved: only fresh=1 goes.
     */
    fun reloadUrl(url: String, savedToken: String?): String {
        val page = withoutFresh(url)
        val token = savedToken?.takeIf { isToken(it) } ?: return page
        val hash = page.indexOf('#')
        if (hash < 0) return page
        val kept = page.substring(hash + 1).split("&").filter { it.isNotEmpty() && !it.startsWith("t=") }
        return page.substring(0, hash + 1) + (kept + "t=${encode(token)}").joinToString("&")
    }

    /**
     * Whether this is THIS app's own chat page (4.15.4, review S18): on the
     * chat's origin, at exactly /embed/chat/{appId}. Another app's chat page
     * is not, so it never loads in the sheet, and never hands over a key that
     * would replace this person's.
     */
    fun isChatPage(url: String, apiUrl: String, appId: String): Boolean {
        if (!isChatOrigin(url, apiUrl)) return false
        val expected = rawPath("${base(apiUrl)}/embed/chat/${encode(appId)}") ?: return false
        val path = rawPath(url) ?: return false
        return path == expected || path == "$expected/"
    }

    /** Whether the address is on the chat's origin: the scheme, host and port of apiUrl. */
    fun isChatOrigin(url: String, apiUrl: String): Boolean {
        val origin = originOf(base(apiUrl)) ?: return false
        return originOf(url) == origin
    }

    /** scheme://host[:port] of an http(s) address, the default port left out; null for anything else. */
    private fun originOf(url: String): String? {
        val uri = try {
            java.net.URI(url)
        } catch (e: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (scheme != "https" && scheme != "http") return null
        val port = when {
            uri.port == -1 -> -1
            scheme == "https" && uri.port == 443 -> -1
            scheme == "http" && uri.port == 80 -> -1
            else -> uri.port
        }
        return if (port == -1) "$scheme://$host" else "$scheme://$host:$port"
    }

    /** The path, decoded once (java.net.URI.getPath), or null. */
    private fun rawPath(url: String): String? = try {
        java.net.URI(url).path
    } catch (e: Exception) {
        null
    }

    // ── What the chat sheet loads (4.15.4, review S18) ─────────────────────

    /** What the chat sheet does with a navigation the page asks for. */
    enum class Navigation {
        /** It loads in the sheet. */
        LOAD,
        /** A link the person tapped: it opens in the browser, the mail app or the phone app. */
        OPEN_OUTSIDE,
        /** Nothing happens. */
        BLOCK,
    }

    /**
     * The sheet's rules for a navigation:
     * - the page itself (mainFrame) loads only this app's own chat page;
     * - a frame inside it loads only from the chat's origin (and Cloudflare's
     *   challenge frame, which only shows while Cloudflare checks a visitor
     *   before serving the chat page);
     * - anything else leaves the sheet only as a link the person TAPPED (web,
     *   email or phone), never for a script or a redirect.
     */
    fun navigation(url: String, mainFrame: Boolean, tapped: Boolean, apiUrl: String, appId: String): Navigation {
        if (!mainFrame) {
            val inside = url == "about:blank" || url == "about:srcdoc" || isChatOrigin(url, apiUrl) || isCloudflareChallenge(url)
            return if (inside) Navigation.LOAD else Navigation.BLOCK
        }
        if (url == "about:blank" || isChatPage(url, apiUrl, appId)) return Navigation.LOAD
        return if (tapped && opensOutside(url)) Navigation.OPEN_OUTSIDE else Navigation.BLOCK
    }

    /** The links the sheet hands to the phone: web pages, email and phone numbers. */
    fun opensOutside(url: String): Boolean {
        val scheme = url.substringBefore(':', "").lowercase()
        return scheme in setOf("http", "https", "mailto", "tel")
    }

    /** Cloudflare's own challenge frame (https://challenges.cloudflare.com). */
    fun isCloudflareChallenge(url: String): Boolean = originOf(url) == "https://challenges.cloudflare.com"

    /**
     * What the SDK runs in the chat page's main frame on an older WebView
     * (4.15.4, review S18): window.AffiliateoChatBridge for the page, which
     * passes each message to the plain interface with `key`. Frames inside
     * the page see the plain interface too, but never the key. The script
     * first checks the document it runs in is on the chat's origin, so the
     * key never reaches a page that took the chat page's place meanwhile.
     * Null when apiUrl has no http(s) origin.
     */
    fun fallbackBridgeScript(key: String, apiUrl: String): String? {
        require(key.isNotEmpty() && key.all { it in '0'..'9' || it in 'a'..'f' }) { "the bridge key is hex" }
        val origin = originOf(base(apiUrl)) ?: return null
        return "(function(){if(location.origin!==${jsString(origin)})return;" +
            "var k='$key',b=window.$FALLBACK_BRIDGE_NAME;if(!b)return;" +
            "window.$BRIDGE_NAME={postMessage:function(m){b.postMessage(k,String(m))}};})();"
    }

    /** A JavaScript string literal: letters and digits as they are, everything else escaped. */
    private fun jsString(value: String): String = buildString {
        append('\'')
        for (c in value) {
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9') append(c) else append("\\u%04x".format(c.code))
        }
        append('\'')
    }

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

    // ── The bubble's words (4.15.2) ───────────────────────────────────────

    /**
     * What TalkBack says for the bubble, in the person's language: the 16
     * languages Affiliateo speaks, English for any other (review E14). The same
     * words as the website's chat.js and the React Native and Swift SDKs.
     */
    data class ChatWords(
        val chat: String,
        val close: String,
        val unreadPattern: String,
        /** The name of the notifications' channel in Android's settings (4.17.0). */
        val supportMessages: String,
        /** A notification's line for a message with no words (4.17.0). */
        val sentMessage: String,
    ) {
        fun unread(count: Int): String = unreadPattern.replace("{n}", count.toString())
    }

    val CHAT_WORDS: Map<String, ChatWords> = mapOf(
        "en" to ChatWords("Chat", "Close chat", "Chat, {n} unread", "Support messages", "Sent you a message"),
        "ar" to ChatWords("الدردشة", "إغلاق الدردشة", "الدردشة، {n} غير مقروءة", "رسائل الدعم", "أرسل لك رسالة"),
        "de" to ChatWords("Chat", "Chat schließen", "Chat, {n} ungelesen", "Support-Nachrichten", "Hat dir eine Nachricht gesendet"),
        "es" to ChatWords("Chat", "Cerrar chat", "Chat, {n} sin leer", "Mensajes de ayuda", "Te envió un mensaje"),
        "fr" to ChatWords("Chat", "Fermer le chat", "Chat, {n} non lus", "Messages d'aide", "Vous a envoyé un message"),
        "hi" to ChatWords("चैट", "चैट बंद करें", "चैट, {n} अपठित", "सहायता संदेश", "आपको एक संदेश भेजा"),
        "it" to ChatWords("Chat", "Chiudi chat", "Chat, {n} non letti", "Messaggi di assistenza", "Ti ha inviato un messaggio"),
        "ja" to ChatWords("チャット", "チャットを閉じる", "チャット、未読 {n} 件", "サポートからのメッセージ", "メッセージが届きました"),
        "ko" to ChatWords("채팅", "채팅 닫기", "채팅, 읽지 않음 {n}개", "고객 지원 메시지", "메시지를 보냈습니다"),
        "nl" to ChatWords("Chat", "Chat sluiten", "Chat, {n} ongelezen", "Hulpberichten", "Heeft je een bericht gestuurd"),
        "pl" to ChatWords("Czat", "Zamknij czat", "Czat, nieprzeczytane: {n}", "Wiadomości od pomocy", "Masz nową wiadomość"),
        "pt" to ChatWords("Chat", "Fechar chat", "Chat, {n} não lidas", "Mensagens de ajuda", "Enviou uma mensagem para você"),
        "ru" to ChatWords("Чат", "Закрыть чат", "Чат, непрочитанных: {n}", "Сообщения поддержки", "Вам пришло сообщение"),
        "tr" to ChatWords("Sohbet", "Sohbeti kapat", "Sohbet, {n} okunmamış", "Destek mesajları", "Size bir mesaj gönderdi"),
        "vi" to ChatWords("Trò chuyện", "Đóng trò chuyện", "Trò chuyện, {n} chưa đọc", "Tin nhắn hỗ trợ", "Đã gửi cho bạn một tin nhắn"),
        "zh" to ChatWords("聊天", "关闭聊天", "聊天，{n} 条未读", "客服消息", "给你发了一条消息"),
    )

    /** The words for a language tag ("pt-BR", "zh-Hans-CN"): its first two letters. */
    fun chatWords(languageTag: String): ChatWords =
        CHAT_WORDS[languageTag.lowercase().take(2)] ?: CHAT_WORDS.getValue("en")

    // ── Where the bubble goes (4.15.2) ────────────────────────────────────

    /**
     * Activities of other libraries that open over the app's own screens: the
     * bubble never goes on them (review E16). Play Billing's purchase screen
     * is the one that matters; the bubble stays on the app's screen under it,
     * which that screen's window covers.
     */
    private val FOREIGN_ACTIVITY_PREFIXES = listOf(
        "com.android.billingclient.",
        "com.google.android.gms.",
        "com.google.android.play.",
        "com.google.android.finsky.",
        "androidx.browser.",
        "com.facebook.",
        "com.revenuecat.",
    )

    /** Whether the bubble may go on this activity (by its class name). */
    fun hostsBubble(activityClassName: String): Boolean =
        FOREIGN_ACTIVITY_PREFIXES.none { activityClassName.startsWith(it) }

    /**
     * The origin of the chat page (scheme, host and port), the only one whose
     * main frame may talk to the SDK (review E15); null for anything odd.
     */
    fun pageOrigin(apiUrl: String): String? {
        val uri = try {
            java.net.URI(base(apiUrl))
        } catch (e: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host ?: return null
        if (scheme != "https" && scheme != "http") return null
        return if (uri.port == -1) "$scheme://$host" else "$scheme://$host:${uri.port}"
    }

    // ── The wiggle (4.17.0) ───────────────────────────────────────────────

    /** The shortest wait between two wiggles, in seconds: anything shorter the code asks for becomes this. */
    const val WIGGLE_MIN_SECONDS = 3
    /** The longest wait between two wiggles, in seconds (an hour, as in the other SDKs). */
    const val WIGGLE_MAX_SECONDS = 3600
    /** How long one wiggle takes, in milliseconds. */
    const val WIGGLE_MS = 900L
    /**
     * The numbers of one wiggle, the same on the website's bubble and in the
     * React Native and Swift SDKs, so it looks alike everywhere (KEEP IN
     * STEP): how far the turn would go without dying down, in degrees; how
     * many times it swings left and right; how fast the swings die down; and
     * how much the bubble grows at the middle of the wiggle.
     */
    private const val WIGGLE_DEGREES = 16.0
    private const val WIGGLE_SWINGS = 3
    private const val WIGGLE_FADE = 1.2
    private const val WIGGLE_GROW = 0.06

    /**
     * Seconds between wiggles as asked for, by the dashboard's setting
     * (bubble.wiggle_seconds: 0, 10, 20 ... 60) or the app's own code
     * (setChatBubbleWiggle, any whole number): nothing, 0 or less is off, 1
     * and 2 become [WIGGLE_MIN_SECONDS].
     */
    fun wiggleSeconds(asked: Int?): Int = when {
        asked == null || asked <= 0 -> 0
        asked < WIGGLE_MIN_SECONDS -> WIGGLE_MIN_SECONDS
        else -> minOf(asked, WIGGLE_MAX_SECONDS)
    }

    /**
     * Seconds between wiggles on this phone, 0 for none: what the app's own
     * code set ([codeSeconds], already through [wiggleSeconds]; null while
     * the code has no say), else the dashboard's.
     */
    fun wiggleEvery(codeSeconds: Int?, dashboardSeconds: Int): Int = codeSeconds ?: wiggleSeconds(dashboardSeconds)

    /**
     * Whether the bubble wiggles now that its wait is over: only while it is
     * on screen in the window the person is looking at, the chat is closed,
     * the app is in front and no finger is on it, and never while the phone
     * has animations off (Remove animations, or the animator scale at 0).
     */
    fun wiggles(everySeconds: Int, bubbleOnScreen: Boolean, chatOpen: Boolean, appInFront: Boolean, touched: Boolean, animationsOn: Boolean): Boolean =
        everySeconds > 0 && bubbleOnScreen && !chatOpen && appInFront && !touched && animationsOn

    /**
     * How far the bubble is turned, in degrees, at [fraction] (0 to 1) of one
     * wiggle: 16 * sin(6 * PI * p) * (1 - p)^1.2. Three full swings left and
     * right that die down smoothly (about 14 degrees at first, about 1 at
     * the end), level at both ends. Read on every frame, never as a handful
     * of steps: that is what keeps it smooth.
     */
    fun wiggleDegrees(fraction: Float): Float {
        val p = fraction.coerceIn(0f, 1f).toDouble()
        if (p == 0.0 || p == 1.0) return 0f
        return (WIGGLE_DEGREES * Math.sin(2 * WIGGLE_SWINGS * Math.PI * p) * Math.pow(1 - p, WIGGLE_FADE)).toFloat()
    }

    /**
     * How big the bubble is at [fraction] of one wiggle, 1 being its own
     * size: 1 + 0.06 * sin(PI * p). It grows a touch, then settles back.
     */
    fun wiggleScale(fraction: Float): Float {
        val p = fraction.coerceIn(0f, 1f).toDouble()
        if (p == 0.0 || p == 1.0) return 1f
        return (1 + WIGGLE_GROW * Math.sin(Math.PI * p)).toFloat()
    }

    // ── Notifications (4.17.0) ────────────────────────────────────────────

    /** The channel a reply's notification shows under ("Support messages" in Android's settings). */
    const val NOTICE_CHANNEL = "affiliateo_chat"
    /** The longest a notification's line is, as the server cuts a push. */
    const val NOTICE_BODY_MAX = 180

    /** Kept while the server holds a registration for this phone: a fingerprint of what it took ([pushDeviceSignature]). */
    fun pushKey(appId: String) = "affiliateo_chat_push_$appId"
    /** Set from a sign-out until the next thread starts: this phone gets no pushes meanwhile. */
    fun pushHoldKey(appId: String) = "affiliateo_chat_push_hold_$appId"
    /** Set while the server still has to forget this phone (a sign-out or an opt-out it has not heard). */
    fun pushForgetKey(appId: String) = "affiliateo_chat_push_forget_$appId"
    /** The id of the greeting this phone already got a notification for. */
    fun greetingNotifiedKey(appId: String) = "affiliateo_chat_greeting_notified_$appId"
    /** The notifications whose tap already opened the chat. */
    const val OPENED_KEY = "affiliateo_chat_opened"

    /** A push token as the app hands it over: trimmed, its case untouched; null when empty or longer than the server takes (2048). */
    fun pushToken(token: String?): String? = token?.trim()?.takeIf { it.isNotEmpty() && it.length <= 2048 }

    /** How long the phone waits after a call that got no answer (offline) before it tries the server again. */
    const val PUSH_RETRY_MS = 5 * 60_000L

    /** What the server said to a call about this phone's token (ChatPushApi). */
    enum class PushAnswer {
        /** {"ok":true}. */
        OK,

        /** It answered, and did not take it: {"ok":false} (an app it does not know, a chat that is off), or a server without the route. */
        REFUSED,

        /** Too many requests, or the server failed: not asked again this launch. */
        BUSY,

        /** No answer at all (offline): worth another try later. */
        NONE,
    }

    /** What an HTTP answer to a call about this phone's token means. */
    fun pushAnswer(status: Int, body: String?): PushAnswer = when {
        status in 200..299 -> if (parseOk(body ?: "")) PushAnswer.OK else PushAnswer.REFUSED
        status == 408 || status == 429 || status >= 500 -> PushAnswer.BUSY
        else -> PushAnswer.REFUSED
    }

    /**
     * What registers this phone for the chat's pushes (POST
     * /api/live-chat/push-devices). Null when nothing may be sent: no token,
     * no device id, or the person has opted out of tracking (their chat
     * carries no device id then, so no push could find this phone).
     */
    fun pushDeviceBody(env: Env, token: String?, packageName: String, language: String): JSONObject? {
        val clean = pushToken(token) ?: return null
        if (env.optedOut) return null
        val device = env.deviceId?.takeIf { it.isNotEmpty() } ?: return null
        return JSONObject().apply {
            put("app", env.appId)
            put("platform", "android")
            put("token", clean)
            put("bundle_id", packageName)
            put("device_id", device)
            env.visitorId?.takeIf { it.isNotEmpty() }?.let { put("visitor_id", it) }
            env.userId?.takeIf { it.isNotEmpty() }?.let { put("user_id", it) }
            put("language", language)
        }
    }

    /**
     * A fingerprint of a registration (the SHA-256 of its fields in a fixed
     * order). The phone registers once per launch, and again in the same
     * launch only when the fingerprint changes (a new token, app, language
     * or person). The token itself is never kept on the phone by the SDK.
     */
    fun pushDeviceSignature(body: JSONObject): String {
        val fields = listOf("app", "platform", "token", "bundle_id", "device_id", "visitor_id", "user_id", "language")
        val text = fields.joinToString("\n") { key -> if (body.isNull(key)) "" else body.optString(key) }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** What makes the server forget this phone (DELETE /api/live-chat/push-devices). */
    fun pushForgetBody(appId: String, token: String): JSONObject = JSONObject().put("app", appId).put("token", token)

    /** Whether the server took a registration: {"ok":true}. An unknown app, or one with its chat off, answers {"ok":false}. */
    fun parseOk(text: String): Boolean = try {
        JSONObject(text).optBoolean("ok", false)
    } catch (e: Exception) {
        false
    }

    /** The server's push for a reply (FCM data, all text). */
    data class ChatPush(val title: String?, val body: String?, val messageId: String?, val conversationId: String?, val app: String?)

    /**
     * A push's data as a chat push; null when it is not one of Affiliateo's
     * (the app's own pushes pass through). Told by the "affiliateo_chat" key
     * alone: its "app" is the app's id on Affiliateo, which need not be the
     * name this app was configured with.
     */
    fun chatPush(data: Map<String, String>): ChatPush? {
        if (!data.containsKey("affiliateo_chat")) return null
        fun value(key: String) = data[key]?.trim()?.takeIf { it.isNotEmpty() }
        return ChatPush(value("title"), value("body"), value("message_id"), value("conversation_id"), value("app"))
    }

    /** A reply from the team as it arrives on the thread's socket, for the notification shown while the app is open. */
    data class TeamMessage(val id: String?, val body: String)

    /** The team's message in a frame from the thread's socket; null for any other frame. */
    fun teamMessage(text: String): TeamMessage? {
        if (parseThreadFrame(text) != ThreadFrame.TEAM_MESSAGE) return null
        val message = JSONObject(text).optJSONObject("message") ?: return null
        val id = if (message.isNull("id")) null else message.optString("id").takeIf { it.isNotEmpty() }
        val body = if (message.isNull("body")) "" else message.optString("body")
        return TeamMessage(id, body)
    }

    /**
     * Whether a push for a reply shows its notification: not while the chat
     * is open on screen, where the reply shows itself.
     */
    fun showsPush(chatOpen: Boolean, appInFront: Boolean): Boolean = !(chatOpen && appInFront)

    /**
     * Whether a reply that came in on the socket shows a notification of the
     * SDK's own: only while the app is in front with the chat closed, and
     * only on a phone the server sends no push to (it would show twice).
     */
    fun showsLocalNotice(pushRegistered: Boolean, chatOpen: Boolean, appInFront: Boolean): Boolean =
        !pushRegistered && !chatOpen && appInFront

    /** A notification's title: the Live Chat app's name, from the push, else the config, else the app's own name. */
    fun noticeTitle(pushTitle: String?, configName: String?, appLabel: String): String =
        pushTitle?.trim()?.takeIf { it.isNotEmpty() } ?: configName?.trim()?.takeIf { it.isNotEmpty() } ?: appLabel

    /**
     * A notification's line: the message, cut to [NOTICE_BODY_MAX] characters
     * (never through the middle of an emoji); "Sent you a message" in the
     * person's language for a message with no words.
     */
    fun noticeBody(body: String?, languageTag: String): String {
        val clean = (body ?: "").trim()
        if (clean.isEmpty()) return chatWords(languageTag).sentMessage
        if (clean.length <= NOTICE_BODY_MAX) return clean
        var end = NOTICE_BODY_MAX - 1
        if (Character.isHighSurrogate(clean[end - 1])) end--
        return clean.substring(0, end).trimEnd() + "\u2026"
    }

    /** The tag one message's notification goes by, so the same message never shows twice; null without an id. */
    fun noticeTag(messageId: String?): String? = messageId?.takeIf { it.isNotEmpty() }?.let { "message:$it" }

    /** The greeting notification's tag: there is only ever one. */
    const val GREETING_TAG = "greeting"

    /** The greeting a person in this language sees: their language's version, else the owner's main text; null when neither is written. */
    fun greetingText(config: Config, languageTag: String): String? =
        (config.greetings[languageTag.lowercase().take(2)] ?: config.greeting)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Whether the greeting goes out as a notification now: notifications are
     * allowed, the greeting still counts as unread ([pending]: the rule that
     * shows the red 1), the chat is not open, and this greeting has not been
     * sent as a notification on this phone before.
     */
    fun notifiesGreeting(allowed: Boolean, pending: Boolean, greetingId: String?, notifiedId: String?, text: String?, chatOpen: Boolean): Boolean =
        allowed && pending && !chatOpen && !greetingId.isNullOrEmpty() && greetingId != notifiedId && !text.isNullOrEmpty()

    /** Whether a notification's tap (its [nonce]) already opened the chat, going by the saved list. */
    fun wasOpened(saved: String?, nonce: String): Boolean = openedList(saved).contains(nonce)

    /** The saved list with [nonce] added: the last [keep] taps, as JSON. */
    fun rememberOpened(saved: String?, nonce: String, keep: Int = 20): String {
        val list = (openedList(saved).filter { it != nonce } + nonce).takeLast(keep.coerceAtLeast(1))
        return org.json.JSONArray(list).toString()
    }

    private fun openedList(saved: String?): List<String> {
        if (saved.isNullOrEmpty()) return emptyList()
        return try {
            val array = org.json.JSONArray(saved)
            (0 until array.length()).mapNotNull { array.opt(it) as? String }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun base(apiUrl: String) = apiUrl.trimEnd('/')

    /** Like encodeURIComponent: a space is %20, never +. */
    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

/**
 * Counts sign-outs and switches of person (4.15.1), readable from any thread.
 * The chat screen saves a thread key the page hands over only while the count
 * is still the one it opened with, so a key can never outlive a sign-out,
 * including during the screen's 250 ms slide-down, when isFinishing is still
 * false. LiveChat.forget() bumps it before anything else.
 */
internal object ChatSignOuts {
    private val count = java.util.concurrent.atomic.AtomicInteger(0)
    val current: Int get() = count.get()
    fun bump() {
        count.incrementAndGet()
    }
}

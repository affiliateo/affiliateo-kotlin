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
    data class ChatWords(val chat: String, val close: String, val unreadPattern: String) {
        fun unread(count: Int): String = unreadPattern.replace("{n}", count.toString())
    }

    val CHAT_WORDS: Map<String, ChatWords> = mapOf(
        "en" to ChatWords("Chat", "Close chat", "Chat, {n} unread"),
        "ar" to ChatWords("الدردشة", "إغلاق الدردشة", "الدردشة، {n} غير مقروءة"),
        "de" to ChatWords("Chat", "Chat schließen", "Chat, {n} ungelesen"),
        "es" to ChatWords("Chat", "Cerrar chat", "Chat, {n} sin leer"),
        "fr" to ChatWords("Chat", "Fermer le chat", "Chat, {n} non lus"),
        "hi" to ChatWords("चैट", "चैट बंद करें", "चैट, {n} अपठित"),
        "it" to ChatWords("Chat", "Chiudi chat", "Chat, {n} non letti"),
        "ja" to ChatWords("チャット", "チャットを閉じる", "チャット、未読 {n} 件"),
        "ko" to ChatWords("채팅", "채팅 닫기", "채팅, 읽지 않음 {n}개"),
        "nl" to ChatWords("Chat", "Chat sluiten", "Chat, {n} ongelezen"),
        "pl" to ChatWords("Czat", "Zamknij czat", "Czat, nieprzeczytane: {n}"),
        "pt" to ChatWords("Chat", "Fechar chat", "Chat, {n} não lidas"),
        "ru" to ChatWords("Чат", "Закрыть чат", "Чат, непрочитанных: {n}"),
        "tr" to ChatWords("Sohbet", "Sohbeti kapat", "Sohbet, {n} okunmamış"),
        "vi" to ChatWords("Trò chuyện", "Đóng trò chuyện", "Trò chuyện, {n} chưa đọc"),
        "zh" to ChatWords("聊天", "关闭聊天", "聊天，{n} 条未读"),
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

package com.affiliateo.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class ChatRulesTest {
    private val token = "lct_" + "A".repeat(42) + "_"

    private fun env(optedOut: Boolean = false) = ChatRules.Env(
        appId = "my-app",
        apiUrl = "https://affiliateo.com/",
        deviceId = "android-abc",
        visitorId = "v_123",
        userId = "user 1&2",
        optedOut = optedOut,
        debug = false,
    )

    private fun fragment(url: String): Map<String, String> =
        URI(url).rawFragment.orEmpty().split("&").filter { it.isNotEmpty() }.associate {
            val kv = it.split("=", limit = 2)
            kv[0] to URLDecoder.decode(kv.getOrElse(1) { "" }, "UTF-8")
        }

    @Test
    fun tokenShape() {
        assertTrue(ChatRules.isToken(token))
        assertFalse(ChatRules.isToken(null))
        assertFalse(ChatRules.isToken("lct_short"))
        assertFalse(ChatRules.isToken(token + "x"))
        assertFalse(ChatRules.isToken("abc_" + "A".repeat(43)))
    }

    @Test
    fun chatPageCarriesTheIdsInTheFragment() {
        val url = ChatRules.chatUrl(env(), token, "android", "pt-BR", "dark")
        val uri = URI(url)
        assertEquals("affiliateo.com", uri.host)
        assertEquals("/embed/chat/my-app", uri.path)
        assertEquals("lang=pt-BR", uri.rawQuery)
        val parts = fragment(url)
        assertEquals("android", parts["p"])
        assertEquals("1", parts["close"])
        assertEquals("android-abc", parts["d"])
        assertEquals("v_123", parts["v"])
        assertEquals("user 1&2", parts["u"])
        assertEquals(token, parts["t"])
        assertEquals("dark", parts["theme"])
        // A space is %20 (never +) and & is escaped, not read as a separator.
        assertTrue(url.contains("u=user%201%262"))
    }

    @Test
    fun optedOutLeavesTheIdsOut() {
        val parts = fragment(ChatRules.chatUrl(env(optedOut = true), token, "android", "en", null))
        assertNull(parts["d"])
        assertNull(parts["v"])
        assertNull(parts["u"])
        assertEquals(token, parts["t"])
        assertNull(parts["theme"])
    }

    @Test
    fun noTokenNoT() {
        assertNull(fragment(ChatRules.chatUrl(env(), null, "android", "en", null))["t"])
        assertNull(fragment(ChatRules.chatUrl(env(), "lct_bad", "android", "en", null))["t"])
    }

    @Test
    fun onlyTheChatPageLoadsInside() {
        val api = "https://affiliateo.com"
        assertTrue(ChatRules.isChatPage("https://affiliateo.com/embed/chat/my-app?lang=en#p=android", api))
        assertTrue(ChatRules.isChatPage("about:blank", api))
        assertFalse(ChatRules.isChatPage("https://affiliateo.com/pricing", api))
        assertFalse(ChatRules.isChatPage("https://affiliateo.com.evil.example/embed/chat/x", api))
        assertFalse(ChatRules.isChatPage("https://example.com/", api))
    }

    @Test
    fun config() {
        assertEquals(
            ChatRules.Config(enabled = true, showBubble = false, online = true),
            ChatRules.parseConfig("""{"enabled":true,"show_bubble":false,"online":true}"""),
        )
        assertEquals(ChatRules.Config.OFF, ChatRules.parseConfig("""{"enabled":false,"show_bubble":true}"""))
        assertEquals(ChatRules.Config.OFF, ChatRules.parseConfig("not json"))
    }

    @Test
    fun unread() {
        assertEquals(3, ChatRules.parseUnread("""{"unread":3}"""))
        assertNull(ChatRules.parseUnread("""{"unread":-1}"""))
        assertNull(ChatRules.parseUnread("{}"))
    }

    @Test
    fun badge() {
        assertNull(ChatRules.badgeText(0))
        assertEquals("4", ChatRules.badgeText(4))
        assertEquals("9+", ChatRules.badgeText(12))
    }

    @Test
    fun messagesFromThePage() {
        assertEquals(ChatRules.HostMessage.Token(token), ChatRules.parseHostMessage("""{"type":"affiliateo:chat-token","token":"$token"}"""))
        assertNull(ChatRules.parseHostMessage("""{"type":"affiliateo:chat-token","token":"lct_bad"}"""))
        assertNull(ChatRules.parseHostMessage("""{"type":"affiliateo:chat-token","token":null}"""))
        assertEquals(ChatRules.HostMessage.Close, ChatRules.parseHostMessage("""{"type":"affiliateo:chat-close"}"""))
        assertEquals(ChatRules.HostMessage.Read, ChatRules.parseHostMessage("""{"type":"affiliateo:chat-read"}"""))
        assertEquals(ChatRules.HostMessage.Ready, ChatRules.parseHostMessage("""{"type":"affiliateo:chat-ready"}"""))
        assertEquals(ChatRules.HostMessage.Message, ChatRules.parseHostMessage("""{"type":"affiliateo:chat-message"}"""))
        assertNull(ChatRules.parseHostMessage("""{"type":"something-else"}"""))
        assertNull(ChatRules.parseHostMessage("42"))
    }
}

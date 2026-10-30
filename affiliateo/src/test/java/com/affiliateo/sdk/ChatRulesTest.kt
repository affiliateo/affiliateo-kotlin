package com.affiliateo.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    /** 4.15.1: a different signed-in person forgets the last person's thread; anonymous to signed-in keeps it. */
    @Test
    fun switchesPersonOnlyBetweenTwoSignedInPeople() {
        assertTrue(ChatRules.switchesPerson("user-a", "user-b"))
        assertFalse(ChatRules.switchesPerson("user-a", "user-a"))
        assertFalse(ChatRules.switchesPerson(null, "user-a"))
        assertFalse(ChatRules.switchesPerson("", "user-a"))
    }

    /** 4.15.1: the page keeps no key itself, so a reload gets the one the app saved since the screen opened. */
    @Test
    fun aReloadCarriesTheKeyTheAppSaved() {
        val first = ChatRules.chatUrl(env(), null, "android", "en", "dark")
        val again = ChatRules.reloadUrl(first, token)
        assertEquals(token, fragment(again)["t"])
        assertNull(fragment(again)["fresh"])
        assertEquals("dark", fragment(again)["theme"])
        assertEquals("user 1&2", fragment(again)["u"])
        // A newer key replaces the one the screen opened with.
        val newer = "lct_" + "B".repeat(43)
        val replaced = ChatRules.reloadUrl(ChatRules.chatUrl(env(), token, "android", "en", null), newer)
        assertEquals(newer, fragment(replaced)["t"])
        assertEquals(1, URI(replaced).rawFragment.split("&").count { it.startsWith("t=") })
        // No key saved (or not a key): only fresh=1 goes.
        assertEquals(ChatRules.withoutFresh(first), ChatRules.reloadUrl(first, null))
        assertEquals(ChatRules.withoutFresh(first), ChatRules.reloadUrl(first, "lct_bad"))
    }

    /** 4.15.1: a key handed over after a sign-out no longer matches the count the chat screen opened with. */
    @Test
    fun signOutCountMovesOnEverySignOut() {
        val openedAt = ChatSignOuts.current
        ChatSignOuts.bump()
        assertEquals(openedAt + 1, ChatSignOuts.current)
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
        assertNull(parts["fresh"])
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
    fun noTokenAsksForAFreshThread() {
        // After a sign-out the page must drop the token it kept itself.
        assertEquals("1", fragment(ChatRules.chatUrl(env(), null, "android", "en", null))["fresh"])
        assertEquals("1", fragment(ChatRules.chatUrl(env(), null, "android", "en", null))["sheet"])
        assertEquals("1", fragment(ChatRules.chatUrl(env(), "lct_bad", "android", "en", null))["fresh"])
        assertEquals("1", fragment(ChatRules.chatUrl(env(optedOut = true), null, "android", "en", null))["fresh"])
        assertNull(fragment(ChatRules.chatUrl(env(optedOut = true), token, "android", "en", null))["fresh"])
    }

    @Test
    fun aReloadKeepsTheThreadTheFirstLoadStarted() {
        val first = ChatRules.chatUrl(env(), null, "android", "en", "dark")
        val again = ChatRules.withoutFresh(first)
        assertNull(fragment(again)["fresh"])
        assertEquals("dark", fragment(again)["theme"])
        assertEquals("user 1&2", fragment(again)["u"])
        assertEquals(first.replace("&fresh=1", ""), again)
        val withToken = ChatRules.chatUrl(env(), token, "android", "en", null)
        assertEquals(withToken, ChatRules.withoutFresh(withToken))
    }

    /** 4.15.4 (review S18): only THIS app's own chat page counts, exactly. */
    @Test
    fun onlyThisAppsChatPageLoadsInside() {
        val api = "https://affiliateo.com/"
        fun page(url: String, appId: String = "my-app") = ChatRules.isChatPage(url, api, appId)
        assertTrue(page("https://affiliateo.com/embed/chat/my-app?lang=en#p=android"))
        assertTrue(page("https://affiliateo.com/embed/chat/my-app/"))
        assertTrue(page("https://AFFILIATEO.com:443/embed/chat/my-app"))
        // Another app's chat page would hand over its own key.
        assertFalse(page("https://affiliateo.com/embed/chat/other-app"))
        assertFalse(page("https://affiliateo.com/embed/chat/my-app-2"))
        assertFalse(page("https://affiliateo.com/embed/chat/my-app/more"))
        assertFalse(page("https://affiliateo.com/embed/chat/"))
        assertFalse(page("https://affiliateo.com/pricing"))
        assertFalse(page("about:blank"))
        // Not the chat's origin.
        assertFalse(page("http://affiliateo.com/embed/chat/my-app"))
        assertFalse(page("https://affiliateo.com:8443/embed/chat/my-app"))
        assertFalse(page("https://affiliateo.com.evil.example/embed/chat/my-app"))
        assertFalse(page("https://evil.example/embed/chat/my-app"))
        assertFalse(page("not a url"))
        // An app id that needs escaping matches its own escaped page.
        assertTrue(page("https://affiliateo.com/embed/chat/a%20b", "a b"))
        assertFalse(page("https://affiliateo.com/embed/chat/a%20c", "a b"))
        // A local server.
        assertTrue(ChatRules.isChatPage("http://10.0.2.2:3000/embed/chat/my-app", "http://10.0.2.2:3000", "my-app"))
        assertFalse(ChatRules.isChatPage("http://10.0.2.2:3001/embed/chat/my-app", "http://10.0.2.2:3000", "my-app"))
    }

    /** 4.15.4 (review S18): what the chat sheet loads, hands to the phone, or stops. */
    @Test
    fun whatTheSheetLoads() {
        val load = ChatRules.Navigation.LOAD
        val outside = ChatRules.Navigation.OPEN_OUTSIDE
        val block = ChatRules.Navigation.BLOCK
        fun nav(url: String, mainFrame: Boolean = true, tapped: Boolean = false) =
            ChatRules.navigation(url, mainFrame, tapped, "https://affiliateo.com", "my-app")
        // The page itself.
        assertEquals(load, nav("https://affiliateo.com/embed/chat/my-app#p=android"))
        assertEquals(load, nav("about:blank"))
        // A link the person tapped opens in the browser, the mail app or the phone app.
        assertEquals(outside, nav("https://example.com/offer", tapped = true))
        assertEquals(outside, nav("HTTP://example.com/", tapped = true))
        assertEquals(outside, nav("mailto:help@example.com", tapped = true))
        assertEquals(outside, nav("tel:+15551234567", tapped = true))
        // The same without a tap (a script or a redirect) goes nowhere.
        assertEquals(block, nav("https://example.com/offer"))
        assertEquals(block, nav("mailto:help@example.com"))
        assertEquals(block, nav("tel:+15551234567"))
        // Another app's chat page, or another page of the site, never loads in the sheet.
        assertEquals(block, nav("https://affiliateo.com/embed/chat/other-app"))
        assertEquals(block, nav("https://affiliateo.com/pricing"))
        assertEquals(outside, nav("https://affiliateo.com/embed/chat/other-app", tapped = true))
        // Other kinds of links never open, tapped or not.
        assertEquals(block, nav("intent://scan/#Intent;scheme=zxing;end", tapped = true))
        assertEquals(block, nav("javascript:alert(1)", tapped = true))
        assertEquals(block, nav("file:///sdcard/x", tapped = true))
        assertEquals(block, nav("content://contacts/people", tapped = true))
        assertEquals(block, nav("market://details?id=x", tapped = true))
        assertEquals(block, nav("data:text/html,hi", tapped = true))
        // Frames stay on the chat's origin (and Cloudflare's check), and never open anything.
        assertEquals(load, nav("https://affiliateo.com/embed/part", mainFrame = false))
        assertEquals(load, nav("about:blank", mainFrame = false))
        assertEquals(load, nav("about:srcdoc", mainFrame = false))
        assertEquals(load, nav("https://challenges.cloudflare.com/cdn-cgi/challenge-platform/h/b", mainFrame = false))
        assertEquals(block, nav("https://evil.example/", mainFrame = false))
        assertEquals(block, nav("https://evil.example/", mainFrame = false, tapped = true))
        assertEquals(block, nav("http://challenges.cloudflare.com/", mainFrame = false))
        assertEquals(block, nav("intent://x#Intent;end", mainFrame = false, tapped = true))
        assertEquals(block, nav("mailto:help@example.com", mainFrame = false, tapped = true))
    }

    /** 4.15.4 (review S18): the key's file in the no-backup folder, never a path of its own. */
    @Test
    fun theKeysFileName() {
        val hashed = Regex("chat_token_[0-9a-f]{64}")
        assertEquals("chat_token_my-app", ChatRules.tokenFileName("my-app"))
        assertEquals("chat_token_App_123.v2", ChatRules.tokenFileName("App_123.v2"))
        assertTrue(hashed.matches(ChatRules.tokenFileName("../../shared_prefs/affiliateo")))
        assertTrue(hashed.matches(ChatRules.tokenFileName("a/b")))
        assertTrue(hashed.matches(ChatRules.tokenFileName("")))
        assertTrue(hashed.matches(ChatRules.tokenFileName("caf\u00e9")))
        assertTrue(hashed.matches(ChatRules.tokenFileName("a".repeat(101))))
        assertEquals(ChatRules.tokenFileName("a b"), ChatRules.tokenFileName("a b"))
        assertFalse(ChatRules.tokenFileName("a b") == ChatRules.tokenFileName("a c"))
    }

    /** 4.15.4 (review S18): an older WebView's bridge answers only the key, which only the chat page's main frame gets. */
    @Test
    fun theOlderWebViewsBridgeScript() {
        val script = ChatRules.fallbackBridgeScript("0a1b2c", "https://affiliateo.com/")!!
        // It runs only in a document on the chat's origin.
        assertTrue(script.startsWith("(function(){if(location.origin!=='https\\u003a\\u002f\\u002faffiliateo\\u002ecom')return;"))
        assertTrue(script.contains("var k='0a1b2c',b=window.__affiliateoChatNative;if(!b)return;"))
        assertTrue(script.contains("window.AffiliateoChatBridge={postMessage:function(m){b.postMessage(k,String(m))}}"))
        assertTrue(ChatRules.fallbackBridgeScript("ff", "http://10.0.2.2:3000")!!.contains("'http\\u003a\\u002f\\u002f10\\u002e0\\u002e2\\u002e2\\u003a3000'"))
        assertNull(ChatRules.fallbackBridgeScript("ff", "file:///sdcard/x"))
        // Only a hex key goes into the script.
        for (bad in listOf("", "AB", "x'", "0a';alert(1);'")) {
            try {
                ChatRules.fallbackBridgeScript(bad, "https://affiliateo.com")
                fail("took $bad")
            } catch (e: IllegalArgumentException) {
                // Refused, as it should be.
            }
        }
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

    // The website's bubble-options.test.ts cases: a bubble must show on the
    // same pages and screens everywhere.
    @Test
    fun pageRules() {
        val cases = listOf(
            Triple("/pricing", "/pricing", true),
            Triple("/pricing", "/Pricing/", true),
            Triple("/pricing", "/pricing/plans", false),
            Triple("/blog/*", "/blog/my-post", true),
            Triple("/blog/*", "/blog/", true),
            Triple("/blog/*", "/blog", true),
            Triple("/blog/*", "/blogger", false),
            Triple("/*", "/", true),
            Triple("/blog*", "/blog", true),
            Triple("*/checkout", "/shop/checkout", true),
            Triple("/", "/", true),
            Triple("/", "/about", false),
            Triple("Settings", "settings", true),
            Triple("Paywall*", "PaywallAnnual", true),
            Triple("Paywall*", "Home", false),
            Triple("/a.b", "/axb", false),
            Triple("/(x)", "/(x)", true),
            Triple("", "/", false),
            Triple("   ", "/", false),
        )
        for ((rule, place, expected) in cases) {
            assertEquals("$rule against $place", expected, ChatRules.pageRuleMatches(rule, place))
        }
    }

    @Test
    fun bubbleShowsOn() {
        val rules = listOf("/checkout*", "Paywall*")
        assertTrue(ChatRules.bubbleShowsOn("all", rules, "/checkout"))
        assertTrue(ChatRules.bubbleShowsOn("all", emptyList(), null))
        assertTrue(ChatRules.bubbleShowsOn("only", rules, "/checkout/step-2"))
        assertFalse(ChatRules.bubbleShowsOn("only", rules, "/home"))
        assertFalse(ChatRules.bubbleShowsOn("only", rules, null))
        assertFalse(ChatRules.bubbleShowsOn("except", rules, "PaywallYearly"))
        assertTrue(ChatRules.bubbleShowsOn("except", rules, "Home"))
        assertTrue(ChatRules.bubbleShowsOn("except", rules, null))
    }

    @Test
    fun bubbleConfig() {
        val config = ChatRules.parseConfig(
            """{"enabled":true,"show_bubble":true,"online":false,"bubble":{"position":"top-left","size":"large","size_points":64,"page_mode":"except","page_rules":["Paywall*",3],"greeting_id":"e40c292c"}}""",
        )
        assertEquals(ChatRules.Bubble("top-left", 64, "except", listOf("Paywall*"), "e40c292c"), config.bubble)
        // An older server, or odd values: the old fixed bubble.
        val plain = ChatRules.parseConfig("""{"enabled":true,"show_bubble":true,"bubble":{"position":"middle","size_points":500,"page_mode":"x","greeting_id":null}}""")
        assertEquals(ChatRules.Bubble.STANDARD, plain.bubble)
        assertEquals(ChatRules.Bubble.STANDARD, ChatRules.parseConfig("""{"enabled":true}""").bubble)
    }

    @Test
    fun theAppsOwnLookWins() {
        val dashboard = ChatRules.parseConfig("""{"enabled":true,"bubble":{"position":"bottom-right","size_points":56,"size_points_phone":48}}""").bubble
        val mine = ChatRules.applying(ChatRules.BubbleLook(color = "#7c3aed", style = "glass", position = "top-left", sizeDp = 64), dashboard)
        assertEquals("#7c3aed", mine.color)
        assertEquals("#ffffff", mine.iconColor)
        assertEquals("glass", mine.style)
        assertEquals("top-left", mine.position)
        assertEquals(64, mine.size)
        assertEquals(64, mine.sizePhone)
        // Nothing set: the dashboard's bubble as it was.
        assertEquals(dashboard, ChatRules.applying(ChatRules.BubbleLook(), dashboard))
        assertEquals("#7c3aed", ChatRules.hex("#7C3AED"))
        assertEquals("#ffaa00", ChatRules.hex("fa0"))
        assertEquals(null, ChatRules.hex("purple"))
    }

    @Test
    fun colors() {
        val colored = ChatRules.parseConfig("""{"enabled":true,"bubble":{"color":"#7c3aed","icon_color":"#fef3c7"}}""").bubble
        assertEquals("#7c3aed", colored.color)
        assertEquals("#fef3c7", colored.iconColor)
        // Anything that is not #rrggbb keeps Affiliateo's blue and white.
        val odd = ChatRules.parseConfig("""{"enabled":true,"bubble":{"color":"purple","icon_color":"#fff"}}""").bubble
        assertEquals("#1754d8", odd.color)
        assertEquals("#ffffff", odd.iconColor)
        assertEquals(0xFF7C3AED.toInt(), ChatRules.colorInt("#7c3aed"))
    }

    @Test
    fun unreadCheckCanSayOnlineForThisPerson() {
        assertEquals(true, ChatRules.parseUnreadOnline("""{"unread":0,"online":true}"""))
        assertEquals(false, ChatRules.parseUnreadOnline("""{"unread":2,"online":false}"""))
        assertEquals(null, ChatRules.parseUnreadOnline("""{"unread":1}"""))
        assertEquals(3, ChatRules.parseUnread("""{"unread":3,"online":true}"""))
    }

    @Test
    fun boxTextSetInCodeGoesToTheChatPage() {
        val parts = fragment(ChatRules.chatUrl(env(), null, "android", "es", null, placeholder = ChatRules.placeholder("  Pregúntanos\n lo que sea ")))
        assertEquals("Pregúntanos lo que sea", parts["ph"])
        assertEquals(null, ChatRules.placeholder("   "))
        assertEquals(60, ChatRules.placeholder("a".repeat(80))?.length)
        assertEquals(null, fragment(ChatRules.chatUrl(env(), null, "android", "en", null))["ph"])
    }

    @Test
    fun sendColorSetInCodeGoesToTheChatPage() {
        val parts = fragment(ChatRules.chatUrl(env(), null, "android", "en", null, sendColor = "#007aff"))
        assertEquals("#007aff", parts["sc"])
        assertEquals(null, parts["c"])
        assertEquals(null, fragment(ChatRules.chatUrl(env(), null, "android", "en", null))["sc"])
    }

    @Test
    fun glassStyle() {
        assertEquals("glass", ChatRules.parseConfig("""{"enabled":true,"bubble":{"style":"glass"}}""").bubble.style)
        // Anything else, or a server from before the style, is the blue circle.
        assertEquals("standard", ChatRules.parseConfig("""{"enabled":true,"bubble":{"style":"neon"}}""").bubble.style)
        assertEquals("standard", ChatRules.parseConfig("""{"enabled":true,"bubble":{}}""").bubble.style)
    }

    @Test
    fun sizesAndBadges() {
        val auto = ChatRules.parseConfig("""{"enabled":true,"bubble":{"size":"auto","size_points":56,"size_points_phone":48}}""").bubble
        assertEquals(56, auto.size)
        assertEquals(48, auto.sizePhone)
        // A server from before the phone size: both are size_points.
        assertEquals(64, ChatRules.parseConfig("""{"enabled":true,"bubble":{"size":"large","size_points":64}}""").bubble.sizePhone)
        // The website's pinned numbers (bubble-options.test.ts).
        assertEquals(ChatRules.BadgeMetrics(20f, 11f, 5f, 4f, 16f, 2f, 2f), ChatRules.badgeMetrics(56))
        assertEquals(ChatRules.BadgeMetrics(17f, 9.5f, 4f, 3f, 14f, 2f, 1.5f), ChatRules.badgeMetrics(48))
        assertEquals(ChatRules.BadgeMetrics(23f, 12.5f, 6f, 5f, 18f, 2f, 2f), ChatRules.badgeMetrics(64))
    }

    // ── 4.15.2 ────────────────────────────────────────────────────────────

    @Test
    fun theBubbleSpeaksThePersonsLanguage() {
        assertEquals("Fermer le chat", ChatRules.chatWords("fr-FR").close)
        assertEquals("Chat, 3 não lidas", ChatRules.chatWords("pt-BR").unread(3))
        assertEquals("聊天", ChatRules.chatWords("zh-Hans-CN").chat)
        assertEquals(16, ChatRules.CHAT_WORDS.size)
    }

    @Test
    fun anyOtherLanguageIsEnglish() {
        assertEquals(ChatRules.chatWords("en"), ChatRules.chatWords("sv-SE"))
        assertEquals("Chat, 2 unread", ChatRules.chatWords("").unread(2))
    }

    @Test
    fun theBubbleNeverGoesOnAnotherLibrarysScreen() {
        assertFalse(ChatRules.hostsBubble("com.android.billingclient.api.ProxyBillingActivity"))
        assertFalse(ChatRules.hostsBubble("com.google.android.gms.auth.api.signin.internal.SignInHubActivity"))
        assertFalse(ChatRules.hostsBubble("androidx.browser.customtabs.CustomTabsActivity"))
        assertTrue(ChatRules.hostsBubble("com.nicogs.copydraw.MainActivity"))
        assertTrue(ChatRules.hostsBubble("com.example.app.ui.HomeActivity"))
    }

    @Test
    fun onlyTheChatPagesOwnOriginMayTalkToTheSdk() {
        assertEquals("https://affiliateo.com", ChatRules.pageOrigin("https://affiliateo.com/"))
        assertEquals("http://10.0.2.2:3000", ChatRules.pageOrigin("http://10.0.2.2:3000"))
        assertNull(ChatRules.pageOrigin("file:///sdcard/x"))
        assertNull(ChatRules.pageOrigin("not a url"))
    }
}

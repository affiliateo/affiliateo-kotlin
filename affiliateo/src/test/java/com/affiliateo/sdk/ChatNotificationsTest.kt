package com.affiliateo.sdk

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 4.17.0: the bubble's wiggle, a reply as a notification (the server's push,
 * or the SDK's own while the app is open), the phone's push registration and
 * the greeting's notification. The rules only; the screens are in Chat.kt and
 * ChatNotifications.kt.
 */
class ChatNotificationsTest {
    private fun env(optedOut: Boolean = false, deviceId: String? = "android-abc", visitorId: String? = "v_123", userId: String? = "user-1") = ChatRules.Env(
        appId = "my-app",
        apiUrl = "https://affiliateo.com",
        deviceId = deviceId,
        visitorId = visitorId,
        userId = userId,
        optedOut = optedOut,
        debug = false,
    )

    // ── The wiggle ────────────────────────────────────────────────────────

    @Test
    fun theDashboardsWiggleComesWithTheConfig() {
        assertEquals(30, ChatRules.parseConfig("""{"enabled":true,"bubble":{"wiggle_seconds":30}}""").bubble.wiggleSeconds)
        assertEquals(0, ChatRules.parseConfig("""{"enabled":true,"bubble":{"wiggle_seconds":0}}""").bubble.wiggleSeconds)
        // A config from before the setting, or an odd value: no wiggle.
        assertEquals(0, ChatRules.parseConfig("""{"enabled":true,"bubble":{"position":"top-left"}}""").bubble.wiggleSeconds)
        assertEquals(0, ChatRules.parseConfig("""{"enabled":true}""").bubble.wiggleSeconds)
        assertEquals(0, ChatRules.parseConfig("""{"enabled":true,"bubble":{"wiggle_seconds":-5}}""").bubble.wiggleSeconds)
        assertEquals(0, ChatRules.parseConfig("""{"enabled":true,"bubble":{"wiggle_seconds":"soon"}}""").bubble.wiggleSeconds)
        assertEquals(0, ChatRules.Bubble.STANDARD.wiggleSeconds)
    }

    @Test
    fun wiggleSecondsAsAsked() {
        assertEquals(0, ChatRules.wiggleSeconds(null))
        assertEquals(0, ChatRules.wiggleSeconds(0))
        assertEquals(0, ChatRules.wiggleSeconds(-10))
        // Never more often than every 3 seconds.
        assertEquals(3, ChatRules.wiggleSeconds(1))
        assertEquals(3, ChatRules.wiggleSeconds(2))
        assertEquals(3, ChatRules.wiggleSeconds(3))
        assertEquals(45, ChatRules.wiggleSeconds(45))
        assertEquals(ChatRules.WIGGLE_MAX_SECONDS, ChatRules.wiggleSeconds(Int.MAX_VALUE))
    }

    @Test
    fun theAppsOwnWiggleWinsOverTheDashboards() {
        // No say in code: the dashboard's.
        assertEquals(30, ChatRules.wiggleEvery(null, 30))
        assertEquals(0, ChatRules.wiggleEvery(null, 0))
        // Code says a number, or off.
        assertEquals(45, ChatRules.wiggleEvery(ChatRules.wiggleSeconds(45), 30))
        assertEquals(0, ChatRules.wiggleEvery(ChatRules.wiggleSeconds(0), 30))
        assertEquals(0, ChatRules.wiggleEvery(ChatRules.wiggleSeconds(null), 30))
        assertEquals(3, ChatRules.wiggleEvery(ChatRules.wiggleSeconds(1), 0))
    }

    @Test
    fun itWigglesOnlyWhenItMay() {
        fun wiggles(every: Int = 30, onScreen: Boolean = true, chatOpen: Boolean = false, inFront: Boolean = true, touched: Boolean = false, animations: Boolean = true) =
            ChatRules.wiggles(every, onScreen, chatOpen, inFront, touched, animations)
        assertTrue(wiggles())
        assertFalse(wiggles(every = 0))
        assertFalse(wiggles(onScreen = false))
        assertFalse(wiggles(chatOpen = true))
        assertFalse(wiggles(inFront = false))
        assertFalse(wiggles(touched = true))
        // Remove animations, or the animator scale at 0.
        assertFalse(wiggles(animations = false))
    }

    @Test
    fun oneWiggleIsThreeSmoothSwingsThatDieDown() {
        // The shared motion (the website and the other SDKs use the same): 900 ms,
        // 16 * sin(6 * PI * p) * (1 - p)^1.2 degrees.
        assertEquals(900L, ChatRules.WIGGLE_MS)
        // Level before, at the start, at the end and after.
        assertEquals(0f, ChatRules.wiggleDegrees(0f), 0f)
        assertEquals(0f, ChatRules.wiggleDegrees(1f), 0f)
        assertEquals(0f, ChatRules.wiggleDegrees(-1f), 0f)
        assertEquals(0f, ChatRules.wiggleDegrees(2f), 0f)
        val path = (0..900).map { ChatRules.wiggleDegrees(it / 900f) }
        // The first swing peaks at about 14 degrees, and it never turns past 16.
        assertTrue(path.max() in 13f..15f)
        assertTrue(path.all { Math.abs(it) <= 16f })
        // Three full swings (six peaks, right then left), each smaller than the one before, the last about 1 degree.
        val peaks = (1 until path.size - 1).filter { Math.abs(path[it]) > Math.abs(path[it - 1]) && Math.abs(path[it]) >= Math.abs(path[it + 1]) }
        assertEquals(6, peaks.size)
        assertEquals(listOf(1f, -1f, 1f, -1f, 1f, -1f), peaks.map { Math.signum(path[it]) })
        val sizes = peaks.map { Math.abs(path[it]) }
        assertEquals(sizes.sortedDescending(), sizes)
        assertEquals(path.max(), sizes.first(), 0f)
        assertTrue(sizes.last() in 0.5f..1.5f)
        // Smooth: from one millisecond to the next it never jumps (the old seven steps did).
        assertTrue((1 until path.size).all { Math.abs(path[it] - path[it - 1]) < 0.4f })
        // One formula, checked at a point: a twelfth of the way in.
        assertEquals((16 * Math.pow(11.0 / 12.0, 1.2)).toFloat(), ChatRules.wiggleDegrees(1f / 12f), 0.01f)
    }

    @Test
    fun theBubbleGrowsATouchInTheMiddleOfAWiggle() {
        assertEquals(1f, ChatRules.wiggleScale(0f), 0f)
        assertEquals(1f, ChatRules.wiggleScale(1f), 0f)
        assertEquals(1f, ChatRules.wiggleScale(-1f), 0f)
        assertEquals(1f, ChatRules.wiggleScale(2f), 0f)
        assertEquals(1.06f, ChatRules.wiggleScale(0.5f), 0.0001f)
        val sizes = (0..900).map { ChatRules.wiggleScale(it / 900f) }
        assertEquals(1.06f, sizes.max(), 0.0001f)
        assertTrue(sizes.all { it >= 1f })
    }

    // ── The phone's push registration ─────────────────────────────────────

    @Test
    fun registeringSendsWhatTheServerAsksFor() {
        val body = ChatRules.pushDeviceBody(env(), "  fcm-token-1  ", "com.example.app", "pt-BR")!!
        assertEquals("my-app", body.getString("app"))
        assertEquals("android", body.getString("platform"))
        assertEquals("fcm-token-1", body.getString("token"))
        assertEquals("com.example.app", body.getString("bundle_id"))
        assertEquals("android-abc", body.getString("device_id"))
        assertEquals("v_123", body.getString("visitor_id"))
        assertEquals("user-1", body.getString("user_id"))
        assertEquals("pt-BR", body.getString("language"))
        // "environment" is the iPhone's only.
        assertFalse(body.has("environment"))
        assertEquals(8, body.length())
    }

    @Test
    fun theOptionalIdsAreLeftOutWhenThereAreNone() {
        val body = ChatRules.pushDeviceBody(env(visitorId = null, userId = ""), "t", "com.example.app", "en")!!
        assertFalse(body.has("visitor_id"))
        assertFalse(body.has("user_id"))
        assertEquals("android-abc", body.getString("device_id"))
    }

    @Test
    fun nothingIsRegisteredWithoutATokenADeviceOrWhileOptedOut() {
        assertNull(ChatRules.pushDeviceBody(env(), "", "com.example.app", "en"))
        assertNull(ChatRules.pushDeviceBody(env(), "   ", "com.example.app", "en"))
        assertNull(ChatRules.pushDeviceBody(env(), null, "com.example.app", "en"))
        // Longer than the server takes.
        assertNull(ChatRules.pushDeviceBody(env(), "x".repeat(2049), "com.example.app", "en"))
        assertNull(ChatRules.pushDeviceBody(env(deviceId = null), "t", "com.example.app", "en"))
        assertNull(ChatRules.pushDeviceBody(env(deviceId = ""), "t", "com.example.app", "en"))
        // Opted out: the chat carries no device id, so no push could find this phone.
        assertNull(ChatRules.pushDeviceBody(env(optedOut = true), "t", "com.example.app", "en"))
        assertEquals("t", ChatRules.pushToken(" t "))
        assertNull(ChatRules.pushToken(null))
        // Firebase's token goes as it is: its case matters.
        assertEquals("cAbC:APA91b_Xy-Z", ChatRules.pushDeviceBody(env(), "cAbC:APA91b_Xy-Z", "com.example.app", "en")!!.getString("token"))
    }

    @Test
    fun theFingerprintChangesWithTheTokenTheLanguageAndThePerson() {
        fun signature(token: String = "t1", language: String = "en", userId: String? = "user-1", visitorId: String? = "v_123") =
            ChatRules.pushDeviceSignature(ChatRules.pushDeviceBody(env(userId = userId, visitorId = visitorId), token, "com.example.app", language)!!)
        val first = signature()
        assertEquals(first, signature())
        assertTrue(Regex("^[0-9a-f]{64}$").matches(first))
        assertNotEquals(first, signature(token = "t2"))
        assertNotEquals(first, signature(language = "es"))
        assertNotEquals(first, signature(userId = "user-2"))
        assertNotEquals(first, signature(userId = null))
        assertNotEquals(first, signature(visitorId = "v_999"))
        // The token is not in it.
        assertFalse(first.contains("t1"))
    }

    @Test
    fun forgettingNamesTheAppAndTheToken() {
        val body = ChatRules.pushForgetBody("my-app", "fcm-token-1")
        assertEquals("my-app", body.getString("app"))
        assertEquals("fcm-token-1", body.getString("token"))
        assertEquals(2, body.length())
    }

    @Test
    fun theServersAnswer() {
        assertTrue(ChatRules.parseOk("""{"ok":true}"""))
        // An unknown app, or one with its chat off.
        assertFalse(ChatRules.parseOk("""{"ok":false,"reason":"chat_off"}"""))
        assertFalse(ChatRules.parseOk("{}"))
        assertFalse(ChatRules.parseOk("not json"))
    }

    @Test
    fun whatAnAnswerMeansForTheNextTry() {
        assertEquals(ChatRules.PushAnswer.OK, ChatRules.pushAnswer(200, """{"ok":true}"""))
        // Not taken: never an error the SDK has to handle, and not asked again this launch.
        assertEquals(ChatRules.PushAnswer.REFUSED, ChatRules.pushAnswer(200, """{"ok":false}"""))
        assertEquals(ChatRules.PushAnswer.REFUSED, ChatRules.pushAnswer(200, null))
        // A server from before the route.
        assertEquals(ChatRules.PushAnswer.REFUSED, ChatRules.pushAnswer(404, null))
        assertEquals(ChatRules.PushAnswer.REFUSED, ChatRules.pushAnswer(400, null))
        // Too many requests, or the server failed: next launch.
        assertEquals(ChatRules.PushAnswer.BUSY, ChatRules.pushAnswer(429, null))
        assertEquals(ChatRules.PushAnswer.BUSY, ChatRules.pushAnswer(500, null))
        assertEquals(ChatRules.PushAnswer.BUSY, ChatRules.pushAnswer(503, null))
        assertEquals(5 * 60_000L, ChatRules.PUSH_RETRY_MS)
    }

    @Test
    fun theKeysNameTheApp() {
        assertEquals("affiliateo_chat_push_my-app", ChatRules.pushKey("my-app"))
        assertEquals("affiliateo_chat_push_hold_my-app", ChatRules.pushHoldKey("my-app"))
        assertEquals("affiliateo_chat_push_forget_my-app", ChatRules.pushForgetKey("my-app"))
        assertEquals("affiliateo_chat_greeting_notified_my-app", ChatRules.greetingNotifiedKey("my-app"))
    }

    // ── A reply as a notification ─────────────────────────────────────────

    @Test
    fun aChatPushIsToldFromTheAppsOwn() {
        val push = ChatRules.chatPush(
            mapOf("affiliateo_chat" to "1", "app" to "my-app", "conversation_id" to "c1", "message_id" to "m1", "title" to "Copydraw", "body" to "Hi there"),
        )!!
        assertEquals(ChatRules.ChatPush("Copydraw", "Hi there", "m1", "c1", "my-app"), push)
        // The app's own pushes pass through.
        assertNull(ChatRules.chatPush(mapOf("title" to "Sale", "body" to "50% off")))
        assertNull(ChatRules.chatPush(mapOf("affiliateo" to "1", "body" to "x")))
        assertNull(ChatRules.chatPush(emptyMap()))
        // The key alone tells it: the push's "app" is the app's id on Affiliateo, not the name the app was configured with.
        assertEquals("0b8f3c1e-app-uuid", ChatRules.chatPush(mapOf("affiliateo_chat" to "1", "app" to "0b8f3c1e-app-uuid"))!!.app)
        // A push with only the mark still counts; empty words read as none.
        assertEquals(ChatRules.ChatPush(null, null, null, null, null), ChatRules.chatPush(mapOf("affiliateo_chat" to "1", "body" to "  ")))
    }

    @Test
    fun theTeamsMessageFromTheSocket() {
        val frame = """{"kind":"live_chat","conversation_id":"c1","event":"message","message":{"id":"m7","sender":"team","body":"We are on it","created_at":"2026-11-11T10:00:00Z"}}"""
        assertEquals(ChatRules.TeamMessage("m7", "We are on it"), ChatRules.teamMessage(frame))
        // The person's own message, a "Chat closed" line, a read, a pong: none.
        assertNull(ChatRules.teamMessage("""{"kind":"live_chat","event":"message","message":{"id":"m8","sender":"visitor","body":"Hello"}}"""))
        assertNull(ChatRules.teamMessage("""{"kind":"live_chat","event":"message","message":{"id":"m9","sender":"system","body":"closed"}}"""))
        assertNull(ChatRules.teamMessage("""{"kind":"live_chat","event":"read","by":"visitor","at":"x"}"""))
        assertNull(ChatRules.teamMessage("pong"))
        // No id or no words still reads.
        assertEquals(ChatRules.TeamMessage(null, ""), ChatRules.teamMessage("""{"kind":"live_chat","event":"message","message":{"sender":"team"}}"""))
    }

    @Test
    fun aPushShowsUnlessTheChatIsOpenOnScreen() {
        assertTrue(ChatRules.showsPush(chatOpen = false, appInFront = true))
        assertTrue(ChatRules.showsPush(chatOpen = false, appInFront = false))
        // The chat is up but the person left the app: they are not reading it.
        assertTrue(ChatRules.showsPush(chatOpen = true, appInFront = false))
        assertFalse(ChatRules.showsPush(chatOpen = true, appInFront = true))
    }

    @Test
    fun theSdksOwnNotificationOnlyWhereNoPushComes() {
        assertTrue(ChatRules.showsLocalNotice(pushRegistered = false, chatOpen = false, appInFront = true))
        // The server pushes to this phone: showing it here too would be twice.
        assertFalse(ChatRules.showsLocalNotice(pushRegistered = true, chatOpen = false, appInFront = true))
        assertFalse(ChatRules.showsLocalNotice(pushRegistered = false, chatOpen = true, appInFront = true))
        assertFalse(ChatRules.showsLocalNotice(pushRegistered = false, chatOpen = false, appInFront = false))
    }

    @Test
    fun theTitleIsTheLiveChatAppsName() {
        assertEquals("Copydraw", ChatRules.noticeTitle("Copydraw", "From config", "App label"))
        assertEquals("From config", ChatRules.noticeTitle(null, "From config", "App label"))
        assertEquals("From config", ChatRules.noticeTitle("  ", " From config ", "App label"))
        assertEquals("App label", ChatRules.noticeTitle(null, null, "App label"))
        assertEquals("Copydraw", ChatRules.parseConfig("""{"enabled":true,"app":{"id":"a1","name":"Copydraw"}}""").appName)
        assertNull(ChatRules.parseConfig("""{"enabled":true,"app":{"id":"a1","name":null}}""").appName)
        assertNull(ChatRules.parseConfig("""{"enabled":true}""").appName)
    }

    @Test
    fun theLineIsTheMessageCutLikeThePush() {
        assertEquals("Hi there", ChatRules.noticeBody("  Hi there ", "en"))
        val long = "a".repeat(400)
        val cut = ChatRules.noticeBody(long, "en")
        assertEquals(ChatRules.NOTICE_BODY_MAX, cut.length)
        assertTrue(cut.endsWith("…"))
        assertEquals("a".repeat(180), ChatRules.noticeBody("a".repeat(180), "en"))
        // Never through the middle of an emoji (two chars in Kotlin).
        val emoji = "a".repeat(178) + "😀" + "b".repeat(50)
        val safe = ChatRules.noticeBody(emoji, "en")
        assertFalse(Character.isHighSurrogate(safe[safe.length - 2]))
        assertEquals("a".repeat(178) + "…", safe)
    }

    @Test
    fun aMessageWithNoWordsSaysSoInThePersonsLanguage() {
        assertEquals("Sent you a message", ChatRules.noticeBody(null, "en-US"))
        assertEquals("Sent you a message", ChatRules.noticeBody("   ", "sv-SE"))
        assertEquals("Te envió un mensaje", ChatRules.noticeBody("", "es-MX"))
        assertEquals("Support messages", ChatRules.chatWords("en").supportMessages)
        assertEquals("Destek mesajları", ChatRules.chatWords("tr-TR").supportMessages)
        // All 16 languages have both.
        for ((language, words) in ChatRules.CHAT_WORDS) {
            assertTrue(language, words.supportMessages.isNotBlank())
            assertTrue(language, words.sentMessage.isNotBlank())
        }
    }

    @Test
    fun oneMessageOneNotification() {
        assertEquals("message:m1", ChatRules.noticeTag("m1"))
        assertNull(ChatRules.noticeTag(null))
        assertNull(ChatRules.noticeTag(""))
        assertEquals("greeting", ChatRules.GREETING_TAG)
        assertEquals("affiliateo_chat", ChatRules.NOTICE_CHANNEL)
    }

    // ── The greeting's notification ───────────────────────────────────────

    @Test
    fun theGreetingInThePersonsLanguage() {
        val config = ChatRules.parseConfig(
            """{"enabled":true,"greeting":" Hi! How can we help? ","greetings":{"es":"¡Hola! ¿Cómo te ayudamos?","DE":"Hallo!","fr":"  ","it":null}}""",
        )
        assertEquals("Hi! How can we help?", config.greeting)
        assertEquals("¡Hola! ¿Cómo te ayudamos?", ChatRules.greetingText(config, "es-MX"))
        assertEquals("Hallo!", ChatRules.greetingText(config, "de"))
        // No version in their language (or an empty one): the owner's main text.
        assertEquals("Hi! How can we help?", ChatRules.greetingText(config, "ja-JP"))
        assertEquals("Hi! How can we help?", ChatRules.greetingText(config, "fr-FR"))
        assertEquals("Hi! How can we help?", ChatRules.greetingText(config, "it"))
        // No greeting written at all.
        assertNull(ChatRules.greetingText(ChatRules.parseConfig("""{"enabled":true,"greeting":null}"""), "en"))
        assertNull(ChatRules.greetingText(ChatRules.parseConfig("""{"enabled":true}"""), "en"))
        assertNull(ChatRules.parseConfig("""{"enabled":true,"greeting":null}""").greeting)
    }

    @Test
    fun theGreetingGoesOutOnce() {
        fun notifies(allowed: Boolean = true, pending: Boolean = true, id: String? = "g1", notified: String? = null, text: String? = "Hi!", chatOpen: Boolean = false) =
            ChatRules.notifiesGreeting(allowed, pending, id, notified, text, chatOpen)
        assertTrue(notifies())
        // Not before notifications are allowed.
        assertFalse(notifies(allowed = false))
        // Not once the chat was opened, or the person wrote (the red 1 is gone).
        assertFalse(notifies(pending = false))
        // Not while "Show as unread" is off (no id), or with no words.
        assertFalse(notifies(id = null))
        assertFalse(notifies(id = ""))
        assertFalse(notifies(text = null))
        assertFalse(notifies(text = ""))
        // Never twice for the same greeting; a new greeting goes out again.
        assertFalse(notifies(notified = "g1"))
        assertTrue(notifies(id = "g2", notified = "g1"))
        // Not while they are looking at the chat.
        assertFalse(notifies(chatOpen = true))
    }

    // ── A tap opens the chat once ─────────────────────────────────────────

    @Test
    fun aTapOpensTheChatOnce() {
        assertFalse(ChatRules.wasOpened(null, "n1"))
        assertFalse(ChatRules.wasOpened("", "n1"))
        assertFalse(ChatRules.wasOpened("not json", "n1"))
        val one = ChatRules.rememberOpened(null, "n1")
        assertTrue(ChatRules.wasOpened(one, "n1"))
        assertFalse(ChatRules.wasOpened(one, "n2"))
        val two = ChatRules.rememberOpened(one, "n2")
        assertTrue(ChatRules.wasOpened(two, "n1"))
        assertTrue(ChatRules.wasOpened(two, "n2"))
        // The same tap again is kept once.
        assertEquals(2, JSONArray(ChatRules.rememberOpened(two, "n1")).length())
    }

    @Test
    fun onlyTheLastTapsAreKept() {
        var saved: String? = null
        for (i in 1..30) saved = ChatRules.rememberOpened(saved, "n$i")
        assertEquals(20, JSONArray(saved).length())
        assertFalse(ChatRules.wasOpened(saved, "n10"))
        assertTrue(ChatRules.wasOpened(saved, "n11"))
        assertTrue(ChatRules.wasOpened(saved, "n30"))
        assertEquals(1, JSONArray(ChatRules.rememberOpened(saved, "x", keep = 0)).length())
    }
}

package com.affiliateo.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live sockets' rules: the app channel (the owner's on/off switch and the
 * team's Online switches, as a config frame holding exactly what
 * /api/live-chat/config answers) and the thread socket's frames.
 */
class ChatAppChannelTest {
    private val onFrame = """{"kind":"live_chat","conversation_id":"*","event":"config","config":{"enabled":true,"show_bubble":true,"online":true,""" +
        """"bubble":{"position":"top-left","size_points":64,"size_points_phone":48,"page_mode":"only","page_rules":["Home"],"greeting_id":"g2","style":"glass","color":"#7c3aed","icon_color":"#000000"}}}"""
    private val offFrame = """{"kind":"live_chat","conversation_id":"*","event":"config","config":{"enabled":false,"show_bubble":false}}"""

    @Test
    fun theAddressesNameTheAppAndCarryNoKey() {
        assertEquals("wss://affiliateo.com/api/live-chat/app-stream?app=my%20app%261", ChatRules.appChannelUrl("https://affiliateo.com/", "my app&1"))
        assertEquals("ws://localhost:3000/api/live-chat/app-stream?app=a1", ChatRules.appChannelUrl("http://localhost:3000", "a1"))
        assertEquals("wss://affiliateo.com/api/live-chat/stream", ChatRules.threadStreamUrl("https://affiliateo.com"))
    }

    @Test
    fun aConfigFrameReadsAsAFreshConfigRead() {
        val (config, saved) = ChatRules.parseAppFrame(onFrame)!!
        assertTrue(config.enabled)
        assertTrue(config.showBubble)
        assertTrue(config.online)
        assertEquals(ChatRules.Bubble("top-left", 64, "only", listOf("Home"), "g2", 48, "glass", "#7c3aed", "#000000"), config.bubble)
        // The same parser as the GET: the saved copy reads back the same.
        assertEquals(config, ChatRules.parseConfig(saved))
    }

    @Test
    fun offTakesTheBubbleAway() {
        val (off, _) = ChatRules.parseAppFrame(offFrame)!!
        assertEquals(ChatRules.Config.OFF, off)
        assertFalse(ChatRules.showsBubble(true, off))
        val on = ChatRules.parseAppFrame(onFrame)!!.first
        assertTrue(ChatRules.showsBubble(true, on))
        assertFalse(ChatRules.showsBubble(false, on))
        assertFalse(ChatRules.showsBubble(true, null))
        // On, but hidden until someone is online: no bubble, the channel stays.
        val hidden = ChatRules.Config(enabled = true, showBubble = false, online = false)
        assertFalse(ChatRules.showsBubble(true, hidden))
        assertTrue(ChatRules.appChannelWanted(true, true, hidden, false))
    }

    @Test
    fun otherFramesAreNotConfig() {
        assertNull(ChatRules.parseAppFrame("pong"))
        assertNull(ChatRules.parseAppFrame("not json"))
        assertNull(ChatRules.parseAppFrame("""{"kind":"live_chat","event":"message","config":{"enabled":true}}"""))
        assertNull(ChatRules.parseAppFrame("""{"kind":"message","event":"config","config":{"enabled":true}}"""))
        assertNull(ChatRules.parseAppFrame("""{"kind":"live_chat","event":"config","config":true}"""))
        // An odd config inside a config frame is off, as a GET would read it.
        assertEquals(ChatRules.Config.OFF, ChatRules.parseAppFrame("""{"kind":"live_chat","event":"config","config":{}}""")?.first)
    }

    @Test
    fun whenTheChannelIsOpen() {
        val on = ChatRules.Config(enabled = true, showBubble = true, online = false)
        assertTrue(ChatRules.appChannelWanted(true, true, on, false))
        assertFalse(ChatRules.appChannelWanted(false, true, on, false))
        assertFalse(ChatRules.appChannelWanted(true, false, on, false))
        assertFalse(ChatRules.appChannelWanted(true, true, ChatRules.Config.OFF, false))
        assertFalse(ChatRules.appChannelWanted(true, true, null, false))
        assertFalse(ChatRules.appChannelWanted(true, true, on, true))
    }

    @Test
    fun aRefusalWaitsForTheNextConfigRead() {
        assertTrue(ChatRules.refusesAppChannel(404))
        assertTrue(ChatRules.refusesAppChannel(403))
        assertFalse(ChatRules.refusesAppChannel(429))
        assertFalse(ChatRules.refusesAppChannel(408))
        assertFalse(ChatRules.refusesAppChannel(503))
        assertFalse(ChatRules.refusesAppChannel(null))
    }

    @Test
    fun backoffDoublesFromTwoSecondsToAMinutePlusJitter() {
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L, 60_000L), (1..8).map { ChatRules.appChannelDelayMs(it, 0.0) })
        assertEquals(2_500L, ChatRules.appChannelDelayMs(1, 1.0))
        assertEquals(75_000L, ChatRules.appChannelDelayMs(9, 1.0))
        assertEquals(2_000L, ChatRules.appChannelDelayMs(0, 0.0))
        // The thread's socket waits as the Swift SDK's does.
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), (1..7).map { ChatRules.threadRetryMs(it) })
    }

    @Test
    fun theThreadSocketsFrames() {
        assertEquals(ChatRules.ThreadFrame.TEAM_MESSAGE, ChatRules.parseThreadFrame("""{"kind":"live_chat","conversation_id":"c1","event":"message","message":{"sender":"team","body":"hi"}}"""))
        assertNull(ChatRules.parseThreadFrame("""{"kind":"live_chat","event":"message","message":{"sender":"visitor"}}"""))
        assertEquals(ChatRules.ThreadFrame.VISITOR_READ, ChatRules.parseThreadFrame("""{"kind":"live_chat","event":"read","by":"visitor"}"""))
        assertNull(ChatRules.parseThreadFrame("""{"kind":"live_chat","event":"read","by":"team"}"""))
        assertNull(ChatRules.parseThreadFrame("""{"kind":"live_chat","event":"typing"}"""))
        assertNull(ChatRules.parseThreadFrame("""{"kind":"message","event":"message","message":{"sender":"team"}}"""))
        assertNull(ChatRules.parseThreadFrame("pong"))
        assertNotNull(ChatRules.parseAppFrame(onFrame))
    }
}

package com.affiliateo.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hold to hide the chat bubble (4.19.0): the numbers, words and rules every SDK shares (KEEP IN STEP). */
class ChatHideTest {
    /** How far the circle sits in from the bubble box's edges, in dp (ChatBubbleView). */
    private val pad = 4f

    @Test
    fun theNumbersAreTheOtherSdksNumbers() {
        assertEquals(1000L, ChatRules.HOLD_MS)
        assertEquals(5000L, ChatRules.HIDE_MODE_MS)
        assertEquals(2.5f, ChatRules.JIGGLE_DEGREES, 0f)
        assertEquals(130L, ChatRules.JIGGLE_SWING_MS)
        assertEquals(18f, ChatRules.HIDE_X_DP, 0f)
        assertEquals(6f, ChatRules.HIDE_X_RADIUS_DP, 0f)
        assertEquals(3f, ChatRules.HIDE_X_OUTSET_DP, 0f)
        assertEquals(36f, ChatRules.HIDE_X_TARGET_DP, 0f)
    }

    @Test
    fun theHideIsKeptBesideTheBubblesPlace() {
        assertEquals("affiliateo_chat_hidden_my-app", ChatRules.hiddenKey("my-app"))
        // The same store and naming as the dragged place.
        assertEquals("affiliateo_chat_pos_my-app", ChatRules.positionKey("my-app"))
    }

    @Test
    fun theHideWordsInEveryLanguage() {
        val expected = mapOf(
            "en" to "Hide chat bubble",
            "ar" to "إخفاء فقاعة الدردشة",
            "de" to "Chat-Blase ausblenden",
            "es" to "Ocultar burbuja de chat",
            "fr" to "Masquer la bulle de chat",
            "hi" to "चैट बबल छिपाएँ",
            "it" to "Nascondi la bolla della chat",
            "ja" to "チャットバブルを非表示",
            "ko" to "채팅 버블 숨기기",
            "nl" to "Chatbubbel verbergen",
            "pl" to "Ukryj dymek czatu",
            "pt" to "Ocultar balão do chat",
            "ru" to "Скрыть значок чата",
            "tr" to "Sohbet balonunu gizle",
            "vi" to "Ẩn bong bóng trò chuyện",
            "zh" to "隐藏聊天气泡",
        )
        assertEquals(16, expected.size)
        assertEquals(expected.keys, ChatRules.CHAT_WORDS.keys)
        for ((language, words) in expected) assertEquals(language, words, ChatRules.CHAT_WORDS.getValue(language).hide)
    }

    @Test
    fun theHideWordsGoByTheFirstTwoLettersElseEnglish() {
        assertEquals("Ocultar balão do chat", ChatRules.chatWords("pt-BR").hide)
        assertEquals("隐藏聊天气泡", ChatRules.chatWords("zh-Hans-CN").hide)
        assertEquals("Masquer la bulle de chat", ChatRules.chatWords("FR-ca").hide)
        assertEquals("Hide chat bubble", ChatRules.chatWords("sv-SE").hide)
        assertEquals("Hide chat bubble", ChatRules.chatWords("").hide)
    }

    @Test
    fun hiddenOnlyOnceTheAppIdIsKnown() {
        assertTrue(ChatRules.hiddenByUser("my-app", stored = true))
        assertFalse(ChatRules.hiddenByUser("my-app", stored = false))
        // Before configure(): never hidden, and a showChatBubble() waits for configure().
        assertFalse(ChatRules.hiddenByUser(null, stored = true))
        assertFalse(ChatRules.hiddenByUser("", stored = true))
    }

    @Test
    fun pastTheSlopIsADragAndNeverAHold() {
        assertTrue(ChatRules.isStill(0f, 0f, 16))
        assertTrue(ChatRules.isStill(7f, -8f, 16))
        assertFalse(ChatRules.isStill(8f, 8f, 16))
        assertFalse(ChatRules.isStill(-20f, 0f, 16))
    }

    @Test
    fun liftingTheFinger() {
        assertEquals(ChatRules.Release.OPEN_CHAT, ChatRules.release(used = false, dragged = false))
        assertEquals(ChatRules.Release.KEEP_PLACE, ChatRules.release(used = false, dragged = true))
        // The hold started hide mode, or the touch ended it: no chat, no new place.
        assertEquals(ChatRules.Release.NOTHING, ChatRules.release(used = true, dragged = false))
        assertEquals(ChatRules.Release.NOTHING, ChatRules.release(used = true, dragged = true))
    }

    @Test
    fun theXHangsOverTheCirclesTopLeft() {
        // 3 dp out from the circle's corner, which sits 4 dp in from the box's: 1 dp in, inside the box.
        assertEquals(1f, ChatRules.hideXOrigin(pad), 0f)
    }

    @Test
    fun theXsTargetIs36AroundItsMiddleAsFarAsTheBoxGoes() {
        // The X's middle: 1 + 9 = 10 dp from the box's top-left.
        assertTrue(ChatRules.onHideX(10f, 10f, pad))
        assertTrue(ChatRules.onHideX(0f, 0f, pad))
        assertTrue(ChatRules.onHideX(28f, 28f, pad))
        assertTrue(ChatRules.onHideX(28f, 0f, pad))
        assertFalse(ChatRules.onHideX(28.5f, 10f, pad))
        assertFalse(ChatRules.onHideX(10f, 29f, pad))
        // The middle of a 56 dp circle (box 64) is not the X: it ends hide mode.
        assertFalse(ChatRules.onHideX(32f, 32f, pad))
        // The red number's corner (top-right of the box) is not the X either.
        assertFalse(ChatRules.onHideX(60f, 4f, pad))
    }

    @Test
    fun noAttentionWiggleInHideMode() {
        assertTrue(ChatRules.wiggles(30, bubbleOnScreen = true, chatOpen = false, appInFront = true, touched = false, animationsOn = true))
        assertFalse(ChatRules.wiggles(30, bubbleOnScreen = true, chatOpen = false, appInFront = true, touched = false, animationsOn = true, hideMode = true))
    }
}

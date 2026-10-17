package com.affiliateo.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailHashTest {
    private val known = "973dfe463ec85785f5f95af5ba3906eedb2d931c24e69824a89ea65dba4e813b"

    @Test fun knownAddress() {
        assertEquals(known, AffiliateoEmail.sha256Hex("test@example.com"))
    }

    @Test fun trimsAndLowercasesFirst() {
        assertEquals(known, AffiliateoEmail.sha256Hex("  Test@Example.COM \n"))
    }

    @Test fun noAddressNoHash() {
        assertNull(AffiliateoEmail.sha256Hex(null))
        assertNull(AffiliateoEmail.sha256Hex(""))
        assertNull(AffiliateoEmail.sha256Hex("   "))
    }
}

class FreeAccessCacheTest {
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun saved(
        active: Boolean = true,
        productId: String? = "pro_monthly",
        expiresAt: String? = null,
        checkedAtMs: Long,
    ) = FreeAccessCache.Entry(active, productId, expiresAt, checkedAtMs)

    /** 1_800_000_000_000 ms is 2027-01-15T08:00:00Z. */
    private fun iso(ms: Long) = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        .format(java.util.Date(ms))

    @Test fun nothingSavedIsNoAccess() {
        assertEquals(FreeAccess.INACTIVE, FreeAccessCache.offlineAnswer(null, now))
    }

    @Test fun recentAnswerWithNoEndIsKept() {
        assertEquals(
            FreeAccess(true, "pro_monthly", null),
            FreeAccessCache.offlineAnswer(saved(checkedAtMs = now - day), now),
        )
    }

    @Test fun recentAnswerWithFutureEndIsKept() {
        val end = iso(now + 3 * day)
        val answer = FreeAccessCache.offlineAnswer(saved(expiresAt = end, checkedAtMs = now - day), now)
        assertTrue(answer.active)
        assertEquals(end, answer.expiresAt)
    }

    @Test fun passedEndIsNoAccess() {
        val answer = FreeAccessCache.offlineAnswer(saved(expiresAt = iso(now - 60_000), checkedAtMs = now - day), now)
        assertEquals(FreeAccess.INACTIVE, answer)
    }

    @Test fun unreadableSavedEndIsNoAccess() {
        assertEquals(FreeAccess.INACTIVE, FreeAccessCache.offlineAnswer(saved(expiresAt = "soon", checkedAtMs = now), now))
    }

    @Test fun sevenDaysIsStillGoodButOlderIsNot() {
        assertTrue(FreeAccessCache.offlineAnswer(saved(checkedAtMs = now - 7 * day), now).active)
        assertFalse(FreeAccessCache.offlineAnswer(saved(checkedAtMs = now - 7 * day - 1), now).active)
    }

    @Test fun answerFromTheFutureIsNotTrusted() {
        // A clock set back would otherwise stretch the 7 days.
        assertFalse(FreeAccessCache.offlineAnswer(saved(checkedAtMs = now + day), now).active)
        assertTrue(FreeAccessCache.offlineAnswer(saved(checkedAtMs = now + 60_000), now).active)
    }

    @Test fun savedNoIsNo() {
        assertEquals(FreeAccess.INACTIVE, FreeAccessCache.offlineAnswer(saved(active = false, checkedAtMs = now), now))
    }

    @Test fun encodeDecodeRoundTrip() {
        val entry = saved(expiresAt = iso(now + day), checkedAtMs = now)
        assertEquals(entry, FreeAccessCache.decode(FreeAccessCache.encode(entry)))
        val fullAccess = saved(productId = null, checkedAtMs = now)
        assertEquals(fullAccess, FreeAccessCache.decode(FreeAccessCache.encode(fullAccess)))
        assertNull(FreeAccessCache.decode(null))
        assertNull(FreeAccessCache.decode("not json"))
    }
}

class FreeAccessReplyTest {
    private val end = 1_794_916_800_000L // 2026-11-17T12:00:00Z

    @Test fun activeWithPlanAndEnd() {
        val answer = FreeAccessCache.parseReply("""{"active":true,"product_id":"pro_yearly","expires_at":"2026-11-17T12:00:00.000Z"}""")
        // The end comes back as the server wrote it.
        assertEquals(FreeAccess(true, "pro_yearly", "2026-11-17T12:00:00.000Z"), answer)
    }

    @Test fun fullAccessWithNoEnd() {
        val answer = FreeAccessCache.parseReply("""{"active":true,"product_id":null,"expires_at":null}""")
        assertEquals(FreeAccess(true, null, null), answer)
    }

    @Test fun inactive() {
        assertEquals(FreeAccess.INACTIVE, FreeAccessCache.parseReply("""{"active":false,"product_id":null,"expires_at":null}"""))
    }

    @Test fun otherDateShapes() {
        assertEquals(end, FreeAccessCache.parseIsoMillis("2026-11-17T12:00:00Z"))
        assertEquals(end, FreeAccessCache.parseIsoMillis("2026-11-17T13:00:00+01:00"))
        assertEquals(end, FreeAccessCache.parseIsoMillis("2026-11-17T07:30:00-0430"))
        assertEquals(end + 123, FreeAccessCache.parseIsoMillis("2026-11-17T12:00:00.123456+00:00"))
        assertEquals(end, FreeAccessCache.parseIsoMillis("2026-11-17T12:00:00"))
        assertNull(FreeAccessCache.parseIsoMillis("2026-13-17T12:00:00Z"))
        assertNull(FreeAccessCache.parseIsoMillis("next tuesday"))
    }

    @Test fun notAReply() {
        assertNull(FreeAccessCache.parseReply("not json"))
        assertNull(FreeAccessCache.parseReply("""{"error":"rate limited"}"""))
        // An end that cannot be read must not become "no end".
        assertNull(FreeAccessCache.parseReply("""{"active":true,"expires_at":"next tuesday"}"""))
    }
}

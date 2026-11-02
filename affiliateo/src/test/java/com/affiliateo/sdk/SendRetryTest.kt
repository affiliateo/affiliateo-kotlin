package com.affiliateo.sdk

import com.affiliateo.sdk.SendRetry.Answer
import com.affiliateo.sdk.SendRetry.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 4.16.3: what the event queue does with each answer and how long it waits
 * before its next try (SendRetry).
 */
class SendRetryTest {
    private val t0 = 1_793_000_000_000L

    @Test
    fun eachStatusHasItsOutcome() {
        assertEquals(Outcome.KEPT, SendRetry.outcome(null))
        for (status in listOf(200, 201, 202, 204, 299)) assertEquals("$status", Outcome.SENT, SendRetry.outcome(status))
        for (status in listOf(400, 401, 403, 405, 409, 410, 413, 415, 422, 451, 499)) {
            assertEquals("$status", Outcome.REFUSED, SendRetry.outcome(status))
        }
        // 404 is the server's "Call /identify first" for a device it does not
        // know yet, which a later try fixes.
        for (status in listOf(404, 408, 429, 500, 502, 503, 504, 599, 301, 304)) {
            assertEquals("$status", Outcome.KEPT, SendRetry.outcome(status))
        }
    }

    @Test
    fun theWaitDoublesUpToFiveMinutes() {
        val waits = (1..10).map { SendRetry.waitMs(it, 500, null, t0, 0.0) }
        assertEquals(listOf(5_000L, 10_000, 20_000, 40_000, 80_000, 160_000, 300_000, 300_000, 300_000, 300_000), waits)
        assertEquals(5_000L, SendRetry.waitMs(0, null, null, t0, 0.0))
        assertEquals(300_000L, SendRetry.waitMs(1_000, null, null, t0, 0.0))
    }

    @Test
    fun theJitterAddsUpToAQuarter() {
        assertEquals(6_250L, SendRetry.waitMs(1, 500, null, t0, 1.0))
        assertEquals(5_625L, SendRetry.waitMs(1, 500, null, t0, 0.5))
        assertEquals(375_000L, SendRetry.waitMs(7, 500, null, t0, 1.0))
        assertEquals(6_250L, SendRetry.waitMs(1, 500, null, t0, 9.0))
        assertEquals(5_000L, SendRetry.waitMs(1, 500, null, t0, -1.0))
    }

    @Test
    fun retryAfterCountsFor429And503Only() {
        assertEquals(120_000L, SendRetry.waitMs(1, 429, "120", t0, 0.0))
        assertEquals(120_000L, SendRetry.waitMs(1, 503, "120", t0, 0.0))
        assertEquals(5_000L, SendRetry.waitMs(1, 500, "120", t0, 0.0))
        assertEquals(5_000L, SendRetry.waitMs(1, 408, "120", t0, 0.0))
        // Never shorter than the backoff, and at most an hour.
        assertEquals(20_000L, SendRetry.waitMs(3, 429, "2", t0, 0.0))
        assertEquals(3_600_000L, SendRetry.waitMs(1, 429, "86400", t0, 0.0))
        // The jitter goes on top of what was asked.
        assertEquals(75_000L, SendRetry.waitMs(1, 429, "60", t0, 1.0))
        // Unreadable: the backoff alone.
        assertEquals(10_000L, SendRetry.waitMs(2, 503, "soon", t0, 0.0))
    }

    @Test
    fun retryAfterReadsSecondsAndDates() {
        assertEquals(30_000L, SendRetry.retryAfterMs("30", t0))
        assertEquals(30_000L, SendRetry.retryAfterMs(" 30 ", t0))
        assertEquals(0L, SendRetry.retryAfterMs("0", t0))
        assertEquals(3_600_000L, SendRetry.retryAfterMs("99999", t0))
        assertEquals(3_600_000L, SendRetry.retryAfterMs("99999999999999999999999", t0))
        // Wed, 21 Oct 2015 07:28:00 GMT is 1445412480 s.
        val date = "Wed, 21 Oct 2015 07:28:00 GMT"
        val at = 1_445_412_480_000L
        assertEquals(90_000L, SendRetry.retryAfterMs(date, at - 90_000))
        assertEquals(0L, SendRetry.retryAfterMs(date, at + 90_000))
        assertEquals(3_600_000L, SendRetry.retryAfterMs(date, at - 7_200_000))
        for (unreadable in listOf(null, "", "  ", "soon", "-5", "1.5", "30s", "+30", "Wed, 21 Oct 2015", "$date and more")) {
            assertNull("$unreadable", SendRetry.retryAfterMs(unreadable, t0))
        }
    }

    @Test
    fun aKeptAnswerWaitsAndTheNextOtherOneResets() {
        val retry = SendRetry(jitter = { 0.0 })
        assertTrue(retry.canSend(t0))

        assertEquals(Outcome.KEPT, retry.answered(Answer(503), t0))
        assertEquals(1, retry.failures)
        assertFalse(retry.canSend(t0 + 4_999))
        assertTrue(retry.canSend(t0 + 5_000))

        val t1 = t0 + 5_000
        assertEquals(Outcome.KEPT, retry.answered(Answer(500), t1))
        assertEquals(2, retry.failures)
        assertFalse(retry.canSend(t1 + 9_999))
        assertTrue(retry.canSend(t1 + 10_000))

        assertEquals(Outcome.SENT, retry.answered(Answer(200), t1))
        assertEquals(0, retry.failures)
        assertTrue(retry.canSend(t1))

        retry.answered(Answer(503), t1)
        assertEquals(Outcome.REFUSED, retry.answered(Answer(400), t1))
        assertEquals(0, retry.failures)
        assertTrue(retry.canSend(t1))
    }

    @Test
    fun retryAfterHoldsTheQueue() {
        val retry = SendRetry(jitter = { 0.0 })
        retry.answered(Answer(429, "30"), t0)
        assertFalse(retry.canSend(t0 + 29_999))
        assertTrue(retry.canSend(t0 + 30_000))
    }

    @Test
    fun theNetworkComingBackEndsOnlyAWaitForTheNetwork() {
        val retry = SendRetry(jitter = { 0.0 })
        retry.answered(Answer(null), t0)
        retry.answered(Answer(null), t0)
        assertFalse(retry.canSend(t0))
        retry.networkBack()
        assertTrue(retry.canSend(t0))
        assertEquals(0, retry.failures)

        // The server answered "busy, come back in a minute": that holds.
        retry.answered(Answer(429, "60"), t0)
        retry.networkBack()
        assertFalse(retry.canSend(t0 + 59_000))
        assertTrue(retry.canSend(t0 + 60_000))
    }

    @Test
    fun nothingIsDroppedForFailedTries() {
        // A kept answer is a record in the entry, never a drop (EventBatch.settle).
        val retry = SendRetry(jitter = { 0.0 })
        val queue = mutableListOf(EventQueue.QueuedEvent("a", "https://affiliateo.com/api/v1/mobile/event", "{}", 0))
        var now = t0
        repeat(12) {
            assertTrue(retry.canSend(now))
            EventBatch.settle(queue, listOf("a"), retry.answered(Answer(503), now))
            now = retry.nextTryAtMs
        }
        assertEquals(listOf("a"), queue.map { it.id })
        assertEquals(12, queue[0].retries)
    }
}

package com.affiliateo.sdk

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * How the event queue tries again (4.16.3).
 *
 * A 2xx is sent. A refusal (a 4xx other than 404, 408 and 429) is dropped,
 * with one line in the debug log: the same bytes would be refused again.
 * Everything else stays queued and waits: no answer (offline, a time out),
 * 404, 408, 429 and 5xx. The wait is 5 s, doubling up to 5 minutes, plus up
 * to a quarter more so phones do not all come back at once, and never shorter
 * than the Retry-After a 429 or 503 asks for (up to an hour). Only the
 * queue's size cap drops a waiting event. Before 4.16.3 every answer but a
 * 2xx cost a try on the fixed 5 s timer, and the third dropped the event.
 *
 * 404 waits on purpose: the server answers it for a device whose first
 * identify has not landed yet ("Call /identify first"), which a later try
 * fixes.
 *
 * Not thread safe: EventQueue holds its lock around every call. Plain JVM
 * code, so the unit tests run it without a phone (SendRetryTest).
 */
internal class SendRetry(private val jitter: () -> Double = { Math.random() }) {
    enum class Outcome { SENT, REFUSED, KEPT }

    /** What the server answered one request: its HTTP status (null when no answer came) and its Retry-After header. */
    class Answer(val status: Int?, val retryAfter: String? = null)

    /** Kept answers in a row. */
    var failures = 0
        private set

    /** No flush sends before this (ms since 1970). */
    var nextTryAtMs = 0L
        private set

    /** The wait is for the network (no answer came), not one the server asked for. */
    private var waitIsNetwork = false

    fun canSend(nowMs: Long): Boolean = nowMs >= nextTryAtMs

    /** Takes in one request's [answer] and says what becomes of the entries it carried. */
    fun answered(answer: Answer, nowMs: Long): Outcome {
        val outcome = outcome(answer.status)
        if (outcome == Outcome.KEPT) {
            failures++
            nextTryAtMs = nowMs + waitMs(failures, answer.status, answer.retryAfter, nowMs, jitter())
            waitIsNetwork = answer.status == null
        } else {
            failures = 0
            nextTryAtMs = 0L
            waitIsNetwork = false
        }
        return outcome
    }

    /** The network came back: a wait for it is over. A wait the server asked for (it answered, busy) still holds. */
    fun networkBack() {
        if (!waitIsNetwork) return
        failures = 0
        nextTryAtMs = 0L
        waitIsNetwork = false
    }

    companion object {
        const val FIRST_WAIT_MS = 5_000L
        const val MAX_WAIT_MS = 300_000L
        const val MAX_RETRY_AFTER_MS = 3_600_000L

        private val DIGITS = Regex("[0-9]+")
        // Only the date form servers send ("Wed, 21 Oct 2026 07:28:00 GMT"):
        // the parser alone would take a date with more after it.
        private val HTTP_DATE = Regex("[A-Za-z]{3}, \\d{1,2} [A-Za-z]{3} \\d{4} \\d{2}:\\d{2}:\\d{2} GMT")

        fun outcome(status: Int?): Outcome = when {
            status == null -> Outcome.KEPT
            status in 200..299 -> Outcome.SENT
            status in 400..499 && status != 404 && status != 408 && status != 429 -> Outcome.REFUSED
            else -> Outcome.KEPT
        }

        /**
         * The wait after kept answer number [failures] (1 and up): 5 s doubling
         * up to 5 minutes, at least what a 429 or 503 asked for, plus up to a
         * quarter more ([jitter] 0 to 1).
         */
        fun waitMs(failures: Int, status: Int?, retryAfter: String?, nowMs: Long, jitter: Double): Long {
            var base = minOf(MAX_WAIT_MS, FIRST_WAIT_MS shl (failures.coerceIn(1, 7) - 1))
            if (status == 429 || status == 503) {
                val asked = retryAfterMs(retryAfter, nowMs)
                if (asked != null) base = maxOf(base, asked)
            }
            return base + (base * 0.25 * jitter.coerceIn(0.0, 1.0)).toLong()
        }

        /**
         * A Retry-After header in ms: whole seconds ("120") or an HTTP date
         * (already past is 0), at most an hour. Null when it is missing or does
         * not read as either.
         */
        fun retryAfterMs(header: String?, nowMs: Long): Long? {
            val text = header?.trim().orEmpty()
            if (text.isEmpty()) return null
            if (DIGITS.matches(text)) {
                val seconds = text.toLongOrNull() ?: return MAX_RETRY_AFTER_MS
                return minOf(seconds, MAX_RETRY_AFTER_MS / 1_000) * 1_000
            }
            if (!HTTP_DATE.matches(text)) return null
            val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
                isLenient = false
            }
            val at = format.parse(text, ParsePosition(0))?.time ?: return null
            return (at - nowMs).coerceIn(0L, MAX_RETRY_AFTER_MS)
        }
    }
}

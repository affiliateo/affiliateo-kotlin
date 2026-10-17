package com.affiliateo.sdk

import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * What [Affiliateo.freeAccess] answered (4.12.0): whether the app owner gave
 * the signed-in person free access from their Affiliateo dashboard.
 *
 * ```kotlin
 * val access = Affiliateo.freeAccess()
 * if (access.active) unlockPaidFeatures(access.productId)
 * ```
 */
data class FreeAccess(
    /** Unlock the app when true. */
    val active: Boolean,
    /** The store product id of the plan the owner chose. Null means full access (every paid feature). */
    val productId: String? = null,
    /**
     * When the free access ends, in ISO 8601 as the server sent it
     * ("2026-11-17T12:00:00.000Z"). Null means it has no end date.
     */
    val expiresAt: String? = null,
) {
    companion object {
        /** No free access. */
        @JvmField
        val INACTIVE = FreeAccess(active = false)
    }
}

/**
 * The last answer the server gave, kept on the phone so an app opened
 * offline still unlocks for a while (4.12.0). Cleared on sign-out and when a
 * different user signs in, since it belongs to the person who was asked about.
 * Everything here but [parseReply], [encode] and [decode] is plain Kotlin, so
 * the rules are unit tested without a phone.
 */
internal object FreeAccessCache {
    const val KEY = "affiliateo_free_access"
    /** How long a saved answer counts without a new one from the server. */
    const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    /** A saved answer dated further ahead than this is not trusted, so a clock set back cannot stretch the 7 days. */
    const val CLOCK_SKEW_MS = 5L * 60 * 1000

    data class Entry(
        val active: Boolean,
        val productId: String?,
        /** ISO 8601 as the server sent it, null for no end. */
        val expiresAt: String?,
        /** When the server gave this answer, milliseconds since 1970. */
        val checkedAtMs: Long,
    ) {
        constructor(answer: FreeAccess, checkedAtMs: Long) :
            this(answer.active, answer.productId, answer.expiresAt, checkedAtMs)
    }

    /**
     * The answer when the server could not be asked: the saved one while it
     * is still good (its end, if any, is in the future and the server gave it
     * at most 7 days ago), otherwise no free access.
     */
    fun offlineAnswer(entry: Entry?, nowMs: Long): FreeAccess {
        if (entry == null || !entry.active) return FreeAccess.INACTIVE
        val age = nowMs - entry.checkedAtMs
        if (age > MAX_AGE_MS || age < -CLOCK_SKEW_MS) return FreeAccess.INACTIVE
        val expiresAt = entry.expiresAt
        if (expiresAt != null) {
            val end = parseIsoMillis(expiresAt) ?: return FreeAccess.INACTIVE
            if (end <= nowMs) return FreeAccess.INACTIVE
        }
        return FreeAccess(true, entry.productId, expiresAt)
    }

    fun encode(entry: Entry): String = JSONObject().apply {
        put("active", entry.active)
        put("product_id", entry.productId ?: JSONObject.NULL)
        put("expires_at", entry.expiresAt ?: JSONObject.NULL)
        put("checked_at", entry.checkedAtMs)
    }.toString()

    fun decode(text: String?): Entry? {
        if (text == null) return null
        return try {
            val json = JSONObject(text)
            Entry(
                active = json.getBoolean("active"),
                productId = if (json.isNull("product_id")) null else json.getString("product_id"),
                expiresAt = if (json.isNull("expires_at")) null else json.getString("expires_at"),
                checkedAtMs = json.getLong("checked_at"),
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The server's reply `{ active, product_id, expires_at }`, or null when it
     * is not one (the check then counts as failed).
     */
    fun parseReply(text: String): FreeAccess? {
        val json = try {
            JSONObject(text)
        } catch (_: Exception) {
            return null
        }
        val active = json.opt("active") as? Boolean ?: return null
        if (!active) return FreeAccess.INACTIVE
        val productId = if (json.isNull("product_id")) null else
            (json.opt("product_id") as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val expiresAt = if (json.isNull("expires_at")) null else {
            // An end the SDK cannot read must not turn into "no end".
            val raw = (json.opt("expires_at") as? String)?.trim() ?: return null
            if (parseIsoMillis(raw) == null) return null
            raw
        }
        return FreeAccess(true, productId, expiresAt)
    }

    private val ISO = Regex(
        """^(\d{4})-(\d{2})-(\d{2})[Tt ](\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?([Zz]|[+-]\d{2}(?::?\d{2})?)?$"""
    )

    /**
     * ISO 8601 ("2026-11-17T12:00:00.000Z", "2026-11-17T12:00:00+00:00") as
     * milliseconds since 1970, or null. By hand because java.time needs
     * Android 8 and this SDK runs on 7; no zone counts as UTC.
     */
    fun parseIsoMillis(text: String): Long? {
        val m = ISO.matchEntire(text.trim()) ?: return null
        val g = m.groupValues
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            isLenient = false
            clear()
            set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
            set(Calendar.MILLISECOND, g[7].padEnd(3, '0').take(3).toInt())
        }
        val utcMillis = try {
            calendar.timeInMillis
        } catch (_: IllegalArgumentException) {
            return null // month 13, hour 25 and the like
        }
        val zone = g[8]
        if (zone.isEmpty() || zone.equals("Z", ignoreCase = true)) return utcMillis
        val sign = if (zone[0] == '-') -1 else 1
        val digits = zone.substring(1).replace(":", "")
        val hours = digits.take(2).toInt()
        val minutes = if (digits.length > 2) digits.substring(2).toInt() else 0
        return utcMillis - sign * (hours * 60L + minutes) * 60_000L
    }
}

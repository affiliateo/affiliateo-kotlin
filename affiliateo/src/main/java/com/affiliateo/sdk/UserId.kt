package com.affiliateo.sdk

/**
 * Which user ids may name a signed-in person (4.11.0).
 *
 * Every device that signs in with the same user id becomes ONE customer of
 * the app on Affiliateo's side, so an id that really means "nobody" ("null",
 * "undefined", "guest", "0") would merge every stranger whose app sent it
 * into one person. The server refuses these with a 400; this is the same
 * list, checked here so a wrong call is skipped (and says so with
 * debug = true) instead of costing a request.
 *
 * KEEP IN SYNC with src/lib/customers/user-id.ts in the Affiliateo web repo
 * and with the copies in the other SDKs.
 */
internal object AffiliateoUserId {
    const val MAX_LENGTH = 128

    private val NOT_A_USER = setOf(
        "null", "(null)", "<null>", "nil", "none", "no", "undefined", "unidentified",
        "unknown", "anonymous", "anon", "guest", "nan", "false", "true",
        "0", "-1", "[]", "{}", "[object object]",
    )

    /** The trimmed id when it can name a person, otherwise null. */
    fun usable(value: String?): String? {
        val id = value?.trim() ?: return null
        if (id.isEmpty() || id.length > MAX_LENGTH) return null
        if (id.lowercase() in NOT_A_USER) return null
        // RevenueCat's own anonymous id names an install, not a user.
        if (id.startsWith("\$RCAnonymousID:")) return null
        return id
    }
}

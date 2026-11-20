package com.affiliateo.sdk

import org.json.JSONObject

/**
 * What [Affiliateo.setAffiliateCode] answers (4.18.0).
 *
 * An affiliate code is a coupon code without the coupon. The buyer types an
 * affiliate's code in a box YOUR app shows, you hand it to the SDK, and that
 * affiliate gets the sale. No discount, Google Play is not involved, and
 * nothing is matched by IP address for that buyer. The code is the one each
 * affiliate already has: the end of their link.
 *
 * ```kotlin
 * val result = Affiliateo.setAffiliateCode(codeField.text.toString())
 * if (result.matched) showCodeApplied() else showCodeNotFound()
 * // Then buy as you already do, with Affiliateo.state.obfuscatedAccountId.
 * ```
 */
data class AffiliateCode(
    /** The code is an affiliate's, and it is on this device now. */
    val matched: Boolean,
    /** The affiliate's code as Affiliateo keeps it. Null unless [matched]. */
    val refCode: String? = null,
    /** Why it did not match. Null when it did. */
    val reason: Reason? = null,
) {
    enum class Reason {
        /** No affiliate of this app has that code. Nothing changed. */
        NOT_FOUND,
        /** Nothing was typed, or it is not one word of at most 64 characters. */
        INVALID,
        /** The app's owner has affiliate codes switched off in Affiliateo. */
        OFF,
        /**
         * The SDK has not reached Affiliateo for this device yet (or the person
         * opted out), so there is nothing to put the code on. Try again in a moment.
         */
        NOT_READY,
        /** Offline, too many tries, or the server did not answer. Try again. */
        FAILED,
    }

    internal companion object {
        fun applied(refCode: String) = AffiliateCode(matched = true, refCode = refCode)
        fun refused(reason: Reason) = AffiliateCode(matched = false, reason = reason)
    }
}

/** The rules of a typed code, kept pure so they are tested without a server. */
internal object AffiliateCodeRules {
    /** The longest code the server reads. */
    const val MAX_LENGTH = 64

    /**
     * What the buyer typed, ready to send: the space around it and a leading
     * "@" dropped, or null when there is nothing usable (empty, more than one
     * word, too long). The server applies the same rule.
     */
    fun normalize(raw: String?): String? {
        val code = (raw ?: return null).trim().trimStart('@').trim()
        if (code.isEmpty() || code.length > MAX_LENGTH) return null
        if (code.any { it.isWhitespace() || it.isISOControl() }) return null
        return code
    }

    /**
     * The server's answer as the app reads it. POST
     * /api/v1/mobile/affiliate-code answers 200 with `matched` (and the
     * `ref_code` when true), 403 `affiliate_codes_off` while the owner's switch
     * is off, 404 `unknown_device` before identify has run, 400 `invalid_code`,
     * and anything else is a failure worth a retry.
     */
    fun parseReply(status: Int, text: String?): AffiliateCode {
        val json = try {
            JSONObject(text ?: "")
        } catch (_: Exception) {
            JSONObject()
        }
        val code = json.opt("code") as? String
        return when {
            status in 200..299 -> {
                val matched = json.opt("matched") as? Boolean ?: return AffiliateCode.refused(AffiliateCode.Reason.FAILED)
                val refCode = (json.opt("ref_code") as? String)?.takeIf { it.isNotEmpty() }
                when {
                    matched && refCode != null -> AffiliateCode.applied(refCode)
                    matched -> AffiliateCode.refused(AffiliateCode.Reason.FAILED)
                    else -> AffiliateCode.refused(AffiliateCode.Reason.NOT_FOUND)
                }
            }
            status == 403 && code == "affiliate_codes_off" -> AffiliateCode.refused(AffiliateCode.Reason.OFF)
            status == 404 && code == "unknown_device" -> AffiliateCode.refused(AffiliateCode.Reason.NOT_READY)
            status == 400 && code == "invalid_code" -> AffiliateCode.refused(AffiliateCode.Reason.INVALID)
            else -> AffiliateCode.refused(AffiliateCode.Reason.FAILED)
        }
    }
}

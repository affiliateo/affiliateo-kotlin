package com.affiliateo.sdk

import java.security.MessageDigest

/**
 * The signed-in person's email as Affiliateo receives it (4.12.0): the
 * SHA-256 of the trimmed, lowercased address, in lowercase hex. The address
 * itself never leaves the phone. Affiliateo hashes the address an app owner
 * types for free access the same way and compares the two.
 *
 * KEEP IN SYNC with the server and with the copies in the other SDKs.
 */
internal object AffiliateoEmail {
    /** The hash, or null when there is no address (null, empty or only spaces). */
    fun sha256Hex(email: String?): String? {
        // lowercase() is locale independent, so a Turkish phone hashes "I" the same.
        val normalized = email?.trim()?.lowercase() ?: return null
        if (normalized.isEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        // By hand rather than String.format, which follows the phone's locale.
        val out = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val v = byte.toInt() and 0xff
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return out.toString()
    }

    private const val HEX = "0123456789abcdef"
}

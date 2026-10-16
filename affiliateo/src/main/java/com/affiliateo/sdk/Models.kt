package com.affiliateo.sdk

import java.text.SimpleDateFormat
import java.util.*

data class DeviceInfo(
    val deviceModel: String,
    val os: String,
    val osVersion: String,
    val appVersion: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val timezone: String,
    val language: String
)

data class IdentifyResponse(
    val visitorId: String,
    val refCode: String?,
    val matched: Boolean
)

data class MobileEvent(
    val type: String,
    val timestamp: String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date()),
    /** Set on screen_view events (e.g. "Paywall"). */
    val screen: String? = null,
    /** Optional context dictionary (e.g. mapOf("plan" to "pro")). */
    val metadata: Map<String, Any>? = null
)

data class AffiliateoState(
    val refCode: String?,
    val isMatched: Boolean,
    val isLoading: Boolean,
    val visitorId: String?,
    /**
     * Play Billing obfuscatedAccountId: the Affiliateo visitor id of this
     * device (a UUID). Every user gets one, whether or not they came through
     * an affiliate. Always pass it to BillingFlowParams via
     * setObfuscatedAccountId() at purchase time, so Google stamps it onto the
     * purchase and Affiliateo ties the sale (and every renewal and refund
     * after it) to this phone, and to the affiliate when there is one.
     *
     * Null before identify succeeds (so also when the SDK starts opted out)
     * and after reset().
     */
    val obfuscatedAccountId: String? = null
)

/** What a purchase reported with `Affiliateo.purchase` bought. */
enum class PurchaseKind(internal val wire: String) {
    /** The first payment of a subscription. */
    SUBSCRIPTION("subscription"),
    /** A one-time purchase (lifetime access, a pack of credits). */
    ONE_TIME("one_time"),
}

class AffiliateoException(message: String) : Exception(message)

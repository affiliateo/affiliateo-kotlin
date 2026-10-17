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
     * Null before identify succeeds (so also when the SDK starts opted out).
     * Since 4.11.0 logout() and reset() keep it: the device and its visitor
     * id stay the same, only who it counts as changes.
     */
    val obfuscatedAccountId: String? = null
)

/** What /api/v1/mobile/identify-user answered (4.11.0). */
internal sealed class IdentifyUserResult {
    /** The server has it. [outcome] is "same", "adopted", "merged" or "switched". */
    data class Linked(val outcome: String?) : IdentifyUserResult()
    /** The device is not known yet; send again after the first identify. */
    object NotLinked : IdentifyUserResult()
    /** A 400: not an id a sign-in gives. Not retried. */
    object Refused : IdentifyUserResult()
}

/** What a purchase reported with `Affiliateo.purchase` bought. */
enum class PurchaseKind(internal val wire: String) {
    /** The first payment of a subscription. */
    SUBSCRIPTION("subscription"),
    /** A one-time purchase (lifetime access, a pack of credits). */
    ONE_TIME("one_time"),
}

class AffiliateoException(message: String) : Exception(message)

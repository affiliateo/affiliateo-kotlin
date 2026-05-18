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
    }.format(Date())
)

data class AffiliateoState(
    val refCode: String?,
    val isMatched: Boolean,
    val isLoading: Boolean,
    val visitorId: String?,
    /**
     * Play Billing obfuscatedAccountId UUID. Pass this to BillingFlowParams
     * via setObfuscatedAccountId() at purchase time so Google stamps it onto
     * the resulting RTDN payloads, which our backend resolves to the affiliate.
     *
     * Null before identify completes or when the device isn't matched to any
     * affiliate.
     */
    val obfuscatedAccountId: String? = null
)

class AffiliateoException(message: String) : Exception(message)

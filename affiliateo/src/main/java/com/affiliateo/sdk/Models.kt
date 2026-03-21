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
    val visitorId: String?
)

class AffiliateoException(message: String) : Exception(message)

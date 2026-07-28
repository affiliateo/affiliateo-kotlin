package com.affiliateo.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal class AffiliateoClient(private val apiUrl: String = "https://affiliateo.com") {

    suspend fun identify(
        campaignId: String,
        deviceId: String,
        deviceInfo: DeviceInfo,
        installReferrer: String? = null
    ): IdentifyResponse = withContext(Dispatchers.IO) {
        val url = URL("${apiUrl.trimEnd('/')}/api/v1/mobile/identify")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true

        val body = JSONObject().apply {
            put("campaign_id", campaignId)
            put("device_id", deviceId)
            put("device_model", deviceInfo.deviceModel)
            put("os", deviceInfo.os)
            put("os_version", deviceInfo.osVersion)
            put("app_version", deviceInfo.appVersion)
            put("screen_width", deviceInfo.screenWidth)
            put("screen_height", deviceInfo.screenHeight)
            put("timezone", deviceInfo.timezone)
            put("language", deviceInfo.language)
            // Play Install Referrer — lets the backend label a paid-ad
            // install's source (Meta / TikTok / Google Ads).
            installReferrer?.let { put("install_referrer", it) }
        }

        conn.outputStream.use { it.write(body.toString().toByteArray()) }

        if (conn.responseCode != 200) {
            throw AffiliateoException("Identify failed: ${conn.responseCode}")
        }

        val responseText = conn.inputStream.bufferedReader().readText()
        val json = JSONObject(responseText)

        IdentifyResponse(
            visitorId = json.getString("visitor_id"),
            refCode = json.optString("ref_code", null),
            matched = json.getBoolean("matched")
        )
    }

    suspend fun sendEvents(
        campaignId: String,
        deviceId: String,
        events: List<MobileEvent>
    ) = withContext(Dispatchers.IO) {
        if (events.isEmpty()) return@withContext

        val url = URL("${apiUrl.trimEnd('/')}/api/v1/mobile/event")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true

        val eventsArray = JSONArray().apply {
            events.forEach { event ->
                put(JSONObject().apply {
                    put("type", event.type)
                    put("timestamp", event.timestamp)
                    event.screen?.let { put("screen", it) }
                    event.metadata?.let { put("metadata", JSONObject(it)) }
                })
            }
        }

        val body = JSONObject().apply {
            put("campaign_id", campaignId)
            put("device_id", deviceId)
            put("events", eventsArray)
        }

        conn.outputStream.use { it.write(body.toString().toByteArray()) }

        if (conn.responseCode != 200) {
            throw AffiliateoException("Event send failed: ${conn.responseCode}")
        }
    }

    /**
     * Link this anonymous device install to a merchant user_id. Required
     * for cross-device funnel stitching: the same person on phone +
     * tablet + reinstall all collapse to one funnel actor. user_id only.
     * the SDK does NOT accept, collect, or transmit email or any other
     * PII. Idempotent on the server side. Best-effort: a 4xx here means
     * the visitor row hasn't been created yet (sign-in fired before first
     * /identify) and the next session will retry.
     */
    suspend fun identifyUser(
        campaignId: String,
        deviceId: String,
        userId: String
    ) = withContext(Dispatchers.IO) {
        val url = URL("${apiUrl.trimEnd('/')}/api/v1/mobile/identify-user")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true

        val body = JSONObject().apply {
            put("campaign_id", campaignId)
            put("device_id", deviceId)
            put("user_id", userId)
        }

        conn.outputStream.use { it.write(body.toString().toByteArray()) }

        if (conn.responseCode != 200) {
            throw AffiliateoException("Identify user failed: ${conn.responseCode}")
        }
    }

    /**
     * Report the host app's RevenueCat App User ID
     * (`Purchases.sharedInstance.appUserID`).
     *
     * Lets an app owner grant this affiliate complimentary access to the app
     * from their Affiliateo dashboard. Without it, Affiliateo can only match
     * an affiliate to a RevenueCat customer by email, which requires the host
     * app to be setting RevenueCat's `$email` attribute AND the affiliate to
     * have used the same address they used on Affiliateo.
     *
     * Deliberately separate from [identifyUser]: sign-in and RevenueCat
     * configuration happen at different moments, and an app may do one
     * without the other. The server accepts either field alone and writes only
     * what the request carried, so neither wipes the other.
     *
     * Write-once per device server-side. Re-sending the same id every launch
     * is a no-op; a DIFFERENT id for an already-bound device is rejected, so a
     * tampered client cannot repoint an established device at somebody else's
     * RevenueCat customer.
     */
    suspend fun identifyRevenueCatUser(
        campaignId: String,
        deviceId: String,
        revenueCatUserId: String
    ) = withContext(Dispatchers.IO) {
        val url = URL("${apiUrl.trimEnd('/')}/api/v1/mobile/identify-user")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true

        val body = JSONObject().apply {
            put("campaign_id", campaignId)
            put("device_id", deviceId)
            put("revenuecat_user_id", revenueCatUserId)
        }

        conn.outputStream.use { it.write(body.toString().toByteArray()) }

        if (conn.responseCode != 200) {
            throw AffiliateoException("Identify RevenueCat user failed: ${conn.responseCode}")
        }
    }

    /**
     * Bind a Play Billing obfuscatedAccountId (UUID) to this visitor on the
     * server. After this returns, Google RTDN payloads carrying this id in
     * externalAccountIdentifiers.obfuscatedExternalAccountId resolve to the
     * same affiliate the visitor is matched to.
     */
    suspend fun registerGoogleAccountId(
        campaignId: String,
        visitorId: String,
        obfuscatedAccountId: String
    ) = withContext(Dispatchers.IO) {
        val url = URL("${apiUrl.trimEnd('/')}/api/v1/mobile/google-account-id")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true

        val body = JSONObject().apply {
            put("campaign_id", campaignId)
            put("visitor_id", visitorId)
            put("obfuscated_account_id", obfuscatedAccountId)
        }

        conn.outputStream.use { it.write(body.toString().toByteArray()) }

        if (conn.responseCode != 200) {
            throw AffiliateoException("Google account id register failed: ${conn.responseCode}")
        }
    }
}

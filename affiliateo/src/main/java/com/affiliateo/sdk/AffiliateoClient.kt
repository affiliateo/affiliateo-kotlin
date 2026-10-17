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
        installReferrer: String? = null,
        adIdentifiers: AdIdentifiers? = null
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
            // Advertising ids (4.9.0): only what the person allowed, see AdIdentifiers.
            adIdentifiers?.toMap()?.forEach { (key, value) -> if (value != null) put(key, value) }
        }

        conn.outputStream.use { it.write(body.toString().toByteArray()) }

        if (conn.responseCode != 200) {
            throw AffiliateoException("Identify failed: ${conn.responseCode}")
        }

        val responseText = conn.inputStream.bufferedReader().readText()
        val json = JSONObject(responseText)

        IdentifyResponse(
            visitorId = json.getString("visitor_id"),
            // optString("ref_code", null) answers the TEXT "null" for a JSON null,
            // which every install that did not come from an affiliate gets.
            refCode = if (json.isNull("ref_code")) null else json.optString("ref_code").takeIf { it.isNotEmpty() },
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
     * Tell Affiliateo who is signed in on this device: the user id, and since
     * 4.12.0 the SHA-256 of their email when the app gave one (never the
     * address itself), which is what free access is matched on.
     *
     * Since 4.11.0 the answer is read rather than ignored:
     * - [IdentifyUserResult.Linked]: the server has it. The outcome says what
     *   happened to the app's customer: "same", "adopted", "merged", or
     *   "switched" (a different person signed in on this device).
     * - [IdentifyUserResult.NotLinked]: the device is not known yet, because
     *   the sign-in raced ahead of this launch's first identify. Sent again
     *   once that call has finished.
     * - [IdentifyUserResult.Refused]: a 400, the id is not one a sign-in
     *   gives. Not retried.
     * Anything else (offline, a 5xx, a rate limit) throws, and the next launch
     * sends the stored id again.
     */
    suspend fun identifyUser(
        campaignId: String,
        deviceId: String,
        userId: String,
        emailSha256: String? = null
    ): IdentifyUserResult = withContext(Dispatchers.IO) {
        val url = URL("${apiUrl.trimEnd('/')}/api/v1/mobile/identify-user")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true

        val body = JSONObject().apply {
            put("campaign_id", campaignId)
            put("device_id", deviceId)
            put("user_id", userId)
            // Left out when there is no email: the server keeps what it has.
            emailSha256?.let { put("email_sha256", it) }
        }

        conn.outputStream.use { it.write(body.toString().toByteArray()) }

        val code = conn.responseCode
        if (code == 400) return@withContext IdentifyUserResult.Refused
        if (code !in 200..299) {
            throw AffiliateoException("Identify user failed: $code")
        }
        val json = try {
            JSONObject(conn.inputStream.bufferedReader().readText())
        } catch (_: Exception) {
            null
        }
        if (json != null && json.has("linked") && !json.optBoolean("linked", true)) {
            IdentifyUserResult.NotLinked
        } else {
            IdentifyUserResult.Linked(
                if (json == null || json.isNull("outcome")) null else json.optString("outcome").takeIf { it.isNotEmpty() }
            )
        }
    }

    /**
     * The person signed out (4.11.0). POST /api/v1/mobile/logout: the device
     * keeps its device id and visitor id, and from now on counts as a new
     * anonymous customer of the app. Returns the server's outcome
     * ("signed_out" or "already_anonymous"); throws when it did not land.
     */
    suspend fun logout(campaignId: String, deviceId: String): String? = withContext(Dispatchers.IO) {
        val url = URL("${apiUrl.trimEnd('/')}/api/v1/mobile/logout")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true

        val body = JSONObject().apply {
            put("campaign_id", campaignId)
            put("device_id", deviceId)
        }

        conn.outputStream.use { it.write(body.toString().toByteArray()) }

        val code = conn.responseCode
        if (code !in 200..299) {
            throw AffiliateoException("Logout failed: $code")
        }
        val json = try {
            JSONObject(conn.inputStream.bufferedReader().readText())
        } catch (_: Exception) {
            null
        }
        if (json == null || json.isNull("outcome")) null else json.optString("outcome").takeIf { it.isNotEmpty() }
    }

    /**
     * Whether the app owner gave the person signed in on this device free
     * access (4.12.0). POST /api/v1/mobile/free-access. Throws when there is no
     * usable answer (offline, a non-2xx, a reply that is not one), so the
     * caller can fall back to the saved answer.
     */
    suspend fun freeAccess(campaignId: String, deviceId: String): FreeAccess = withContext(Dispatchers.IO) {
        val url = URL("${apiUrl.trimEnd('/')}/api/v1/mobile/free-access")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        // An app may hold its paywall for this answer, and HttpURLConnection
        // waits forever by default: past this the saved answer is used.
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000

        val body = JSONObject().apply {
            put("campaign_id", campaignId)
            put("device_id", deviceId)
        }

        conn.outputStream.use { it.write(body.toString().toByteArray()) }

        val code = conn.responseCode
        if (code !in 200..299) {
            throw AffiliateoException("Free access check failed: $code")
        }
        FreeAccessCache.parseReply(conn.inputStream.bufferedReader().readText())
            ?: throw AffiliateoException("Free access check failed: not a reply")
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

    // registerGoogleAccountId (POST /api/v1/mobile/google-account-id) used to
    // live here. Since 4.10.0 the obfuscatedAccountId IS the visitor id, which
    // the server already knows from identify, so there is nothing left to
    // register.
}

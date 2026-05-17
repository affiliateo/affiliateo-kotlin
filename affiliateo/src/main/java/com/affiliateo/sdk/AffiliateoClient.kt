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
        deviceInfo: DeviceInfo
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
}

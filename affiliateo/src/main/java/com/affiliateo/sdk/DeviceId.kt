package com.affiliateo.sdk

import android.content.Context
import android.provider.Settings

/**
 * Returns a stable device ID that persists across app launches.
 * Uses Android's built-in ANDROID_ID — no permissions needed.
 * Falls back to a UUID saved in SharedPreferences.
 */
internal object DeviceId {

    private var cached: String? = null

    fun get(context: Context): String {
        cached?.let { return it }

        // Try ANDROID_ID first (built-in, no permissions needed)
        try {
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            if (!androidId.isNullOrBlank()) {
                val id = "android-$androidId"
                cached = id
                return id
            }
        } catch (_: Exception) { }

        // Fallback: generate UUID and persist in SharedPreferences
        val prefs = context.getSharedPreferences("affiliateo_sdk", Context.MODE_PRIVATE)
        val saved = prefs.getString("device_id", null)
        if (saved != null) {
            cached = saved
            return saved
        }

        val newId = "android-${java.util.UUID.randomUUID()}"
        prefs.edit().putString("device_id", newId).apply()
        cached = newId
        return newId
    }
}

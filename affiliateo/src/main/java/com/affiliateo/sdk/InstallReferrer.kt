package com.affiliateo.sdk

import android.content.Context
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Play Install Referrer capture.
 *
 * The Play Store hands every app the link that installed it (the "install
 * referrer"). When the install came from a paid ad, the referrer carries the
 * network's utm/click-id tags — that's how the backend labels the install's
 * source (Meta / TikTok / Google Ads) without the merchant doing anything.
 *
 * Read ONCE per install: the result (or a definitive "nothing there") is
 * cached in SharedPreferences so we never re-open the Play Store connection.
 * Transient failures (service unavailable) are NOT cached, so the next
 * launch retries. Everything is best-effort — attribution must never break
 * or delay the host app.
 */
internal object InstallReferrer {

    private const val PREFS = "affiliateo"
    private const val VALUE_KEY = "affiliateo_install_referrer"
    private const val DONE_KEY = "affiliateo_install_referrer_done"

    /** Returns the raw referrer string, or null when there is none. */
    suspend fun get(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(DONE_KEY, false)) {
            return prefs.getString(VALUE_KEY, null)
        }

        val referrer = fetchFromPlayStore(context)
        if (referrer != null) {
            prefs.edit().putString(VALUE_KEY, referrer.value).putBoolean(DONE_KEY, true).apply()
            return referrer.value
        }
        return null
    }

    /** Wrapper so a definitive "no referrer" can cache too. */
    private class Result(val value: String?)

    private suspend fun fetchFromPlayStore(context: Context): Result? =
        suspendCancellableCoroutine { cont ->
            val client = InstallReferrerClient.newBuilder(context).build()
            try {
                client.startConnection(object : InstallReferrerStateListener {
                    override fun onInstallReferrerSetupFinished(responseCode: Int) {
                        when (responseCode) {
                            InstallReferrerClient.InstallReferrerResponse.OK -> {
                                val raw = try {
                                    client.installReferrer.installReferrer
                                } catch (_: Exception) {
                                    null
                                }
                                try { client.endConnection() } catch (_: Exception) {}
                                // "utm_source=google-play&utm_medium=organic" is the
                                // Play Store's own placeholder for organic installs —
                                // a real answer, cache it (as "nothing paid here").
                                val trimmed = raw?.trim()?.take(2048)
                                if (cont.isActive) cont.resume(Result(if (trimmed.isNullOrEmpty()) null else trimmed))
                            }
                            InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED,
                            InstallReferrerClient.InstallReferrerResponse.PERMISSION_ERROR -> {
                                // Permanent on this device — cache the absence.
                                try { client.endConnection() } catch (_: Exception) {}
                                if (cont.isActive) cont.resume(Result(null))
                            }
                            else -> {
                                // SERVICE_UNAVAILABLE / DEVELOPER_ERROR — transient,
                                // don't cache; next launch retries.
                                try { client.endConnection() } catch (_: Exception) {}
                                if (cont.isActive) cont.resume(null)
                            }
                        }
                    }

                    override fun onInstallReferrerServiceDisconnected() {
                        // Transient — retry next launch.
                        if (cont.isActive) cont.resume(null)
                    }
                })
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(null)
            }
            cont.invokeOnCancellation {
                try { client.endConnection() } catch (_: Exception) {}
            }
        }
}

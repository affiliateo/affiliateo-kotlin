package com.affiliateo.sdk

import android.content.Context

/**
 * The advertising identifiers this device may share.
 *
 * - [trackingAllowed]: false when the person turned ad personalisation off
 *   (Google calls it "limit ad tracking"), true otherwise, null when Google
 *   Play services could not be asked.
 * - [adId]: the Google advertising ID, only while tracking is allowed. A phone
 *   that has deleted or limited its id hands back zeros, which are never sent.
 *   Since Android 13 the app must also declare
 *   `com.google.android.gms.permission.AD_ID` in its manifest, or the id is
 *   zeros for everyone; the SDK does not declare it for you, because declaring
 *   it is a statement the app makes in its Play Store data safety answers.
 * - [metaAnonId]: the Meta SDK's own anonymous id, when the app ships the Meta
 *   SDK. It is what lets Meta tie a payment Affiliateo reports to the install
 *   Meta's SDK saw.
 * - [appPackage]: the package name, which Meta and Snapchat name the app by.
 *
 * Google Play services and the Meta SDK are found BY NAME, so there is no
 * dependency on either and nothing at all happens in apps without them.
 * [read] blocks on a Play services call, so it must run off the main thread.
 */
internal data class AdIdentifiers(
    val adId: String?,
    val trackingAllowed: Boolean?,
    val metaAnonId: String?,
    val appPackage: String?,
) {
    /** The fields as the server reads them. Absent values are left out (the queue drops nulls). */
    fun toMap(): Map<String, Any?> = mapOf(
        "ad_tracking_allowed" to trackingAllowed,
        "ad_id" to adId,
        "meta_anon_id" to metaAnonId,
        "app_package" to appPackage,
    )

    companion object {
        private const val ZERO_ID = "00000000-0000-0000-0000-000000000000"

        fun read(context: Context): AdIdentifiers {
            var adId: String? = null
            var allowed: Boolean? = null
            try {
                val client = Class.forName("com.google.android.gms.ads.identifier.AdvertisingIdClient")
                val info = client.getMethod("getAdvertisingIdInfo", Context::class.java).invoke(null, context)
                if (info != null) {
                    val limited = info.javaClass.getMethod("isLimitAdTrackingEnabled").invoke(info) as? Boolean
                    allowed = limited?.not()
                    val id = (info.javaClass.getMethod("getId").invoke(info) as? String)?.lowercase()
                    if (allowed == true && id != null && id != ZERO_ID) adId = id
                }
            } catch (_: Throwable) {
                // No Play services on this phone, or not in the app: no id.
            }

            val metaAnonId = try {
                val logger = Class.forName("com.facebook.appevents.AppEventsLogger")
                (logger.getMethod("getAnonymousAppDeviceGUID", Context::class.java).invoke(null, context) as? String)
                    ?.takeIf { it.isNotBlank() }
            } catch (_: Throwable) {
                null
            }

            return AdIdentifiers(
                adId = adId,
                trackingAllowed = allowed,
                metaAnonId = metaAnonId,
                appPackage = context.packageName,
            )
        }
    }
}

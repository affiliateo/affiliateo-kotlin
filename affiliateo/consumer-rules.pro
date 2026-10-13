# Rules that travel with the SDK into every app that uses it.
#
# The SDK finds RevenueCat by name while the app runs (Affiliateo.kt:
# setRevenueCatAttributes and readRevenueCatAppUserId), so that it needs no
# RevenueCat dependency of its own. RevenueCat's own rules let an app's
# shrinker (R8) rename that class. The lookup then finds nothing and says
# nothing: a shrunk release build stops tagging purchases with the affiliate
# and stops reporting the RevenueCat user id. These lines keep the three names
# the SDK looks up. In an app without RevenueCat they match nothing.
-keep class com.revenuecat.purchases.Purchases {
    public static com.revenuecat.purchases.Purchases getSharedInstance();
    public void setAttributes(java.util.Map);
    public java.lang.String getAppUserID();
}

# The same for the advertising ids (AdIdentifiers.kt, 4.9.0): the Google
# advertising ID comes from Google Play services and the Meta SDK's anonymous
# id from the Meta SDK, both looked up by name so the SDK depends on neither.
# Without these a shrunk release build renames them and the lookup quietly
# finds nothing. In an app without them they match nothing, and -dontwarn
# keeps R8 quiet about classes the app never ships.
-keep class com.google.android.gms.ads.identifier.AdvertisingIdClient {
    public static com.google.android.gms.ads.identifier.AdvertisingIdClient$Info getAdvertisingIdInfo(android.content.Context);
}
-keep class com.google.android.gms.ads.identifier.AdvertisingIdClient$Info {
    public java.lang.String getId();
    public boolean isLimitAdTrackingEnabled();
}
-keep class com.facebook.appevents.AppEventsLogger {
    public static java.lang.String getAnonymousAppDeviceGUID(android.content.Context);
}
-dontwarn com.google.android.gms.ads.identifier.**
-dontwarn com.facebook.appevents.**

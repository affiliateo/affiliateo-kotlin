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

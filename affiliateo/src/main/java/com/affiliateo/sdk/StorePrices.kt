package com.affiliateo.sdk

import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.GetBillingConfigParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.ProductDetailsResponseListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryProductDetailsResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Google Play's own price for the app's products, as this phone sees it
 * (4.13.0).
 *
 * The app's product cards on Affiliateo show each product's US price. An app
 * on RevenueCat or Adapty alone gives Affiliateo no way to read Google Play's
 * price list, so the phones report it: once a day, in the background, only on
 * a phone whose Play Store country is the US, and only for the products
 * Affiliateo asks about (the app's own store products). A price shows once
 * enough phones agree, so one phone never decides it.
 *
 * Play Billing is the APP's: the SDK compiles against Billing 8 and adds no
 * dependency. On an app without it, or with an older one whose listener has
 * another shape, nothing is reported and nothing breaks.
 *
 * Nothing about the person is sent: the product ids, their prices and
 * currency, and the store's country. Never blocks the app, never throws,
 * and does nothing while tracking is opted out. Display only: commission is
 * always computed off the amount paid.
 */
internal object StorePrices {
    /**
     * True once this phone's check is done for the day (reported, or nothing
     * to report); false on a failure worth trying again on the next launch.
     */
    suspend fun report(context: Context, client: AffiliateoClient, campaignId: String, deviceId: String): Boolean {
        if (!billingUsable()) return true
        return try {
            withTimeoutOrNull(60_000) { reportWithBilling(context, client, campaignId, deviceId) } ?: false
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Whether the app ships a Play Billing this code was built against: the
     * classes are there, and the product details listener takes Billing 8's
     * result. Compared by class, not by name, so a shrunk (R8) app still
     * answers right.
     */
    private fun billingUsable(): Boolean = try {
        val result = QueryProductDetailsResult::class.java
        ProductDetailsResponseListener::class.java.methods.any { method ->
            method.parameterTypes.size == 2 && method.parameterTypes[1] == result
        }
    } catch (_: Throwable) {
        false
    }

    private suspend fun reportWithBilling(context: Context, client: AffiliateoClient, campaignId: String, deviceId: String): Boolean {
        val billing = BillingClient.newBuilder(context.applicationContext)
            // Required by the builder; this client only reads, it never sells.
            .setListener { _, _ -> }
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()
        try {
            if (!connect(billing)) return false
            // The US Play Store only: Affiliateo shows the US price.
            val country = billingCountry(billing) ?: return false
            if (country != "US") return true
            val asked = client.storePriceProducts(campaignId, deviceId)
            if (!asked.report || asked.productIds.isEmpty()) return true

            val wanted = asked.productIds.map(StorePriceRules::split)
            val subscriptions = details(billing, wanted.map { it.productId }.distinct(), BillingClient.ProductType.SUBS)
            val oneTime = details(billing, wanted.filter { it.basePlanId == null }.map { it.productId }.distinct(), BillingClient.ProductType.INAPP)

            val prices = wanted.mapNotNull { key ->
                val sub = subscriptions[key.productId]?.let { basePlanPrice(it, key.basePlanId) }
                val one = if (key.basePlanId == null) oneTime[key.productId]?.let(::oneTimePrice) else null
                val (micros, currency) = sub ?: one ?: return@mapNotNull null
                if (!currency.equals("USD", ignoreCase = true)) return@mapNotNull null
                val cents = StorePriceRules.microsToCents(micros) ?: return@mapNotNull null
                StorePrice(key.original, cents, "USD")
            }
            if (prices.isEmpty()) return true
            client.reportStorePrices(campaignId, deviceId, "US", prices)
            return true
        } finally {
            try { billing.endConnection() } catch (_: Throwable) {}
        }
    }

    private suspend fun connect(billing: BillingClient): Boolean = suspendCancellableCoroutine { cont ->
        billing.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (cont.isActive) cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
            }
            override fun onBillingServiceDisconnected() {
                if (cont.isActive) cont.resume(false)
            }
        })
    }

    private suspend fun billingCountry(billing: BillingClient): String? = suspendCancellableCoroutine { cont ->
        billing.getBillingConfigAsync(GetBillingConfigParams.newBuilder().build()) { result, config ->
            val country = if (result.responseCode == BillingClient.BillingResponseCode.OK) config?.countryCode else null
            if (cont.isActive) cont.resume(country?.uppercase())
        }
    }

    private suspend fun details(billing: BillingClient, ids: List<String>, type: String): Map<String, ProductDetails> {
        if (ids.isEmpty()) return emptyMap()
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(ids.map { QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(type).build() })
            .build()
        return suspendCancellableCoroutine { cont ->
            billing.queryProductDetailsAsync(params) { result, found ->
                val list = if (result.responseCode == BillingClient.BillingResponseCode.OK) found.productDetailsList else emptyList()
                if (cont.isActive) cont.resume(list.associateBy { it.productId })
            }
        }
    }

    /**
     * A subscription's own recurring price: its base plan with no offer, the
     * phase that recurs for good. A product listed without a base plan is
     * priced only when it has exactly one.
     */
    private fun basePlanPrice(product: ProductDetails, basePlanId: String?): Pair<Long, String>? {
        val basePlans = product.subscriptionOfferDetails.orEmpty().filter { it.offerId == null }
        val plan = if (basePlanId != null) basePlans.firstOrNull { it.basePlanId == basePlanId } else basePlans.singleOrNull()
        val phase = plan?.pricingPhases?.pricingPhaseList
            ?.lastOrNull { it.recurrenceMode == ProductDetails.RecurrenceMode.INFINITE_RECURRING }
            ?: return null
        return phase.priceAmountMicros to phase.priceCurrencyCode
    }

    @Suppress("DEPRECATION")
    private fun oneTimePrice(product: ProductDetails): Pair<Long, String>? {
        val offer = product.oneTimePurchaseOfferDetails ?: return null
        return offer.priceAmountMicros to offer.priceCurrencyCode
    }
}

/** The pure rules behind [StorePrices], apart from Play Billing so they can be tested on the JVM. */
internal object StorePriceRules {
    /** At most once a day per app on this phone. */
    const val INTERVAL_MS: Long = 24L * 60 * 60 * 1000

    fun checkedKey(campaignId: String) = "affiliateo_store_prices_checked_at:$campaignId"

    /** Whether a day has passed since this app's last check on this phone. A clock set back does not lock it out. */
    fun isDue(lastCheckedMs: Long?, nowMs: Long): Boolean =
        lastCheckedMs == null || nowMs - lastCheckedMs >= INTERVAL_MS || nowMs < lastCheckedMs

    /** Play's micro-units as whole cents (6,990,000 -> 699), or null for none. */
    fun microsToCents(micros: Long): Int? {
        val cents = Math.round(micros / 10_000.0)
        return if (cents in 1..Int.MAX_VALUE) cents.toInt() else null
    }

    /**
     * A store id as Affiliateo lists it: a Play subscription as RevenueCat
     * names it ("subscription:base-plan"), or a plain product id.
     */
    data class Key(val original: String, val productId: String, val basePlanId: String?)

    fun split(key: String): Key {
        val at = key.indexOf(':')
        return if (at > 0 && at < key.length - 1) Key(key, key.substring(0, at), key.substring(at + 1)) else Key(key, key, null)
    }
}

/** One product's price as this phone's store lists it. */
internal data class StorePrice(val productId: String, val priceCents: Int, val currency: String)

/** What Affiliateo asks this phone to price today. */
internal data class StorePriceRequest(val productIds: List<String>, val report: Boolean)

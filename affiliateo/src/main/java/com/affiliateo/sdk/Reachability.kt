package com.affiliateo.sdk

/**
 * Whether the phone can reach the internet, from the networks Android
 * reports one by one (4.16.2). A phone often has two at once, Wi-Fi and
 * mobile data, and losing one leaves the other: the phone is offline when
 * the LAST usable network goes, not the first.
 *
 * Before 4.16.2 any one network dropping marked the phone offline, and only
 * a brand new network marked it online again, so after a switch from Wi-Fi
 * to mobile data the queue could sit unsent until the next network change
 * or app start.
 *
 * Starts online (the first callbacks arrive within moments of registering,
 * and a request that fails is retried anyway). Generic over the network so
 * it is checked without Android (ReachabilityTest).
 */
internal class Reachability<T : Any> {
    private val usable = mutableSetOf<T>()

    @Volatile var online: Boolean = true
        private set

    /** A network can reach the internet. Answers whether the phone was offline until now. */
    @Synchronized fun available(network: T): Boolean {
        val wasOffline = !online
        usable.add(network)
        online = true
        return wasOffline
    }

    /** A network went, or can no longer reach the internet. */
    @Synchronized fun lost(network: T) {
        usable.remove(network)
        online = usable.isNotEmpty()
    }
}

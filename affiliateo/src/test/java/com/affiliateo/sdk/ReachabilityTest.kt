package com.affiliateo.sdk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 4.16.2: the queue counts the phone offline only when its LAST usable
 * network goes, so losing Wi-Fi while mobile data is up keeps it sending.
 */
class ReachabilityTest {
    @Test
    fun startsOnline() {
        assertTrue(Reachability<String>().online)
    }

    @Test
    fun losingWifiWhileMobileDataIsUpStaysOnline() {
        val reach = Reachability<String>()
        reach.available("wifi")
        reach.available("mobile")
        reach.lost("wifi")
        assertTrue(reach.online)
    }

    @Test
    fun goesOfflineWhenTheLastNetworkGoesAndSaysWhenItComesBack() {
        val reach = Reachability<String>()
        assertFalse(reach.available("wifi"))
        reach.lost("wifi")
        assertFalse(reach.online)
        assertTrue(reach.available("mobile"))
        assertTrue(reach.online)
        assertFalse(reach.available("wifi"))
    }

    @Test
    fun aNetworkReportedTwiceIsStillOneNetwork() {
        val reach = Reachability<String>()
        reach.available("wifi")
        reach.available("wifi")
        reach.lost("wifi")
        assertFalse(reach.online)
    }

    @Test
    fun aLossOfANetworkNeverSeenLeavesTheOthersUp() {
        val reach = Reachability<String>()
        reach.available("mobile")
        reach.lost("wifi")
        assertTrue(reach.online)
    }
}

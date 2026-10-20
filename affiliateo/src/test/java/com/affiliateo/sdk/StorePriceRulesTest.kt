package com.affiliateo.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The store prices a US phone reports (4.13.0): whole cents, once a day, RevenueCat's Play ids read. */
class StorePriceRulesTest {
    @Test
    fun wholeCents() {
        assertEquals(699, StorePriceRules.microsToCents(6_990_000))
        assertEquals(5999, StorePriceRules.microsToCents(59_990_000))
        assertNull(StorePriceRules.microsToCents(0))
    }

    @Test
    fun onceADay() {
        val now = 1_800_000_000_000L
        assertTrue(StorePriceRules.isDue(null, now))
        assertFalse(StorePriceRules.isDue(now - 3_600_000, now))
        assertTrue(StorePriceRules.isDue(now - 25 * 3_600_000L, now))
        // A clock set back does not lock the check out.
        assertTrue(StorePriceRules.isDue(now + 3_600_000, now))
    }

    @Test
    fun readsRevenueCatsPlayIds() {
        assertEquals(StorePriceRules.Key("premium:yearly", "premium", "yearly"), StorePriceRules.split("premium:yearly"))
        assertEquals(StorePriceRules.Key("com.app.lifetime", "com.app.lifetime", null), StorePriceRules.split("com.app.lifetime"))
        assertEquals(StorePriceRules.Key("odd:", "odd:", null), StorePriceRules.split("odd:"))
    }
}

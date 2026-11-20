package com.affiliateo.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Affiliate codes typed inside the app (4.18.0): a coupon code without the
 * coupon. The rules are pure, so they are tested without a server; the
 * server's own half is the website's `affiliate-code/route.test.ts`.
 */
class AffiliateCodeTest {
    private fun refused(reason: AffiliateCode.Reason) = AffiliateCode(matched = false, reason = reason)

    @Test fun keepsACodeAsTypedMinusTheSpaceAroundItAndALeadingAt() {
        assertEquals("elliotalton", AffiliateCodeRules.normalize("elliotalton"))
        assertEquals("ElliotAlton", AffiliateCodeRules.normalize("  ElliotAlton \n"))
        assertEquals("elliotalton", AffiliateCodeRules.normalize("@elliotalton"))
        assertEquals("elliot", AffiliateCodeRules.normalize("@@ elliot"))
    }

    @Test fun refusesNothingASentenceAndAnythingTooLong() {
        for (raw in listOf(null, "", "   ", "@", "two words", "tab\there", "line\nbreak")) {
            assertNull(raw, AffiliateCodeRules.normalize(raw))
        }
        val longest = "a".repeat(AffiliateCodeRules.MAX_LENGTH)
        assertEquals(longest, AffiliateCodeRules.normalize(longest))
        assertNull(AffiliateCodeRules.normalize(longest + "a"))
    }

    @Test fun aMatchedCodeCarriesTheAffiliatesCode() {
        assertEquals(
            AffiliateCode(matched = true, refCode = "elliotalton"),
            AffiliateCodeRules.parseReply(200, """{"ok":true,"matched":true,"ref_code":"elliotalton"}"""),
        )
    }

    @Test fun aCodeNobodyHasIsNotFound() {
        assertEquals(refused(AffiliateCode.Reason.NOT_FOUND), AffiliateCodeRules.parseReply(200, """{"ok":true,"matched":false}"""))
    }

    @Test fun theOwnersSwitchOffTheDeviceUnknownAndABadCodeEachSayWhy() {
        assertEquals(refused(AffiliateCode.Reason.OFF), AffiliateCodeRules.parseReply(403, """{"error":"Affiliate codes are off for this app.","code":"affiliate_codes_off"}"""))
        assertEquals(refused(AffiliateCode.Reason.NOT_READY), AffiliateCodeRules.parseReply(404, """{"error":"Unknown device: call identify first.","code":"unknown_device"}"""))
        assertEquals(refused(AffiliateCode.Reason.INVALID), AffiliateCodeRules.parseReply(400, """{"error":"code required","code":"invalid_code"}"""))
    }

    @Test fun anythingElseIsAFailureWorthARetryNeverAMatch() {
        val failed = refused(AffiliateCode.Reason.FAILED)
        assertEquals(failed, AffiliateCodeRules.parseReply(404, """{"error":"App not found"}"""))
        assertEquals(failed, AffiliateCodeRules.parseReply(429, """{"error":"Too many requests"}"""))
        assertEquals(failed, AffiliateCodeRules.parseReply(500, """{"error":"Internal server error"}"""))
        assertEquals(failed, AffiliateCodeRules.parseReply(200, "not json"))
        assertEquals(failed, AffiliateCodeRules.parseReply(200, """{"ok":true,"matched":true}"""))
        assertEquals(failed, AffiliateCodeRules.parseReply(403, """{"error":"Forbidden"}"""))
        assertEquals(failed, AffiliateCodeRules.parseReply(500, null))
    }
}

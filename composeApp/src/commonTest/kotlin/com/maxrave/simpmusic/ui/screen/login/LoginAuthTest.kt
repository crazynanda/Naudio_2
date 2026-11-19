package com.maxrave.simpmusic.ui.screen.login

import com.maxrave.simpmusic.expect.ui.mergeCookieStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoginAuthTest {

    @Test
    fun hasYouTubeAuthCookies_returnsTrueForStandardAuthCookies() {
        val cookie = "SAPISID=sample_sapisid; SID=sample_sid; HSID=sample_hsid"
        assertTrue(hasYouTubeAuthCookies(cookie))
    }

    @Test
    fun hasYouTubeAuthCookies_returnsTrueForSecureVariants() {
        val cookie = "__Secure-3PAPISID=sample_3papisid; __Secure-3PSID=sample_3psid"
        assertTrue(hasYouTubeAuthCookies(cookie))

        val cookie1p = "__Secure-1PAPISID=sample_1papisid; LOGIN_INFO=sample_login_info"
        assertTrue(hasYouTubeAuthCookies(cookie1p))
    }

    @Test
    fun hasYouTubeAuthCookies_returnsFalseWhenSapisidMissing() {
        val cookie = "SID=sample_sid; HSID=sample_hsid; LOGIN_INFO=sample_login_info"
        assertFalse(hasYouTubeAuthCookies(cookie))
    }

    @Test
    fun hasYouTubeAuthCookies_returnsFalseWhenSessionCookieMissing() {
        val cookie = "SAPISID=sample_sapisid; VISITOR_INFO1_LIVE=xyz; YSC=abc"
        assertFalse(hasYouTubeAuthCookies(cookie))
    }

    @Test
    fun hasYouTubeAuthCookies_returnsFalseForBlankOrEmpty() {
        assertFalse(hasYouTubeAuthCookies(""))
        assertFalse(hasYouTubeAuthCookies("   "))
    }

    @Test
    fun mergeCookieStrings_mergesNonOverlappingCookies() {
        val first = "SAPISID=val1; SID=val2"
        val second = "__Secure-3PAPISID=val3; LOGIN_INFO=val4"
        val merged = mergeCookieStrings(first, second)

        assertTrue(merged.contains("SAPISID=val1"))
        assertTrue(merged.contains("SID=val2"))
        assertTrue(merged.contains("__Secure-3PAPISID=val3"))
        assertTrue(merged.contains("LOGIN_INFO=val4"))
    }

    @Test
    fun mergeCookieStrings_prefersFirstOnKeyCollision() {
        val first = "SID=primary_sid; SAPISID=val1"
        val second = "SID=secondary_sid; OTHER=val2"
        val merged = mergeCookieStrings(first, second)

        assertTrue(merged.contains("SID=primary_sid"))
        assertFalse(merged.contains("SID=secondary_sid"))
        assertTrue(merged.contains("OTHER=val2"))
    }

    @Test
    fun mergeCookieStrings_handlesEmptyOrBlankInputs() {
        val nonBlank = "SAPISID=val1; SID=val2"
        assertEquals(nonBlank, mergeCookieStrings(nonBlank, ""))
        assertEquals(nonBlank, mergeCookieStrings("", nonBlank))
        assertEquals(nonBlank, mergeCookieStrings(nonBlank, "   "))
        assertEquals("", mergeCookieStrings("", ""))
    }
}

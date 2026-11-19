package com.maxrave.simpmusic.ui.screen.login

import com.maxrave.common.Config
import com.maxrave.simpmusic.expect.ui.mergeCookieStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    @Test
    fun hostOfUrl_returnsTheHostOnly() {
        assertEquals("music.youtube.com", hostOfUrl("https://music.youtube.com/"))
        assertEquals("music.youtube.com", hostOfUrl("https://music.youtube.com"))
        assertEquals("music.youtube.com", hostOfUrl("https://music.youtube.com/?list=RDAMVM"))
        assertEquals("music.youtube.com", hostOfUrl("https://MUSIC.YouTube.com/"))
        assertEquals("accounts.google.com", hostOfUrl("https://accounts.google.com/ServiceLogin?x=1"))
        assertEquals("www.youtube.com", hostOfUrl("https://www.youtube.com/signin?action_handle_signin=true"))
        assertEquals("www.youtube.com", hostOfUrl("https://www.youtube.com:443/signin"))
        assertEquals("www.youtube.com", hostOfUrl("https://user:pass@www.youtube.com/signin"))
    }

    @Test
    fun hostOfUrl_returnsNullForAnythingThatIsNotAnAbsoluteHttpUrl() {
        assertNull(hostOfUrl(""))
        assertNull(hostOfUrl("   "))
        assertNull(hostOfUrl("music.youtube.com"))
        assertNull(hostOfUrl("/signin"))
        assertNull(hostOfUrl("ftp://music.youtube.com/"))
        assertNull(hostOfUrl("intent://music.youtube.com/#Intent;end"))
    }

    @Test
    fun hostOfUrl_isTheHostOfTheGoogleSignInPageAndNotTheYoutubeItCarries() {
        // The sign-in page carries "music.youtube.com" inside its percent encoded "continue"
        // parameter. A substring match on the whole URL used to match this page, which started
        // the cookie check before anything was signed in.
        assertTrue(Config.LOG_IN_URL.contains("music.youtube.com"))
        assertEquals("accounts.google.com", hostOfUrl(Config.LOG_IN_URL))
    }

    @Test
    fun cookieNames_readsTheNamesOnly() {
        val names = cookieNames("SAPISID=value; SID=other; PREF=f4=4000000&tz=UTC")

        assertEquals(setOf("SAPISID", "SID", "PREF"), names)
        assertFalse(names.any { it.contains("value") })
    }

    @Test
    fun sessionCookieNames_namesTheCookiesThatCarryTheSession() {
        val names = sessionCookieNames("__Secure-3PAPISID=a; __Secure-3PSID=b; PREF=c; VISITOR_INFO1_LIVE=d")

        assertEquals(setOf("__Secure-3PAPISID", "__Secure-3PSID"), names)
    }

    @Test
    fun hasYouTubeAuthCookies_isFalseForTheGuestCookiesOfTheJar() {
        // What the jar holds while the sign-in is still running: no session, so nothing to ask
        // YouTube about.
        assertFalse(hasYouTubeAuthCookies("PREF=f4=4000000; VISITOR_INFO1_LIVE=xyz; YSC=abc; SOCS=CAI"))
    }
}

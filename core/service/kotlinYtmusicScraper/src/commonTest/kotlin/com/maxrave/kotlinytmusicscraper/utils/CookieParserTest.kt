package com.maxrave.kotlinytmusicscraper.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CookieParserTest {

    @Test
    fun parseCookieString_parsesStandardCookies() {
        val input = "SAPISID=abc123xyz; SID=session_id_456; HSID=hsid_789"
        val parsed = parseCookieString(input)

        assertEquals("abc123xyz", parsed["SAPISID"])
        assertEquals("session_id_456", parsed["SID"])
        assertEquals("hsid_789", parsed["HSID"])
        assertEquals(3, parsed.size)
    }

    @Test
    fun parseCookieString_preservesEqualSignsInValues() {
        // Base64 padding and nested parameters contain '=' characters
        val input = "LOGIN_INFO=AFmmF2swRQIhAK12345==; PREF=f4=4000000&tz=UTC&f6=8; SIDCC=ABT2q7xyz=="
        val parsed = parseCookieString(input)

        assertEquals("AFmmF2swRQIhAK12345==", parsed["LOGIN_INFO"])
        assertEquals("f4=4000000&tz=UTC&f6=8", parsed["PREF"])
        assertEquals("ABT2q7xyz==", parsed["SIDCC"])
    }

    @Test
    fun parseCookieString_handlesVariousDelimitersAndWhitespace() {
        val input = "  SAPISID=token1;__Secure-3PAPISID=token2 ;   LOGIN_INFO=token3 ; "
        val parsed = parseCookieString(input)

        assertEquals("token1", parsed["SAPISID"])
        assertEquals("token2", parsed["__Secure-3PAPISID"])
        assertEquals("token3", parsed["LOGIN_INFO"])
    }

    @Test
    fun parseCookieString_ignoresMalformedTokensWithoutEquals() {
        val input = "HttpOnly; Secure; SAPISID=valid_sapisid; ; =no_name; SID=valid_sid"
        val parsed = parseCookieString(input)

        assertEquals("valid_sapisid", parsed["SAPISID"])
        assertEquals("valid_sid", parsed["SID"])
        assertFalse(parsed.containsKey("HttpOnly"))
        assertFalse(parsed.containsKey("Secure"))
    }

    @Test
    fun parseCookieString_handlesBlankOrEmptyString() {
        assertTrue(parseCookieString("").isEmpty())
        assertTrue(parseCookieString("   ").isEmpty())
        assertTrue(parseCookieString(";;;;").isEmpty())
    }
}

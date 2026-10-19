package com.maxrave.kotlinytmusicscraper

import java.util.*

// user.country is read from the OS locale when the JVM starts. Locale.getDefault() is not a
// reliable source: changeLanguageNative() calls Locale.setDefault() when the in-app language
// changes, which would otherwise move the region with it.
actual fun getCountry(): String =
    System.getProperty("user.country")?.takeIf { it.length == 2 }?.uppercase()
        ?: Locale.getDefault().country.takeIf { it.length == 2 }
        ?: "US"

actual fun getLanguage(): String =
    System.getProperty("user.language")?.takeIf { it.isNotEmpty() }
        ?: Locale.getDefault().language

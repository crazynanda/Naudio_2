package com.maxrave.kotlinytmusicscraper

import android.content.res.Resources
import java.util.*

// The system locale, not Locale.getDefault(): AppCompatDelegate.setApplicationLocales() changes
// the in-process default when the user picks an in-app language, which must not move the region.
actual fun getCountry(): String =
    Resources.getSystem().configuration.locales.get(0).country.takeIf { it.length == 2 }
        ?: Locale.getDefault().country.takeIf { it.length == 2 }
        ?: "US"

actual fun getLanguage(): String =
    Resources.getSystem().configuration.locales.get(0).language.takeIf { it.isNotEmpty() }
        ?: Locale.getDefault().language

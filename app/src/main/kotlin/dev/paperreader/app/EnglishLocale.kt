package dev.paperreader.app

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

internal fun Context.withEnglishLocale(): Context {
    val englishConfiguration = Configuration(resources.configuration).apply {
        setLocales(LocaleList(Locale.ENGLISH))
    }
    return createConfigurationContext(englishConfiguration)
}

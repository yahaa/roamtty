package io.itermux.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class AppLanguageTest {
    private fun withLocale(locale: Locale, check: () -> Unit) {
        val previous = Locale.getDefault()
        try { Locale.setDefault(locale); check() } finally { Locale.setDefault(previous) }
    }

    @Test fun firstLaunchUsesEnglishForNonChineseDevices() = withLocale(Locale.US) {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.from(null))
    }

    @Test fun firstLaunchUsesChineseForAllChineseLocales() = withLocale(Locale.TAIWAN) {
        assertEquals(AppLanguage.ZH_CN, AppLanguage.from(null))
    }

    @Test fun savedExplicitLanguageWinsOverDeviceLanguage() = withLocale(Locale.US) {
        assertEquals(AppLanguage.ZH_CN, AppLanguage.from("ZH_CN"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.from("ENGLISH"))
    }

    @Test fun unsupportedDeviceLanguageFallsBackToEnglish() = withLocale(Locale.FRANCE) {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.from(null))
    }
}

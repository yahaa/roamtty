package io.itermux.app

import android.content.ActivityNotFoundException
import android.content.Intent
import io.itermux.app.ui.browserLaunchIntent
import org.junit.Assert.*
import org.junit.Test

class BrowserLauncherTest {
    private val url = "https://github.com/yahaa/roamtty?x=1&y=2#readme"

    @Test fun availableDefaultBrowserWins() {
        val intent = browserLaunchIntent(url, listOf("system.browser", "com.android.chrome"), "com.android.chrome")
        assertEquals("com.android.chrome", intent.`package`)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(url, intent.dataString)
        assertNull(intent.selector)
    }

    @Test fun respectsAnotherAvailableDefaultBrowser() {
        val intent = browserLaunchIntent(url, listOf("firefox", "com.android.chrome"), "firefox")
        assertEquals("firefox", intent.`package`)
    }

    @Test fun unavailableDefaultFallsBackToTheOnlyAvailableBrowser() {
        val intent = browserLaunchIntent(url, listOf("com.android.chrome"), "suspended.system.browser")
        assertEquals("com.android.chrome", intent.`package`)
        assertEquals(url, intent.dataString)
    }

    @Test fun noDefaultShowsOnlyAvailableBrowsersInChooser() {
        val chooser = browserLaunchIntent(url, listOf("com.android.chrome", "firefox"), "android")
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val targets = listOf(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!) +
            chooser.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS)!!.map { it as Intent }
        assertEquals(listOf("com.android.chrome", "firefox"), targets.map { it.`package` })
        targets.forEach { assertEquals(url, it.dataString); assertNull(it.selector) }
    }

    @Test(expected = ActivityNotFoundException::class)
    fun noAvailableBrowserNeverLaunchesASuspendedDefault() {
        browserLaunchIntent(url, emptyList(), "suspended.system.browser")
    }
}

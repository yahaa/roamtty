package io.itermux.app.ui

import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import org.junit.Assert.*
import org.junit.Test

class BrowserTargetsTest {
    private fun browser(name: String, suspended: Boolean = false, enabled: Boolean = true) = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply {
            packageName = name
            exported = true
            this.enabled = true
            applicationInfo = ApplicationInfo().apply {
                packageName = name
                this.enabled = enabled
                flags = if (suspended) ApplicationInfo.FLAG_SUSPENDED else 0
            }
        }
    }

    @Test fun suspendedSystemBrowserDoesNotHideChrome() {
        assertEquals(listOf("com.android.chrome"), usableBrowserPackages(listOf(
            browser("system.browser", suspended = true), browser("com.android.chrome")
        )))
    }

    @Test fun disabledAndPrivateActivitiesAreExcluded() {
        assertEquals(listOf("active.browser"), usableBrowserPackages(listOf(
            browser("disabled.app", enabled = false),
            browser("disabled.activity").apply { activityInfo.enabled = false },
            browser("private.activity").apply { activityInfo.exported = false },
            browser("active.browser")
        )))
    }

    @Test fun duplicateAndIncompleteActivitiesDoNotBecomeBrowserChoices() {
        assertEquals(listOf("chrome", "firefox"), usableBrowserPackages(listOf(
            browser("chrome"), browser("chrome"), browser("firefox"), ResolveInfo()
        )))
    }

    @Test fun noUsableBrowserReturnsNoTargets() {
        assertTrue(usableBrowserPackages(listOf(browser("frozen", suspended = true))).isEmpty())
    }
}

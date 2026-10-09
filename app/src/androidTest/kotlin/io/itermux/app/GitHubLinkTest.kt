package io.itermux.app

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import io.itermux.app.ui.openTerminalUrl
import org.junit.Assert.*
import org.junit.Test

/** scripts/test-github-links.sh exercises explicit app/browser expectations using an isolated fixture. */
class GitHubLinkTest {
    private val github = "com.github.android"
    private val expected = InstrumentationRegistry.getArguments().getString("githubExpected")
    private val url = "https://github.com/yahaa/roamtty/pull/127?tab=files#discussion_r4228278412"

    private fun open(url: String, failure: RuntimeException? = null): List<Intent> {
        val launches = mutableListOf<Intent>()
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun startActivity(intent: Intent) {
                launches.add(Intent(intent))
                if (intent.`package` == github && failure != null) throw failure
                if (InstrumentationRegistry.getArguments().getString("githubLaunch") == "true") super.startActivity(intent)
            }
        }
        openTerminalUrl(context, url)
        return launches
    }

    @Suppress("DEPRECATION")
    private fun targets(intent: Intent): List<Intent> =
        if (intent.action != Intent.ACTION_CHOOSER) listOf(intent) else
            listOf(intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!) +
                intent.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS)!!.map { it as Intent }

    private fun assertExpectedHandler(intent: Intent) {
        when (expected) {
            "app" -> assertEquals(github, intent.`package`)
            "browser" -> assertNotEquals(github, intent.`package`)
        }
    }

    private fun assertOpensAddress(address: String) {
        val launch = open(address).single()
        assertExpectedHandler(launch)
        for (intent in targets(launch)) {
            assertEquals(address, intent.dataString)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
            assertNull(intent.selector)
        }
    }

    @Test fun githubPrLinkPrefersTheAvailableAppAndPreservesTheWholeAddress() = assertOpensAddress(url)

    @Test fun httpAndWwwLinksPreferTheAvailableApp() {
        assertOpensAddress(url.replace("https:", "http:"))
        assertOpensAddress(url.replace("github.com", "www.github.com"))
    }

    @Test fun otherHostsAndGithubLookalikesStayInTheBrowser() {
        for (address in listOf(
            "https://example.test/github.com", "https://github.com.example.test/",
            "https://github.com@example.test/", "https://example.test/?next=https://github.com",
            "https://raw.githubusercontent.com/yahaa/roamtty/main/README.md", "https://yahaa.github.io/"
        )) {
            for (intent in targets(open(address).single())) {
                assertNotEquals(github, intent.`package`)
                assertEquals(address, intent.dataString)
            }
        }
    }

    @Test fun githubLaunchFailureFallsBackToTheBrowserWithTheSameAddress() {
        for (failure in listOf(ActivityNotFoundException(), SecurityException())) {
            val launches = open(url, failure)
            assertExpectedHandler(launches.first())
            assertEquals(if (launches.first().`package` == github) 2 else 1, launches.size)
            for (intent in targets(launches.last())) {
                assertNotEquals(github, intent.`package`)
                assertEquals(url, intent.dataString)
            }
        }
    }
}

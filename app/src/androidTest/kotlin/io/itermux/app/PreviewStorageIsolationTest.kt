package io.itermux.app

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import io.itermux.app.ui.PreviewStorage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Two hosts' dev servers on the same port are one origin on the phone (`http://localhost:<port>`). A page previewed on
 * one host must not see the cookies or localStorage that a page on the other host left behind.
 */
@RunWith(AndroidJUnit4::class)
class PreviewStorageIsolationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val hostA = "test-a-${UUID.randomUUID()}"
    private val hostB = "test-b-${UUID.randomUUID()}"
    private lateinit var server: ServerSocket

    @Before fun serve() {
        assumeTrue("WebView without profiles", onMain { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) })
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (true) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                client.use {
                    val reader = it.getInputStream().bufferedReader()
                    while (reader.readLine()?.isNotEmpty() == true) Unit
                    val body = "<html><body>dev server</body></html>"
                    it.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${body.length}\r\n" +
                        "Connection: close\r\n\r\n$body").toByteArray())
                }
            }
        }
    }

    @After fun stop() {
        if (::server.isInitialized) server.close()
        onMain { PreviewStorage.forget(context, hostA); PreviewStorage.forget(context, hostB) }
    }

    @Test fun eachHostKeepsItsOwnCookiesAndStorage() {
        visit(hostA, "document.cookie='login=a; path=/'; localStorage.setItem('token','a'); 'set'")
        assertEquals("host A keeps its login across previews", "login=a|a", visit(hostA, READ))
        assertEquals("host B sees nothing of host A", "|", visit(hostB, READ))
        visit(hostB, "document.cookie='login=b; path=/'; localStorage.setItem('token','b'); 'set'")
        assertEquals("login=a|a", visit(hostA, READ))
        assertEquals("login=b|b", visit(hostB, READ))
    }

    @Test fun deletingAHostDropsWhatItsPagesKept() {
        visit(hostA, "document.cookie='login=a; path=/'; localStorage.setItem('token','a'); 'set'")
        assertEquals("login=a|a", visit(hostA, READ))
        onMain { PreviewStorage.forget(context, hostA) }
        assertEquals("|", visit(hostA, READ))
    }

    /** Opens the dev server's page as a preview of [host] would, runs [script] and returns its result. */
    private fun visit(host: String, script: String): String {
        val loaded = CountDownLatch(1)
        val result = CountDownLatch(1)
        var value = ""
        lateinit var view: WebView
        onMain {
            view = WebView(instrumentation.targetContext)
            PreviewStorage.attach(view, host)
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) { loaded.countDown() }
            }
            view.loadUrl("http://localhost:${server.localPort}/")
        }
        check(loaded.await(15, TimeUnit.SECONDS)) { "page did not load" }
        onMain {
            view.evaluateJavascript(script) { value = it.removeSurrounding("\""); result.countDown() }
        }
        check(result.await(15, TimeUnit.SECONDS)) { "script did not answer" }
        onMain {
            PreviewStorage.release(instrumentation.targetContext, view)
            view.destroy()
        }
        return value
    }

    private fun <T> onMain(block: () -> T): T {
        var value: Result<T>? = null
        instrumentation.runOnMainSync { value = runCatching(block) }
        return value!!.getOrThrow()
    }

    private companion object {
        const val READ = "document.cookie + '|' + (localStorage.getItem('token') || '')"
    }
}

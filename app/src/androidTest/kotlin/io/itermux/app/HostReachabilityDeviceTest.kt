package io.itermux.app

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.itermux.app.data.Host
import io.itermux.app.ui.HostCard
import io.itermux.app.ui.HostReachability
import io.itermux.app.ui.HostReachabilityTracker
import io.itermux.app.ui.ProbeHostReachability
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Exercises Android sockets, the foreground probe loop and the host card together, without a remote host. */
@RunWith(AndroidJUnit4::class)
class HostReachabilityDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun slowSshGreetingDoesNotInflateDisplayedTcpLatency() = withHost("SSH-2.0-fixture\r\n", 700) { host, tracker ->
        compose.waitUntil(5_000) { tracker.statusOf(host) is HostReachability.Online }
        val latency = (tracker.statusOf(host) as HostReachability.Online).latencyMs!!
        assertTrue("TCP connect time included the 700 ms greeting delay: $latency ms", latency in 0..349)
        compose.onNodeWithTag("host-card-${host.id}").assertTextContains("在线 ·", substring = true)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "host-tcp-latency.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun acceptedTcpConnectionWithTruncatedGreetingStillShowsOffline() = withHost("SSH-", 0) { host, tracker ->
        compose.waitUntil(5_000) { tracker.statusOf(host) == HostReachability.Offline }
        compose.onNodeWithTag("host-card-${host.id}").assertTextContains("离线")
    }

    private fun withHost(greeting: String, delayMs: Long, check: (Host, HostReachabilityTracker) -> Unit) {
        ServerSocket(0).use { server ->
            server.soTimeout = 5_000
            val worker = thread {
                runCatching {
                    server.accept().use { client ->
                        Thread.sleep(delayMs)
                        client.getOutputStream().write(greeting.toByteArray())
                    }
                }
            }
            val host = Host(name = "TCP latency fixture", address = "127.0.0.1", port = server.localPort, username = "test")
            val tracker = HostReachabilityTracker()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val prefs = context.getSharedPreferences("app-settings", Context.MODE_PRIVATE)
            val previousLanguage = prefs.getString("language", null)
            try {
                compose.runOnUiThread { AppLocale.set(context, AppLanguage.ZH_CN) }
                compose.setContent {
                    MaterialTheme {
                        ProbeHostReachability(tracker, listOf(host))
                        HostCard(host, {}, {}, {}, {}, reachability = tracker.statusOf(host))
                    }
                }
                check(host, tracker)
            } finally {
                worker.join()
                compose.runOnUiThread {
                    AppLocale.set(context, AppLanguage.from(previousLanguage))
                    if (previousLanguage == null) prefs.edit().remove("language").commit()
                }
            }
        }
    }
}

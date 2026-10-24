package io.itermux.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.itermux.app.data.Host
import io.itermux.app.desktop.DesktopThumbnails
import io.itermux.app.ui.HostCard
import io.itermux.app.ui.HostReachability
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import io.itermux.app.ui.AppShapes
import io.itermux.app.ui.AppTypography
import io.itermux.app.ui.DarkColors
import io.itermux.desktop.DesktopButton
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The hosts list's screen cards: what each part opens, and how a host with a picture, without one and without a desktop look. */
@RunWith(AndroidJUnit4::class)
class HostScreenCardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pictureOpensTheDesktopAndTheStripOrAHostWithoutOneOpensDetails() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pictured = Host(name = "pictured", address = "10.0.0.1", username = "me", os = "UBUNTU", plugins = "tmux,mosh,desktop")
        val offline = Host(name = "offline", address = "10.0.0.2", username = "me", os = "MACOS")
        val headless = Host(name = "headless", address = "10.0.0.3", username = "me", os = "DEBIAN", plugins = "tmux")
        DesktopThumbnails.save(context, pictured.id, gradient())
        val opened = mutableListOf<String>()
        val prefs = context.getSharedPreferences("app-settings", Context.MODE_PRIVATE)
        val previousLanguage = prefs.getString("language", null)
        try {
            compose.runOnUiThread { AppLocale.set(context, AppLanguage.ZH_CN) }
            compose.setContent {
                MaterialTheme(colorScheme = DarkColors, shapes = AppShapes, typography = AppTypography) {
                    Column(Modifier.background(DarkColors.background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        HostCard(pictured, { opened += "detail:pictured" }, {}, {}, {}, reachability = HostReachability.Online(42),
                            desktop = DesktopButton.CONNECTED, openDesktop = { opened += "desktop:pictured" })
                        HostCard(offline, { opened += "detail:offline" }, {}, {}, {}, reachability = HostReachability.Offline,
                            desktop = DesktopButton.OPENABLE, openDesktop = { opened += "desktop:offline" })
                        HostCard(headless, { opened += "detail:headless" }, {}, {}, {}, reachability = HostReachability.Online(7),
                            openDesktop = { opened += "desktop:headless" })
                    }
                }
            }
            compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("已连接").assertIsDisplayed() }.isSuccess }
            compose.onNodeWithText("没有图形桌面").assertIsDisplayed()
            compose.onNodeWithText("me@10.0.0.1:22").assertIsDisplayed()
            // The strip names what carries the sessions, the desktop plugin aside.
            compose.onNodeWithTag("host-plugin-tmux-${pictured.id}", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("host-plugin-mosh-${pictured.id}", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("host-plugin-desktop-${pictured.id}", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag("host-plugin-tmux-${headless.id}", useUnmergedTree = true).assertIsDisplayed()
            File(context.getExternalFilesDir(null), "host-screen-cards.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.onNodeWithTag("host-screen-${pictured.id}").performClick()
            compose.onNodeWithTag("host-card-${pictured.id}").performClick()
            // An offline host's picture cannot open its desktop; without a desktop the picture leads to the details too.
            compose.onNodeWithTag("host-screen-${offline.id}").performClick()
            compose.onNodeWithTag("host-screen-${headless.id}").performClick()
            compose.onNodeWithTag("host-card-${headless.id}").performClick()
            compose.runOnIdle {
                assertEquals(listOf("desktop:pictured", "detail:pictured", "detail:offline", "detail:headless", "detail:headless"), opened)
            }
        } finally {
            DesktopThumbnails.remove(context, pictured.id)
            compose.runOnUiThread {
                AppLocale.set(context, AppLanguage.from(previousLanguage))
                if (previousLanguage == null) prefs.edit().remove("language").commit()
            }
        }
    }

    /** A stand-in for a desktop: a purple-to-orange wash with a light window. */
    private fun gradient(): Bitmap = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = Canvas(bitmap)
        canvas.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 640f, 400f, 0xFF3A1E4A.toInt(), 0xFFE95420.toInt(), Shader.TileMode.CLAMP) })
        canvas.drawRect(120f, 80f, 420f, 280f, Paint().apply { color = 0xEEF2F2F2.toInt() })
    }
}

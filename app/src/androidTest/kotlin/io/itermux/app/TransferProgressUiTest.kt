package io.itermux.app

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.itermux.app.ui.TransferStatusDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TransferProgressUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun showsMeasuredProgressInEnglish() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("app-settings", Context.MODE_PRIVATE)
        val previous = prefs.getString("language", null)
        try {
            compose.runOnUiThread { AppLocale.set(context, AppLanguage.ENGLISH) }
            compose.setContent {
                MaterialTheme {
                    TransferStatusDialog("Uploading sample.bin", 512 * 1024L, 1024 * 1024L,
                        true, null, onCancel = {}, onDismiss = {})
                }
            }
            compose.onNodeWithTag("transfer-progress").assertIsDisplayed()
            compose.onNodeWithTag("transfer-status").assertTextContains("512 KiB / 1024 KiB")
            compose.onNodeWithText("Cancel transfer").assertIsDisplayed()
            File(context.getExternalFilesDir(null), "transfer-progress-en.png").outputStream().use {
                compose.onNodeWithTag("transfer-dialog").captureToImage().asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            assertEquals("ENGLISH", prefs.getString("language", null))
        } finally {
            compose.runOnUiThread {
                AppLocale.set(context, AppLanguage.from(previous))
                if (previous == null) prefs.edit().remove("language").commit()
            }
        }
    }
}

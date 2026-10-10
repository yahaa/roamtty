package io.itermux.app

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LanguageSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun languageSwitchUpdatesTheAppAndPersists() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("app-settings", Context.MODE_PRIVATE)
        val previous = prefs.getString("language", null)
        try {
            compose.runOnUiThread { AppLocale.set(context, AppLanguage.ZH_CN) }
            compose.onNodeWithTag("brand-logo").performClick()
            compose.onNodeWithTag("drawer-settings").performClick()
            compose.onNodeWithText("语言").assertIsDisplayed()
            compose.onNodeWithTag("settings-language-english").performClick()
            compose.onNodeWithText("Language").assertIsDisplayed()
            compose.onNodeWithText("Appearance").assertIsDisplayed()
            assertEquals("ENGLISH", prefs.getString("language", null))
            File(context.getExternalFilesDir(null), "language-settings-en.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.onNodeWithTag("close-settings").performClick()
            compose.onNodeWithContentDescription("Search hosts").assertIsDisplayed()
            compose.onNodeWithTag("brand-logo").performClick()
            compose.onNodeWithTag("drawer-guide").performClick()
            compose.onNodeWithText("Sessions and AI agents").assertIsDisplayed()
            compose.onNodeWithText("Close").performClick()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithContentDescription("Search hosts").assertIsDisplayed()
            compose.onNodeWithTag("brand-logo").performClick()
            compose.onNodeWithTag("drawer-settings").performClick()
            compose.onNodeWithTag("settings-language-zh_cn").performClick()
            compose.onNodeWithText("外观").assertIsDisplayed()
            compose.onNodeWithText("语言").assertIsDisplayed()
        } finally {
            compose.runOnUiThread {
                AppLocale.set(context, AppLanguage.from(previous))
                if (previous == null) prefs.edit().remove("language").commit()
            }
        }
    }
}

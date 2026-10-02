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
import io.itermux.app.session.DownloadItem
import io.itermux.app.session.DownloadPhase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TransferProgressUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun successfulDownloadKeepsTheSummaryShortAndExpandsItsFullPath() {
        val path = "/storage/emulated/0/Documents/" + "long-directory/".repeat(5) + "app.apk"
        compose.setContent {
            MaterialTheme {
                TransferStatusDialog("app.apk", 1024, 1024, false, tr("传输完成"), {}, {},
                    downloads = listOf(DownloadItem("/work/dist/app.apk", 1024, 1024,
                        DownloadPhase.COMPLETE, localPath = path)))
            }
        }
        compose.onNodeWithText(tr("下载完成")).assertIsDisplayed()
        compose.onNodeWithTag("transfer-count").assertDoesNotExist()
        compose.onNodeWithText(tr("已完成")).assertDoesNotExist()
        compose.onNodeWithText(trf("来源目录：%s", "/work/dist")).assertDoesNotExist()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "download-result-compact.png").outputStream().use {
            compose.onNodeWithTag("transfer-dialog").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithTag("download-result-details").performClick()
        compose.onNodeWithText(trf("来源目录：%s", "/work/dist")).assertIsDisplayed()
        compose.onNodeWithTag("download-local-path-/work/dist/app.apk").assertTextEquals(trf("下载路径：%s", path))
    }

    @Test fun batchProgressShowsCountsPercentageSpeedAndPerFileStates() {
        compose.setContent {
            MaterialTheme {
                TransferStatusDialog("", 512 * 1024L, 1024 * 1024L, true, null, {}, {},
                    downloads = listOf(
                        DownloadItem("/notes/README.md", 200, 200, DownloadPhase.COMPLETE),
                        DownloadItem("/reports/quarterly-report.pdf", 1024 * 1024L, 512 * 1024L, DownloadPhase.RUNNING),
                        DownloadItem("/images/photo.png", 4 * 1024 * 1024L)), bytesPerSecond = 256 * 1024.0)
            }
        }
        compose.onNodeWithTag("transfer-count").assertTextEquals(trf("已完成 %d/%d · 失败 %d", 1, 3, 0))
        compose.onNodeWithTag("transfer-percent").assertTextEquals("50%")
        compose.onNodeWithTag("transfer-speed").assertTextEquals("256 KiB/s")
        compose.onNodeWithTag("transfer-overall-progress").assertIsDisplayed()
        compose.onNodeWithText(tr("取消全部")).assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "download-batch-progress.png").outputStream().use {
            compose.onNodeWithTag("transfer-dialog").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

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
            compose.onNodeWithTag("transfer-status").assertTextContains("512 KiB / 1.0 MiB")
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

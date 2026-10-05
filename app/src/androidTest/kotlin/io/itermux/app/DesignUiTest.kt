package io.itermux.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.itermux.app.session.CommonCommandsState
import io.itermux.app.session.ImageInsert
import io.itermux.app.session.ImageOutcome
import io.itermux.app.session.TransferStatus
import io.itermux.app.ui.*
import io.itermux.ssh.RemoteEntry
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DesignUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var originalFont = TerminalFontId.DEFAULT

    @Before fun rememberFont() { originalFont = TerminalFonts.selection }
    @After fun restoreFont() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { TerminalFonts.setSelection(context, originalFont) }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun commonCommandsRestoreSearchAndSelectFullTextWithoutExecuting() {
        val command = "git log --oneline --decorate --all --graph --since='2026-09-01' --author='Design review'"
        val commands = listOf("git status", command, "npm run build", "docker compose up -d", "ls -lah")
        var selected: String? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme(colorScheme = DarkColors) {
                CommonCommandsContent(CommonCommandsState.Ready(commands, "~/.bash_history"), true, {}, { selected = it }, {})
            }
        }
        compose.onNodeWithTag("common-commands-list").performScrollToNode(hasTestTag("common-commands-search"))
        compose.onNodeWithTag("common-commands-search").performTextInput("git")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("common-commands-search").assertTextContains("git")
        screenshot("design-commands-dark")
        compose.onNodeWithTag("common-commands-list").performScrollToNode(hasTestTag("common-command-$command"))
        compose.onNodeWithTag("common-command-$command").performClick()
        assertEquals(command, selected)
        assertFalse(selected!!.endsWith("\r"))
    }

    @Test fun fileActionsRemainReachableWithLargeTextAndHaveAccessibleLabels() {
        var open by mutableStateOf(false)
        var renamed = false
        val entry = RemoteEntry("Project notes — September 2026.md", false, 8240, 1790800000, "-rw-r--r--")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme(colorScheme = LightColors) {
                    RemoteFileRow(entry, onOpen = { open = true }, onMenu = { open = true }) {
                        FileActionsMenu(entry, open, false, {}, {}, { renamed = true; open = false }, {}, { open = false })
                    }
                }
            }
        }
        compose.onNodeWithContentDescription(trf("文件操作：%s", entry.name)).assertHasClickAction().performClick()
        compose.onNodeWithTag("files-action-delete").performScrollTo()
        compose.onNodeWithTag("files-action-delete").assertIsDisplayed()
        screenshot("design-file-actions-large-text")
        compose.onNodeWithTag("files-action-rename").performScrollTo()
        compose.onNodeWithTag("files-action-rename").performClick()
        assertTrue(renamed)
    }

    @Test fun fontPickerShowsLivePreviewAndSelection() {
        compose.setContent {
            MaterialTheme(colorScheme = DarkColors) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.safeDrawingPadding()) { SettingsPage(AppTheme.DARK, {}, AppLanguage.ZH_CN, {}) }
                }
            }
        }
        compose.onNodeWithTag("settings-font-open").performScrollTo().performClick()
        compose.onNodeWithTag("font-options").performScrollToNode(hasTestTag("settings-font-fira_code"))
        compose.onNodeWithTag("settings-font-fira_code").performClick()
        assertEquals(TerminalFontId.FIRA_CODE, TerminalFonts.selection)
        screenshot("design-font-picker-dark")
        compose.onNodeWithTag("font-options").performScrollToNode(hasTestTag("settings-font-source_code_pro"))
        compose.onNodeWithTag("settings-font-source_code_pro").assertIsDisplayed()
    }

    @Test fun imageInsertDialogShowsBatchProgressAndEachImageOutcome() {
        val first = ImageInsert("IMG_20261005_0901.jpg", ImageOutcome.INSERTED, "/tmp/roamtty-img-a.png")
        var status by mutableStateOf(TransferStatus(tr("插入图片"), 1_200_000, 2_400_000, running = true, imageTotal = 3,
            images = listOf(first)))
        compose.setContent {
            MaterialTheme(colorScheme = DarkColors) { ImageInsertDialog(status, {}, {}) }
        }
        compose.onNodeWithTag("image-insert-count").assertTextEquals(trf("第 %d 张，共 %d 张", 2, 3))
        screenshot("design-image-insert-running")
        status = status.copy(running = false, succeeded = true, result = tr("传输完成"), images = listOf(first,
            ImageInsert("Screenshot_2026-10-05.png", ImageOutcome.IN_EDITOR, "/tmp/roamtty-img-b.png"),
            ImageInsert("IMG_20261005_0915.heic", ImageOutcome.FAILED, error = "无法解码图片")))
        compose.onNodeWithTag("image-insert-count").assertTextEquals(listOf(trf("成功 %d 张", 2), trf("失败 %d 张", 1)).joinToString(" · "))
        compose.onNodeWithTag("image-insert-row-1").assert(hasAnyDescendant(hasText(tr("已放入编辑区"))))
        compose.onNodeWithTag("image-insert-row-2").assert(hasAnyDescendant(hasText("无法解码图片")))
        Thread.sleep(300) // The dialog window draws a frame after the semantics update.
        screenshot("design-image-insert-results")
    }
}

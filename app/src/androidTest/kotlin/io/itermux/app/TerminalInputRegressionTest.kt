package io.itermux.app

import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.text.SpannableString
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.SilentTerminalClient
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import io.itermux.app.ui.MobileTerminalClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Exercise the actual Android view/IME boundary while recording the bytes it sends. */
class TerminalInputRegressionTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var terminal: TerminalView
    private var ctrlArmed = false
    private var shiftArmed = false
    private var softEnters = 0
    private val session = RecordingSession()
    private var downTime = 0L

    private class RecordingSession : TerminalSession {
        val input = StringBuilder()
        private val emulator = TerminalEmulator(object : TerminalOutput() {
            override fun write(data: ByteArray, offset: Int, count: Int) {
                input.append(String(data, offset, count, Charsets.UTF_8))
            }
            override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
            override fun onCopyTextToClipboard(text: String?) = Unit
            override fun onPasteTextFromClipboard() = Unit
            override fun onBell() = Unit
            override fun onColorsChanged() = Unit
        }, 80, 24, 8, 16, 400, SilentTerminalClient())
        override fun getEmulator() = emulator
        override fun updateSize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
            emulator.resize(columns, rows, cellWidthPixels, cellHeightPixels)
        }
        override fun write(data: String) { input.append(data) }
        override fun writeCodePoint(prependEscape: Boolean, codePoint: Int) {
            if (prependEscape) input.append('\u001b')
            input.append(String(Character.toChars(codePoint)))
        }
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun reset() = Unit
    }

    private fun terminalContent() {
        compose.setContent {
            MaterialTheme {
                AndroidView(modifier = Modifier.fillMaxSize().testTag("input-regression-terminal"), factory = { context ->
                    TerminalView(context, null).also { view ->
                        terminal = view
                        view.setTextSize(36)
                        view.setTypeface(Typeface.MONOSPACE)
                        view.isFocusableInTouchMode = true
                        view.setTerminalViewClient(MobileTerminalClient(view, {}, { ctrlArmed }, { ctrlArmed = false },
                            { shiftArmed }, { shiftArmed = false },
                            onSwipeLeft = { view.sendInputControlKey(KeyEvent.KEYCODE_DPAD_LEFT) },
                            onSwipeHoldLeft = { view.sendInputText("\u0001") },
                            onSwipeHoldRight = { view.sendInputText("\u0005") },
                            onSoftKeyboardEnter = { softEnters++ }))
                        view.attachSession(session)
                    }
                })
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { terminal.requestFocus() }
    }

    private fun connection(): InputConnection = compose.runOnIdle { terminal.onCreateInputConnection(EditorInfo()) }

    private fun touch(action: Int, x: Float, y: Float) = compose.runOnIdle {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        MotionEvent.obtain(downTime, now, action, x, y, 0).also {
            it.source = InputDevice.SOURCE_TOUCHSCREEN
            terminal.onTouchEvent(it)
            it.recycle()
        }
    }

    private fun dragAndHold(dx: Float) {
        val (x, y) = compose.runOnIdle { terminal.width / 2f to terminal.height / 2f }
        touch(MotionEvent.ACTION_DOWN, x, y)
        for (step in 1..10) {
            touch(MotionEvent.ACTION_MOVE, x + dx * step / 10, y)
            SystemClock.sleep(10)
        }
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 150L)
        touch(MotionEvent.ACTION_UP, x + dx, y)
        compose.waitForIdle()
    }

    private fun swipeLeft() {
        compose.onNodeWithTag("input-regression-terminal").performTouchInput {
            swipeLeft(startX = center.x + 100, endX = center.x - 100, durationMillis = 150)
        }
    }

    @Test fun localHistoryNeverSendsSwipeInputOrLeavesTheHistoryPosition() {
        terminalContent()
        compose.runOnIdle { terminal.setTopRow(-10) }
        swipeLeft()
        dragAndHold(-200f)
        dragAndHold(200f)
        compose.runOnIdle {
            assertEquals("", session.input.toString())
            assertEquals(-10, terminal.topRow)
            terminal.setTopRow(0)
        }
        swipeLeft()
        compose.runOnIdle { assertEquals("\u001b[D", session.input.toString()) }
    }

    @Test fun swipeMustStartAndEndInTheSameLiveViewport() {
        terminalContent()
        val (x, y) = compose.runOnIdle { terminal.width / 2f to terminal.height / 2f }
        compose.runOnIdle { terminal.setTopRow(-10) }
        touch(MotionEvent.ACTION_DOWN, x, y)
        touch(MotionEvent.ACTION_MOVE, x - 200, y)
        compose.runOnIdle { terminal.setTopRow(0) }
        touch(MotionEvent.ACTION_UP, x - 200, y)
        compose.runOnIdle { assertEquals("", session.input.toString()) }

        val next = RecordingSession()
        touch(MotionEvent.ACTION_DOWN, x, y)
        touch(MotionEvent.ACTION_MOVE, x - 200, y)
        compose.runOnIdle { terminal.attachSession(next) }
        touch(MotionEvent.ACTION_UP, x - 200, y)
        compose.runOnIdle {
            assertEquals("", session.input.toString())
            assertEquals("", next.input.toString())
        }
    }

    @Test fun allImeDeleteEntrypointsKeepACompositionLocalPastTheHoldThreshold() {
        terminalContent()
        for (mode in 0..2) {
            val ime = connection()
            compose.runOnIdle { ime.setComposingText("p".repeat(32), 1) }
            repeat(12) {
                compose.runOnIdle {
                    when (mode) {
                        0 -> ime.deleteSurroundingText(1, 0)
                        1 -> ime.deleteSurroundingTextInCodePoints(1, 0)
                        else -> ime.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                    }
                }
                SystemClock.sleep(100)
            }
            compose.runOnIdle {
                assertEquals("IME route $mode must never clear or delete remote text", "", session.input.toString())
                assertTrue(ime.getExtractedText(ExtractedTextRequest(), 0).text.isNotEmpty())
            }
        }
    }

    @Test fun clearAllOnlyDiscardsCompositionAndStartsANewRemoteDeleteTimer() {
        terminalContent()
        val ime = connection()
        compose.runOnIdle {
            ime.setComposingText("拼音🙂", 1)
            assertTrue(ime.deleteSurroundingText(Int.MAX_VALUE, 0))
            assertTrue(ime.getExtractedText(ExtractedTextRequest(), 0).text.isBlank())
            assertEquals("", session.input.toString())
            ime.setComposingText("另一个拼音", 1)
            assertTrue(ime.deleteSurroundingTextInCodePoints(Int.MAX_VALUE, Int.MAX_VALUE))
            assertEquals("", session.input.toString())
            ime.deleteSurroundingText(1, 0)
            assertEquals("\u007f", session.input.toString())
            ime.commitText("c", 1)
            assertEquals("\u007fc", session.input.toString())
        }
    }

    @Test fun localCodePointDeletionPreservesSurrogatesWithoutSendingRemoteKeys() {
        terminalContent()
        val ime = connection()
        compose.runOnIdle {
            ime.setComposingText("中文🙂", 1)
            // Text outside the composing span is still uncommitted in this connection's Editable.
            BaseInputConnection.removeComposingSpans(checkNotNull((ime as BaseInputConnection).editable))
            ime.deleteSurroundingTextInCodePoints(1, 0)
            assertEquals("中文", checkNotNull((ime as BaseInputConnection).editable).toString())
            val extracted = checkNotNull(ime.getExtractedText(ExtractedTextRequest(), 0))
            assertTrue(extracted.text.toString().endsWith("中文"))
            assertEquals(extracted.text.length, extracted.selectionStart)
            assertEquals("", session.input.toString())
            ime.finishComposingText()
            assertEquals("中文", session.input.toString())
        }
    }

    @Test fun ordinaryLocalDeletesStartTheHoldTimerOnlyAfterTheEditableBecomesEmpty() {
        terminalContent()
        val ime = connection()
        compose.runOnIdle {
            ime.setComposingText("x".repeat(13), 1)
            BaseInputConnection.removeComposingSpans(checkNotNull((ime as BaseInputConnection).editable))
            repeat(13) {
                ime.deleteSurroundingText(1, 0)
                assertEquals("Local deletes must not spill into remote input", "", session.input.toString())
                SystemClock.sleep(100)
            }
            assertTrue(checkNotNull(ime.getExtractedText(ExtractedTextRequest(), 0)).text.isBlank())

            val remoteStarted = SystemClock.uptimeMillis()
            ime.deleteSurroundingText(1, 0)
            assertEquals("The first remote delete starts a fresh hold", "\u007f", session.input.toString())
            repeat(5) {
                SystemClock.sleep(100)
                ime.deleteSurroundingText(1, 0)
            }
            assertFalse("Local delete time must not count toward remote clear", session.input.contains('\u0015'))
            while (SystemClock.uptimeMillis() - remoteStarted < 1200) {
                SystemClock.sleep(100)
                ime.deleteSurroundingText(1, 0)
            }
            assertTrue("The continuing remote hold still clears", session.input.endsWith("\u0015\u000b"))
            assertEquals(1, session.input.count { it == '\u0015' })
        }
    }

    @Test fun remoteImeDeletesAndClearConsumeStickyModifiersExactlyOnce() {
        terminalContent()
        val ime = connection()
        compose.runOnIdle {
            ctrlArmed = true
            shiftArmed = true
            ime.deleteSurroundingText(1, 0)
            assertEquals("\u0008", session.input.toString())
            assertFalse(ctrlArmed)
            assertFalse(shiftArmed)
            ime.commitText("c", 1)
            assertEquals("\u0008c", session.input.toString())
            session.input.clear()
            ctrlArmed = true
            shiftArmed = true
            ime.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            assertEquals("\u0008", session.input.toString())
            assertFalse(ctrlArmed)
            assertFalse(shiftArmed)
            ime.commitText("c", 1)
            assertEquals("\u0008c", session.input.toString())
            session.input.clear()
            ctrlArmed = true
            shiftArmed = true
            ime.deleteSurroundingTextInCodePoints(0, 1)
            assertEquals("\u001b[3;6~", session.input.toString())
            assertFalse(ctrlArmed)
            assertFalse(shiftArmed)
            ime.commitText("a", 1)
            assertTrue(session.input.endsWith("a"))
            session.input.clear()
            ctrlArmed = true
            shiftArmed = true
            ime.deleteSurroundingText(Int.MAX_VALUE, 0)
            assertEquals("\u0015\u000b", session.input.toString())
            assertFalse(ctrlArmed)
            assertFalse(shiftArmed)
            ime.commitText("c", 1)
            assertEquals("\u0015\u000bc", session.input.toString())
        }
    }

    @Test fun remoteLongPressStillClearsOnceAndSuppressesItsRemainingDeletes() {
        terminalContent()
        val ime = connection()
        repeat(13) {
            compose.runOnIdle { ime.deleteSurroundingText(1, 0) }
            SystemClock.sleep(100)
        }
        compose.runOnIdle {
            val sent = session.input.toString()
            assertTrue(sent.startsWith("\u007f"))
            assertTrue(sent.endsWith("\u0015\u000b"))
            assertEquals(1, sent.count { it == '\u0015' })
            ime.commitText("x", 1)
            assertTrue(session.input.endsWith("\u0015\u000bx"))
        }
    }

    @Test fun oldInputConnectionsCannotFinishCommitDeleteOrSendKeysToAnotherSession() {
        terminalContent()
        val old = connection()
        val next = RecordingSession()
        compose.runOnIdle {
            old.setComposingText("old pinyin", 1)
            terminal.attachSession(next)
            val current = terminal.onCreateInputConnection(EditorInfo())
            current.setComposingText("new", 1)
            assertFalse(old.finishComposingText())
            assertFalse(old.commitText("wrong", 1))
            assertFalse(old.deleteSurroundingText(Int.MAX_VALUE, 0))
            assertFalse(old.deleteSurroundingTextInCodePoints(1, 0))
            assertFalse(old.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
            assertFalse(old.setComposingText("wrong", 1))
            assertFalse(old.setComposingRegion(64, 67))
            assertFalse(old.setSelection(64, 67))
            assertEquals("new", checkNotNull((current as BaseInputConnection).editable).toString())
            assertTrue(checkNotNull(current.getExtractedText(ExtractedTextRequest(), 0)).text.toString().endsWith("new"))
            assertEquals("", session.input.toString())
            assertEquals("", next.input.toString())
            current.finishComposingText()
            current.finishComposingText()
            assertEquals("new", next.input.toString())
        }
    }

    @Test fun switchingKeyboardsKeepsWhicheverConnectionOfTheSessionTheImeUses() {
        terminalContent()
        compose.runOnIdle {
            // Android creates several connections during a keyboard switch and may hand the IME an earlier one.
            val used = terminal.onCreateInputConnection(EditorInfo())
            terminal.onCreateInputConnection(EditorInfo())
            assertTrue(used.commitText("typed", 1))
            assertTrue(used.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)))
            assertTrue(used.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL)))
            assertEquals("typed\u007f", session.input.toString())
            session.input.clear()
            // Clearing the line discards the composition in the connection the IME is using.
            used.setComposingText("pin", 1)
            terminal.clearCurrentInput()
            assertTrue(used.getExtractedText(ExtractedTextRequest(), 0).text.isBlank())
        }
    }

    @Test fun aDetachedKeyboardCannotFinishCommitOrDeleteAfterANewBatchOfConnections() {
        terminalContent()
        compose.runOnIdle {
            val old = terminal.onCreateInputConnection(EditorInfo())
            old.setComposingText("old", 1)
            // Switch or restart: a new batch is created before the new keyboard calls in.
            val first = terminal.onCreateInputConnection(EditorInfo())
            val second = terminal.onCreateInputConnection(EditorInfo())
            assertFalse(old.finishComposingText())
            assertFalse(old.commitText("wrong", 1))
            assertFalse(old.deleteSurroundingText(1, 0))
            assertTrue(second.commitText("valid", 1))
            // Once the new keyboard is in, the rest of its batch is not reopened by a later batch either.
            terminal.onCreateInputConnection(EditorInfo())
            assertFalse(first.commitText("wrong", 1))
            assertEquals("valid", session.input.toString())
        }
    }

    @Test fun deleteWorksForImesThatCheckTheTextBeforeTheCursorFirst() {
        terminalContent()
        compose.runOnIdle {
            val ime = terminal.onCreateInputConnection(EditorInfo())
            // Typeless deletes only when it sees text before the cursor, then deletes that many chars.
            val before = ime.getTextBeforeCursor(33, 0).toString()
            assertTrue(before.isNotEmpty())
            // WeChat IME stops a held delete once it sees no text before the cursor, even after committing:
            // the placeholder must outlast the hold that clears the line.
            ime.commitText("你好", 1)
            val held = ime.getExtractedText(ExtractedTextRequest(), 0)
            assertTrue(held.text.isBlank() && held.selectionStart >= 16)
            session.input.clear()
            assertTrue(ime.deleteSurroundingText(before.length.coerceAtMost(1), 0))
            assertEquals("\u007f", session.input.toString())
        }
    }

    @Test fun softKeyboardEnterIsReportedButHardwareEnterAndPlainTextAreNot() {
        terminalContent()
        val ime = connection()
        compose.runOnIdle { ime.commitText("ls", 1) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, softEnters) }
        compose.runOnIdle { ime.commitText("\n", 1) }
        compose.waitUntil(2_000) { softEnters == 1 }
        compose.runOnIdle {
            assertEquals("ls\r", session.input.toString())
            ime.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ime.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
        compose.waitUntil(2_000) { softEnters == 2 }
        compose.runOnIdle {
            assertEquals("ls\r\r", session.input.toString())
            val now = SystemClock.uptimeMillis()
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                terminal.dispatchKeyEvent(KeyEvent(now, now, action, KeyEvent.KEYCODE_ENTER, 0, 0, 3, 0, 0, InputDevice.SOURCE_KEYBOARD))
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("ls\r\r\r", session.input.toString())
            assertEquals("A hardware keyboard's Enter keeps the keyboard state", 2, softEnters)
        }
    }

    @Test fun streamingVoiceComposingKeepsCursorOffsetsContinuousWithoutTruncating() {
        terminalContent()
        val ime = connection()
        compose.runOnIdle {
            // Reproduces WeChat IME 4.0.1 VoiceComposingRegionWriter: it anchors [regionStart, regionEnd]
            // at the idle cursor offset and aborts further streaming writes if selection ever shifts backward.
            fun speakUtterance(chunks: List<String>) {
                val startSnap = checkNotNull(ime.getExtractedText(ExtractedTextRequest(), 0))
                val regionStart = startSnap.selectionStart
                var regionEnd = startSnap.selectionEnd
                assertTrue(regionStart >= 16 && regionStart == regionEnd)
                for (chunk in chunks) {
                    ime.beginBatchEdit()
                    val cur = checkNotNull(ime.getExtractedText(ExtractedTextRequest(), 0))
                    assertEquals("Cursor must not shift backward mid-utterance", regionEnd, cur.selectionStart)
                    assertEquals("Cursor must stay collapsed at composing end", regionEnd, cur.selectionEnd)
                    assertTrue(ime.setComposingRegion(regionStart, regionEnd))
                    assertTrue(ime.setSelection(regionEnd, regionEnd))
                    assertTrue(ime.setComposingText(SpannableString(chunk), 1))
                    regionEnd = regionStart + chunk.length
                    ime.endBatchEdit()
                }
                assertTrue(ime.finishComposingText())
            }

            speakUtterance(listOf("你", "你好", "你好呢", "你好，", "你好，能听到我说话吗"))
            assertEquals("你好，能听到我说话吗", session.input.toString())
            session.input.clear()

            // A follow-up utterance on the same connection starts cleanly at the same placeholder anchor.
            speakUtterance(listOf("第", "第二句语音测试", "第二句测试"))
            assertEquals("第二句测试", session.input.toString())
        }
    }

    @Test fun virtualPlaceholderOffsetsTranslateSelectionComposingRegionReplaceTextAndSnapshot() {
        terminalContent()
        val ime = connection()
        compose.runOnIdle {
            val baseOffset = checkNotNull(ime.getExtractedText(ExtractedTextRequest(), 0)).selectionStart
            assertTrue(ime.setComposingText("你好世界", 1))
            val extracted = checkNotNull(ime.getExtractedText(ExtractedTextRequest(), 0))
            assertEquals(baseOffset + 4, extracted.selectionStart)
            assertEquals(baseOffset + 4, extracted.selectionEnd)
            assertTrue(extracted.text.toString().endsWith("你好世界"))

            if (Build.VERSION.SDK_INT >= 31) {
                val surrounding = checkNotNull(ime.getSurroundingText(100, 100, 0))
                assertEquals(baseOffset + 4, surrounding.offset + surrounding.selectionStart)
                assertTrue(surrounding.text.toString().endsWith("你好世界"))
            }
            if (Build.VERSION.SDK_INT >= 33) {
                val snapshot = checkNotNull(ime.takeSnapshot())
                assertEquals(baseOffset, snapshot.compositionStart)
                assertEquals(baseOffset + 4, snapshot.compositionEnd)
            }

            assertTrue(ime.setSelection(baseOffset + 1, baseOffset + 3))
            assertEquals("好世", ime.getSelectedText(0)?.toString())
            val selectedSnap = checkNotNull(ime.getExtractedText(ExtractedTextRequest(), 0))
            assertEquals(baseOffset + 1, selectedSnap.selectionStart)
            assertEquals(baseOffset + 3, selectedSnap.selectionEnd)

            assertTrue(ime.setComposingRegion(baseOffset, baseOffset + 4))
            assertTrue(ime.setSelection(baseOffset + 4, baseOffset + 4))
            assertTrue(ime.setComposingText("替换", 1))
            assertEquals("替换", checkNotNull((ime as BaseInputConnection).editable).toString())

            if (Build.VERSION.SDK_INT >= 34) {
                assertTrue(ime.replaceText(baseOffset, baseOffset + 2, "最终文本", 1, null))
                assertEquals("最终文本", session.input.toString())
            } else {
                assertTrue(ime.finishComposingText())
                assertEquals("替换", session.input.toString())
            }
        }
    }
}

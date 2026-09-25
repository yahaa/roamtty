package io.itermux.app

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.itermux.app.ui.insertDraftText
import org.junit.Assert.assertEquals
import org.junit.Test

class DraftPasteTest {
    @Test fun insertsAtCaretWithoutReplacingTheDraft() {
        val draft = TextFieldValue("start end", TextRange(6))
        val result = insertDraftText(draft, "new ", 128 * 1024)
        assertEquals("start new end", result.text)
        assertEquals(TextRange(10), result.selection)
    }

    @Test fun replacesOnlySelectedText() {
        val draft = TextFieldValue("hello world", TextRange(6, 11))
        val result = insertDraftText(draft, "friend", 12)
        assertEquals("hello friend", result.text)
        assertEquals(TextRange(12), result.selection)
    }

    @Test fun emptyClipboardOrTruncatedEmojiLeavesDraftIntact() {
        val draft = TextFieldValue("abc", TextRange(3))
        assertEquals(draft, insertDraftText(draft, "", 4))
        assertEquals(draft, insertDraftText(draft, "🙂!", 4))
        assertEquals("abc🙂", insertDraftText(draft, "🙂!", 5).text)
    }
}

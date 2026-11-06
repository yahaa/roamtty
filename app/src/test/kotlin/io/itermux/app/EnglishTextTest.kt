package io.itermux.app

import io.itermux.app.session.sessionCountKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class EnglishTextTest {
    private fun english(key: String, count: Int) = EnglishText.values.getValue(key).format(count)

    @Test fun oneSessionIsSingularInTheNotificationTitle() {
        assertEquals("1 session", english(sessionCountKey(1), 1))
        assertEquals("2 sessions", english(sessionCountKey(2), 2))
        assertEquals("0 sessions", english(sessionCountKey(0), 0))
    }

    @Test fun chineseTitleKeepsTheCount() {
        assertEquals("1 个会话", sessionCountKey(1).format(1))
        assertEquals("3 个会话", sessionCountKey(3).format(3))
    }

    @Test fun networkStatsDescriptionIsTranslated() {
        assertNotNull(EnglishText.values["画面上显示延迟、帧率和码率，长按可拖动"])
    }
}

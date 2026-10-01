package io.itermux.app.session

import io.itermux.ssh.Tmux
import org.junit.Assert.assertEquals
import org.junit.Test

/** Documents the journal remote-session field rules without Android SharedPreferences. */
class SessionJournalCodecTest {
    private fun remoteSessionName(jsonObjectBody: String): String {
        val match = Regex("\"remoteSessionName\"\\s*:\\s*\"([^\"]*)\"").find(jsonObjectBody)
        val raw = match?.groupValues?.get(1).orEmpty()
        return Tmux.sanitizeSessionName(raw.ifBlank { Tmux.LEGACY_SESSION })
    }

    @Test fun legacyEntriesWithoutFieldMapToFixedName() {
        assertEquals(Tmux.LEGACY_SESSION, remoteSessionName("""{"id":"a","hostId":"h","mode":"TMUX","title":"old"}"""))
    }

    @Test fun blankRemoteSessionNameMapsToLegacy() {
        assertEquals(Tmux.LEGACY_SESSION, remoteSessionName("""{"id":"a","remoteSessionName":""}"""))
    }

    @Test fun modernEntriesKeepSanitizedNames() {
        assertEquals("itermux-aabbccdd1122", remoteSessionName("""{"remoteSessionName":"itermux-aabbccdd1122"}"""))
        assertEquals("itermux-xrm-rf", remoteSessionName("""{"remoteSessionName":"itermux-x;rm -rf"}"""))
    }
}

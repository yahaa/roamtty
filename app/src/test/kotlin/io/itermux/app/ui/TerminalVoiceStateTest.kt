package io.itermux.app.ui

import io.itermux.desktop.VoiceCallPhase
import io.itermux.desktop.VoiceMode
import io.itermux.desktop.VoiceUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalVoiceStateTest {
    @Test fun petVoiceStateMapsEveryCallPhaseCleanly() {
        assertEquals(PetVoiceState.Idle, PetVoiceState.from(VoiceUiState()))
        assertFalse(PetVoiceState.Idle.inCall)
        assertEquals("idle", PetVoiceState.Idle.tagName)

        assertEquals(PetVoiceState.Idle, PetVoiceState.from(VoiceUiState(phase = VoiceCallPhase.Failed("err"))))

        val connecting = PetVoiceState.from(VoiceUiState(phase = VoiceCallPhase.Connecting()))
        assertEquals(PetVoiceState.Connecting, connecting)
        assertTrue(connecting.inCall)
        assertEquals("connecting", connecting.tagName)

        val listening = PetVoiceState.from(VoiceUiState(phase = VoiceCallPhase.Listening, mode = VoiceMode.LIVE, micLevel = 0.42f))
        assertEquals(PetVoiceState.Listening(0.42f, dictating = false), listening)
        assertEquals("listening", listening.tagName)

        val dictating = PetVoiceState.from(VoiceUiState(phase = VoiceCallPhase.Listening, mode = VoiceMode.DICTATE, micLevel = 0.5f))
        assertEquals(PetVoiceState.Listening(0.5f, dictating = true), dictating)
        assertEquals("dictating", dictating.tagName)

        val speaking = PetVoiceState.from(VoiceUiState(phase = VoiceCallPhase.Speaking, speakerLevel = 0.77f))
        assertEquals(PetVoiceState.Speaking(0.77f), speaking)
        assertEquals("speaking", speaking.tagName)

        val muted = PetVoiceState.from(VoiceUiState(phase = VoiceCallPhase.Muted))
        assertEquals(PetVoiceState.Muted, muted)
        assertEquals("muted", muted.tagName)
    }
}

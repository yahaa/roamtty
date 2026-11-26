package io.itermux.app.ui

import io.itermux.app.session.AgentStatus
import io.itermux.app.session.CodingAgent
import io.itermux.app.session.ScreenApp
import io.itermux.app.session.isAgyActive
import io.itermux.desktop.AudioRoute
import io.itermux.desktop.VoiceCallPhase
import io.itermux.desktop.VoiceEvent
import io.itermux.desktop.VoiceMode
import io.itermux.desktop.VoiceStateMachine
import io.itermux.desktop.VoiceUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalVoiceStateTest {
    @Test fun petVoiceStateMapsEveryCallPhaseCleanly() {
        assertEquals(PetVoiceState.Idle, PetVoiceState.from(VoiceUiState()))
        assertFalse(PetVoiceState.Idle.inCall)
        assertEquals("idle", PetVoiceState.Idle.tagName)

        assertEquals(PetVoiceState.Idle, PetVoiceState.from(VoiceUiState(phase = VoiceCallPhase.Failed("err"))))

        val connecting = PetVoiceState.from(VoiceUiState(phase = VoiceCallPhase.Connecting()))
        assertEquals(PetVoiceState.Connecting(networkUnstable = false), connecting)
        assertTrue(connecting.inCall)
        assertFalse(connecting.isNetworkUnstable)
        assertEquals("connecting", connecting.tagName)

        val listening = PetVoiceState.from(VoiceUiState(phase = VoiceCallPhase.Listening, mode = VoiceMode.LIVE, micLevel = 0.42f))
        assertEquals(PetVoiceState.Listening(0.42f, networkUnstable = false), listening)
        assertFalse(listening.isNetworkUnstable)
        assertEquals("listening", listening.tagName)

        val speaking = PetVoiceState.from(VoiceUiState(phase = VoiceCallPhase.Speaking, speakerLevel = 0.77f))
        assertEquals(PetVoiceState.Speaking(0.77f, networkUnstable = false), speaking)
        assertFalse(speaking.isNetworkUnstable)
        assertEquals("speaking", speaking.tagName)

        val muted = PetVoiceState.from(VoiceUiState(phase = VoiceCallPhase.Muted))
        assertEquals(PetVoiceState.Muted(networkUnstable = false), muted)
        assertFalse(muted.isNetworkUnstable)
        assertEquals("muted", muted.tagName)

        // Network instability flags the pet across active states so only poor/reconnecting network shifts color.
        val unstableListening = PetVoiceState.from(VoiceUiState(phase = VoiceCallPhase.Listening, micLevel = 0.3f, networkPoor = true))
        assertTrue(unstableListening.isNetworkUnstable)
        assertEquals("listening-unstable", unstableListening.tagName)

        val unstableSpeaking = PetVoiceState.from(VoiceUiState(phase = VoiceCallPhase.Speaking, speakerLevel = 0.5f, networkPoor = true))
        assertTrue(unstableSpeaking.isNetworkUnstable)
        assertEquals("speaking-unstable", unstableSpeaking.tagName)

        val unstableMuted = PetVoiceState.from(VoiceUiState(phase = VoiceCallPhase.Muted, networkPoor = true))
        assertTrue(unstableMuted.isNetworkUnstable)
        assertEquals("muted-unstable", unstableMuted.tagName)
    }

    @Test fun resumingMapsPetToUnstableConnectingAndPausesTimer() {
        val resumingListening = VoiceUiState(phase = VoiceCallPhase.Listening, mode = VoiceMode.LIVE, resuming = true)
        assertEquals(PetVoiceState.Connecting(networkUnstable = true), PetVoiceState.from(resumingListening))
        assertEquals("reconnecting", PetVoiceState.from(resumingListening).tagName)
        val resumingMuted = VoiceUiState(phase = VoiceCallPhase.Muted, mode = VoiceMode.LIVE, resuming = true)
        assertEquals(PetVoiceState.Connecting(networkUnstable = true), PetVoiceState.from(resumingMuted))

        var state = VoiceStateMachine.reduce(VoiceUiState(), VoiceEvent.Start(VoiceMode.LIVE, AudioRoute.SPEAKER))
        state = VoiceStateMachine.reduce(state, VoiceEvent.Ready(24713, "UDP"))
        state = VoiceStateMachine.reduce(state, VoiceEvent.MicStarted(3L))
        state = VoiceStateMachine.reduce(state, VoiceEvent.SpkStarted(4L))
        state = VoiceStateMachine.reduce(state, VoiceEvent.TickSecond)
        assertEquals(1, state.elapsedSeconds)

        state = VoiceStateMachine.reduce(state, VoiceEvent.Resuming(true))
        assertTrue(state.resuming)
        assertTrue(state.networkUnstable)
        assertNull(state.activeMicStreamId)
        assertNull(state.activeSpkStreamId)
        state = VoiceStateMachine.reduce(state, VoiceEvent.TickSecond)
        assertEquals(1, state.elapsedSeconds)
    }

    @Test fun toggleMuteBlockedWhileConnecting() {
        val connecting = VoiceStateMachine.reduce(VoiceUiState(), VoiceEvent.Start(VoiceMode.LIVE, AudioRoute.SPEAKER))
        assertEquals(connecting, VoiceStateMachine.reduce(connecting, VoiceEvent.ToggleMute))
    }

    @Test fun isAgyActiveHidesVoiceButtonAfterAgyExitsToShell() {
        // Before first status poll answers, a session launched for agy counts as active.
        assertTrue(isAgyActive(agentStatus = null, launchedAgent = CodingAgent.AGY, agentPolled = false, screenApp = ScreenApp.SHELL))
        // Once polled and agentStatus is null (exited back to shell) with no agy TUI on screen, voice button hides.
        assertFalse(isAgyActive(agentStatus = null, launchedAgent = CodingAgent.AGY, agentPolled = true, screenApp = ScreenApp.SHELL))
        // Active hook status for agy keeps it shown.
        val agyStatus = AgentStatus(AgentStatus.State.IDLE, "agy", "", "", "", 0L)
        assertTrue(isAgyActive(agentStatus = agyStatus, launchedAgent = null, agentPolled = true, screenApp = ScreenApp.SHELL))
        // Manually launched agy detected on screen keeps it shown.
        assertTrue(isAgyActive(agentStatus = null, launchedAgent = null, agentPolled = true, screenApp = ScreenApp.AGY))
        // Another agent running in the session does not show agy voice button.
        val claudeStatus = AgentStatus(AgentStatus.State.IDLE, "claude", "", "", "", 0L)
        assertFalse(isAgyActive(agentStatus = claudeStatus, launchedAgent = CodingAgent.AGY, agentPolled = true, screenApp = ScreenApp.CLAUDE))
    }
}

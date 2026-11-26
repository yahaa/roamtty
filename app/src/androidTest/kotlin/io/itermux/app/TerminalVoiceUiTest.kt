package io.itermux.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.itermux.app.ui.*
import io.itermux.desktop.AudioRoute
import io.itermux.desktop.VoiceCallPhase
import io.itermux.desktop.VoiceMode
import io.itermux.desktop.VoiceStateMachine
import io.itermux.desktop.VoiceUiState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalVoiceUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun dockPhoneButtonReflectsIdleActiveAndMutedStates() {
        var voiceUi by mutableStateOf(VoiceUiState())
        var clicks = 0
        compose.setContent {
            MaterialTheme(colorScheme = DarkColors, shapes = AppShapes, typography = AppTypography) {
                DockVoiceButton(voiceUi = voiceUi, enabled = true, onClick = { clicks++ })
            }
        }
        compose.onNodeWithTag("terminal-voice").assertIsDisplayed().performClick()
        assertEquals(1, clicks)
        compose.onNodeWithTag("terminal-voice-badge-active").assertDoesNotExist()

        voiceUi = voiceUi.copy(phase = VoiceCallPhase.Listening)
        compose.waitForIdle()
        compose.onNodeWithTag("terminal-voice-badge-active").assertIsDisplayed()

        voiceUi = voiceUi.copy(phase = VoiceCallPhase.Muted)
        compose.waitForIdle()
        compose.onNodeWithTag("terminal-voice-badge-muted").assertIsDisplayed()
    }

    @Test fun confirmationSheetSwitchesModesRoutesAndNotices() {
        var mode by mutableStateOf(VoiceMode.LIVE)
        var route by mutableStateOf(AudioRoute.SPEAKER)
        var hasMic by mutableStateOf(false)
        var pipMuted by mutableStateOf(true)
        var pluginInstalled by mutableStateOf(false)
        var confirmed = 0
        var cancelled = 0

        compose.setContent {
            MaterialTheme(colorScheme = DarkColors, shapes = AppShapes, typography = AppTypography) {
                VoiceStartSheetContent(
                    hostName = "mac-studio",
                    mode = mode,
                    route = route,
                    hasMicPermission = hasMic,
                    desktopPipAudioActive = pipMuted,
                    pluginInstalled = pluginInstalled,
                    onModeChange = { mode = it },
                    onRouteChange = { route = it },
                    onCancel = { cancelled++ },
                    onConfirm = { confirmed++ },
                )
            }
        }

        // Enlarged phone-holding pet + live mode + route pills + all 3 contextual notices
        compose.onNodeWithTag("voice-sheet-pet").assertIsDisplayed()
        compose.onNodeWithTag("voice-route-row").assertIsDisplayed()
        compose.onNodeWithTag("voice-notice-permission").assertIsDisplayed()
        compose.onNodeWithTag("voice-notice-desktop-mute").assertIsDisplayed()
        compose.onNodeWithTag("voice-notice-install").assertIsDisplayed()
        compose.onNodeWithText(tr("安装插件并通话")).assertIsDisplayed()

        // Switch audio route pill
        compose.onNodeWithTag("voice-route-earpiece").performClick()
        assertEquals(AudioRoute.EARPIECE, route)

        // Switch to dictate mode -> route row hides, button reflects dictation
        compose.onNodeWithTag("voice-mode-dictate").performClick()
        compose.waitForIdle()
        assertEquals(VoiceMode.DICTATE, mode)
        compose.onNodeWithTag("voice-route-row").assertDoesNotExist()
        compose.onNodeWithText(tr("安装插件并听写")).assertIsDisplayed()

        // When permission granted, PiP inactive, and plugin installed -> notices vanish
        hasMic = true; pipMuted = false; pluginInstalled = true; mode = VoiceMode.LIVE
        compose.waitForIdle()
        compose.onNodeWithTag("voice-notice-permission").assertDoesNotExist()
        compose.onNodeWithTag("voice-notice-desktop-mute").assertDoesNotExist()
        compose.onNodeWithTag("voice-notice-install").assertDoesNotExist()
        compose.onNodeWithText(tr("开始通话")).assertIsDisplayed()

        compose.onNodeWithTag("voice-sheet-confirm").performClick()
        compose.onNodeWithTag("voice-sheet-cancel").performClick()
        assertEquals(1, confirmed)
        assertEquals(1, cancelled)
    }

    @Test fun petVoiceStatesTransitionAndKeepTapAndLongPressSeparate() {
        var voiceState by mutableStateOf<PetVoiceState>(PetVoiceState.Idle)
        var taps = 0
        var holds = 0

        compose.setContent {
            MaterialTheme(colorScheme = DarkColors, shapes = AppShapes, typography = AppTypography) {
                Box(Modifier.size(120.dp)) {
                    ToolboxPet(
                        description = "pet",
                        voiceState = voiceState,
                        onHoldStart = { holds++ },
                        onClick = { taps++ },
                    )
                }
            }
        }

        val states = listOf(
            PetVoiceState.Idle to "pet-voice-idle",
            PetVoiceState.Connecting to "pet-voice-connecting",
            PetVoiceState.Listening(0.6f) to "pet-voice-listening",
            PetVoiceState.Speaking(0.8f) to "pet-voice-speaking",
            PetVoiceState.Muted to "pet-voice-muted",
        )
        for ((state, tag) in states) {
            voiceState = state
            compose.waitForIdle()
            compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
        }

        // Tap still triggers onClick in call mode
        compose.onNodeWithTag("restore-dock").performClick()
        assertEquals(1, taps)
        // Long press still triggers onHoldStart (PetShortcutArc) in call mode
        compose.onNodeWithTag("restore-dock").performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals(1, holds)
    }

    @Test fun callControlPopoverDrivesMuteRouteDockAndHangup() {
        var ui by mutableStateOf(
            VoiceUiState(
                phase = VoiceCallPhase.Listening,
                mode = VoiceMode.LIVE,
                audioRoute = AudioRoute.SPEAKER,
                bluetoothAvailable = true,
                elapsedSeconds = 84,
            )
        )
        var dockOpened = 0
        var hungUp = 0

        compose.setContent {
            MaterialTheme(colorScheme = DarkColors, shapes = AppShapes, typography = AppTypography) {
                VoiceCallPopoverCard(
                    ui = ui,
                    onToggleMute = { ui = VoiceStateMachine.reduce(ui, io.itermux.desktop.VoiceEvent.ToggleMute) },
                    onCycleRoute = { ui = VoiceStateMachine.reduce(ui, io.itermux.desktop.VoiceEvent.CycleRoute) },
                    onOpenDock = { dockOpened++ },
                    onHangup = { hungUp++ },
                )
            }
        }

        compose.onNodeWithTag("voice-call-timer").assertTextEquals("01:24")
        compose.onNodeWithTag("voice-popover-state").assertTextEquals(tr("聆听中"))

        compose.onNodeWithTag("voice-popover-mute").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("voice-popover-state").assertTextEquals(tr("麦克风已静音"))
        compose.onNodeWithText(tr("取消静音")).assertIsDisplayed()

        compose.onNodeWithTag("voice-popover-route").performClick()
        compose.waitForIdle()
        assertEquals(AudioRoute.EARPIECE, ui.audioRoute)
        compose.onNodeWithText(tr("听筒")).assertIsDisplayed()

        compose.onNodeWithTag("voice-popover-route").performClick()
        compose.waitForIdle()
        assertEquals(AudioRoute.BLUETOOTH, ui.audioRoute)
        compose.onNodeWithText(tr("蓝牙")).assertIsDisplayed()

        compose.onNodeWithTag("voice-popover-dock").performClick()
        compose.onNodeWithTag("voice-popover-hangup").performClick()
        assertEquals(1, dockOpened)
        assertEquals(1, hungUp)
    }
}

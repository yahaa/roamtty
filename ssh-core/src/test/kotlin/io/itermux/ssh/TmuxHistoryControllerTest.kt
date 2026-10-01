package io.itermux.ssh

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxHistoryControllerTest {
    @Test fun fullScreenCodexUsesItsTranscriptInsteadOfEmptyCopyMode() = runBlocking {
        val commands = mutableListOf<String>()
        val history = TmuxHistoryController("itermux-test") { command ->
            commands += command
            when {
                command.startsWith("ps ") -> "42 42 node\n42 42 codex\n"
                command.contains("display-message") -> "0::0:40:1"
                else -> ""
            }
        }
        assertEquals(HistorySource.CODEX, history.enter()?.source)
        history.enter() // Do not toggle the transcript closed on a repeated request.
        history.moveTo(200)
        assertTrue(requireNotNull(history.history()).active)
        assertEquals(1, commands.count { it.endsWith(" C-t") })
        assertFalse(commands.any { it.contains("copy-mode") || it.contains(" -X ") })
        assertFalse(requireNotNull(history.exit()).active)
        assertTrue(commands.any { it.endsWith(" Escape") })
    }

    @Test fun backgroundCodexDoesNotStealOtherProgramsHistoryKeys() = runBlocking {
        val commands = mutableListOf<String>()
        val history = TmuxHistoryController("itermux-test") { command ->
            commands += command
            when {
                command.startsWith("ps ") -> "42 11 codex\n42 42 grok\n"
                command.contains("display-message") -> "0::1:40:1"
                else -> ""
            }
        }
        assertEquals(HistorySource.APPLICATION, history.enter()?.source)
        assertTrue(commands.indexOfFirst { it.endsWith(" -X cancel") } < commands.indexOfFirst { it.endsWith(" PPage") })
        assertFalse(commands.any { it.endsWith(" C-t") || it.contains("copy-mode") })
    }

    @Test fun historyCanMoveAcrossLargeRangesAndReturnToLivePane() = runBlocking {
        val remote = FakeTmux()
        val history = TmuxHistoryController(Tmux.LEGACY_SESSION, remote::execute)

        assertEquals(TmuxHistory(120, 0, false, 24), history.history())
        assertTrue(requireNotNull(history.moveTo(37)).active)
        assertEquals(37, history.history()?.position)
        assertEquals(120, history.moveTo(120)?.position)
        assertEquals(0, history.moveTo(0)?.position)
        assertFalse(requireNotNull(history.exit()).active)
        assertTrue(remote.commands.count { it.contains("send-keys") } < 10)
    }

    @Test fun malformedRemoteStateDoesNotIssueHistoryCommands() = runBlocking {
        val commands = mutableListOf<String>()
        val history = TmuxHistoryController(Tmux.LEGACY_SESSION) { command -> commands += command; "not tmux state" }

        assertNull(history.enter())
        assertNull(history.moveTo(20))
        assertNull(history.exit())
        assertTrue(commands.all { it.contains("display-message") })
    }


    @Test fun customSessionNameIsTargeted() = runBlocking {
        val commands = mutableListOf<String>()
        val history = TmuxHistoryController("itermux-custom1") { command ->
            commands += command
            if (command.contains("display-message")) "10:0:0:24:0" else ""
        }
        assertEquals(TmuxHistory(10, 0, false, 24), history.history())
        history.enter()
        assertTrue(commands.any { it.contains("-t 'itermux-custom1'") })
        assertFalse(commands.any { it.contains("-t 'itermux'") && "custom1" !in it })
    }

    private class FakeTmux {
        var position = 0
        var active = false
        val commands = mutableListOf<String>()

        suspend fun execute(command: String): String? {
            commands += command
            if (command.contains("display-message")) return "120:${if (active) position else ""}:${if (active) 1 else 0}:24:0"
            if (command.contains("copy-mode")) {
                active = true
                return ""
            }
            val count = Regex("-N (\\d+)").find(command)?.groupValues?.get(1)?.toInt() ?: 1
            when (command.substringAfter(" -X ")) {
                "history-top" -> position = 120
                "history-bottom" -> position = 0
                "page-up" -> position = (position + 22 * count).coerceAtMost(120)
                "page-down" -> position = (position - 22 * count).coerceAtLeast(0)
                "scroll-up" -> position = (position + count).coerceAtMost(120)
                "scroll-down" -> position = (position - count).coerceAtLeast(0)
                "cancel" -> { active = false; position = 0 }
                else -> error("Unexpected tmux command: $command")
            }
            return ""
        }
    }
}

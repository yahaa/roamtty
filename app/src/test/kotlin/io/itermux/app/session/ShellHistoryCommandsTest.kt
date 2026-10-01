package io.itermux.app.session

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellHistoryCommandsTest {
    @Test fun bashSourceWinsWithoutFetchingZsh() = runBlocking {
        val paths = mutableListOf<String>()
        val result = loadCommonCommands { path ->
            paths += path
            "pwd\nls\npwd" to false
        }
        assertEquals(listOf(".bash_history"), paths)
        assertEquals(CommonCommandsState.Ready(listOf("pwd", "ls"), "~/.bash_history"), result)
    }

    @Test fun emptyBashFallsBackToZshAndTruncatedFirstLineIsSkipped() = runBlocking {
        val result = loadCommonCommands { path ->
            if (path == ".bash_history") "#1234\n\n" to false
            else "partial\n: 1234:0;pwd\n: 1235:0;ls" to true
        }
        assertEquals(CommonCommandsState.Ready(listOf("ls", "pwd"), "~/.zsh_history"), result)
        assertTrue(loadCommonCommands { null } is CommonCommandsState.Unavailable)
    }

    @Test(expected = kotlinx.coroutines.CancellationException::class)
    fun cancellingHistoryReadDoesNotFetchAnotherSource() = runBlocking {
        loadCommonCommands { throw kotlinx.coroutines.CancellationException("disconnected") }
        Unit
    }

    @Test fun bashRanksByLastUseRegardlessOfFrequency() {
        val raw = """
            ls
            git status
            ls
            pwd
            git status
            git status
            echo hi

        """.trimIndent()
        val ranked = ShellHistoryCommands.rankByRecency(
            ShellHistoryCommands.extractCommands(raw, zshStyle = false),
            limit = 10
        )
        assertEquals(listOf("echo hi", "git status", "pwd", "ls"), ranked)
    }

    @Test fun zshExtendedHistoryExtractsCommands() {
        val raw = """
            : 1700000000:0;cd /tmp
            : 1700000001:0;ls -la
            plain leftover
            : 1700000002:0;cd /tmp
        """.trimIndent()
        val commands = ShellHistoryCommands.extractCommands(raw, zshStyle = true)
        assertEquals(listOf("cd /tmp", "ls -la", "plain leftover", "cd /tmp"), commands)
        // The last occurrence of each command determines its position.
        assertEquals(listOf("cd /tmp", "plain leftover", "ls -la"),
            ShellHistoryCommands.rankByRecency(commands))
    }

    @Test fun skipsOverlongLinesAndRespectsLimit() {
        val long = "x".repeat(ShellHistoryCommands.MAX_COMMAND_LENGTH + 1)
        val commands = ShellHistoryCommands.extractCommands("ok\n$long\nok\n", zshStyle = false)
        assertEquals(listOf("ok", "ok"), commands)
        // Duplicates are removed before applying the limit.
        assertEquals(listOf("a", "c"), ShellHistoryCommands.rankByRecency(
            listOf("a", "b", "c", "a"), limit = 2
        ))
    }

    @Test fun fuzzyFilterContainsAndSubsequence() {
        val commands = listOf("git status", "git commit -m x", "ls -la", "docker ps")
        assertEquals(listOf("git status", "git commit -m x"),
            ShellHistoryCommands.fuzzyFilter(commands, "git"))
        assertEquals(listOf("docker ps"), ShellHistoryCommands.fuzzyFilter(commands, "dps"))
        assertEquals(commands, ShellHistoryCommands.fuzzyFilter(commands, "  "))
        assertTrue(ShellHistoryCommands.fuzzyFilter(commands, "zzz").isEmpty())
    }

    @Test fun skipFirstLineAfterTruncation() {
        val commands = ShellHistoryCommands.extractCommands(
            "partial-command\nfull one\nfull two",
            zshStyle = false,
            skipFirstLine = true
        )
        assertEquals(listOf("full one", "full two"), commands)
    }
    @Test fun bashTimestampsAreNotExecutableShortcuts() {
        assertEquals(listOf("pwd", "ls"), ShellHistoryCommands.extractCommands("#1700000000\npwd\n#1700000001\nls", false))
    }

    @Test fun recentSelectionStaysFirstAcrossHistoryRefreshWithoutDuplicates() {
        assertEquals(listOf("ls", "pwd", "git status"),
            ShellHistoryCommands.mergeRecent(listOf("ls", "pwd"), listOf("git status", "ls", "pwd")))
        assertEquals(emptyList<String>(), ShellHistoryCommands.rankByRecency(listOf("ls"), 0))
    }

    @Test fun executionAppendsEnterOutsideBracketedPaste() {
        assertEquals("git status\r", ShellHistoryCommands.executionInput("git status", false))
        assertEquals("\u001b[200~git status\u001b[201~\r", ShellHistoryCommands.executionInput("git status", true))
    }

    @Test(expected = IllegalArgumentException::class)
    fun executionRejectsEmbeddedTerminalControlSequences() {
        ShellHistoryCommands.executionInput("ls\u001b[201~", true)
    }

}

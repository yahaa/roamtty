package io.itermux.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellHistoryCommandsTest {
    @Test fun bashSkipsBlankAndRanksByFrequencyThenRecency() {
        val raw = """
            ls
            git status
            ls
            pwd
            git status
            git status
            echo hi

        """.trimIndent()
        val ranked = ShellHistoryCommands.rankByFrequencyThenRecency(
            ShellHistoryCommands.extractCommands(raw, zshStyle = false),
            limit = 10
        )
        assertEquals(listOf("git status", "ls", "echo hi", "pwd"), ranked)
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
        // frequency first; recency tie-break (plain leftover later than ls -la)
        assertEquals(listOf("cd /tmp", "plain leftover", "ls -la"),
            ShellHistoryCommands.rankByFrequencyThenRecency(commands))
    }

    @Test fun skipsOverlongLinesAndRespectsLimit() {
        val long = "x".repeat(ShellHistoryCommands.MAX_COMMAND_LENGTH + 1)
        val commands = ShellHistoryCommands.extractCommands("ok\n$long\nok\n", zshStyle = false)
        assertEquals(listOf("ok", "ok"), commands)
        // a freq=2; among freq=1, c is more recent than b
        assertEquals(listOf("a", "c"), ShellHistoryCommands.rankByFrequencyThenRecency(
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
}

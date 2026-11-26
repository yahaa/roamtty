package io.itermux.app.voice

import io.itermux.app.session.AgentHooks
import io.itermux.ssh.PowerShell
import io.itermux.ssh.Psmux
import io.itermux.ssh.RemoteHost
import io.itermux.ssh.Tmux

/**
 * Injects `ANTIGRAVITY_MIC=127.0.0.1:<mic_port>` into the remote host's `agy` launchers and active
 * tmux/psmux sessions so both Roamtty-launched and manually started `agy` processes connect to the
 * host plugin's local `JETSKI-AUDIO/1` bridge.
 */
object VoiceEnvironment {
    const val ENV_NAME = "ANTIGRAVITY_MIC"

    fun endpoint(micPort: Int): String {
        require(micPort in 1..65535) { "invalid mic_port: $micPort" }
        return "127.0.0.1:$micPort"
    }

    fun validateEndpoint(endpoint: String): String {
        require(AgentHooks.isValidLoopbackMicEndpoint(endpoint)) { "invalid $ENV_NAME endpoint: $endpoint" }
        return endpoint
    }

    /** Contents of `~/.roamtty/agy` on POSIX hosts. */
    fun posixLauncher(micEndpoint: String?): String = AgentHooks.agyLauncher(micEndpoint)

    /** Contents of `%USERPROFILE%\.roamtty\agy.ps1` on Windows hosts. */
    fun windowsLauncherScript(micEndpoint: String?): String {
        val envLines = if (micEndpoint != null) {
            val validated = validateEndpoint(micEndpoint)
            listOf("\$env:$ENV_NAME = '$validated'")
        } else {
            listOf(
                "if (-not \$env:$ENV_NAME) {",
                "  if (Test-Path \"\$env:USERPROFILE\\.roamtty\\voice-mic\") {",
                "    \$env:$ENV_NAME = (Get-Content \"\$env:USERPROFILE\\.roamtty\\voice-mic\" -Raw).Trim()",
                "  }",
                "  if (-not \$env:$ENV_NAME) {",
                "    \$env:$ENV_NAME = '${AgentHooks.DEFAULT_ANTIGRAVITY_MIC}'",
                "  }",
                "}",
            )
        }
        return (listOf(
            "# Roamtty: starts Antigravity CLI with local voice bridge environment.",
        ) + envLines + listOf(
            "if (Get-Command agy.cmd -CommandType Application -ErrorAction SilentlyContinue) {",
            "  & agy.cmd @args",
            "} else {",
            "  & agy @args",
            "}",
            "exit \$LASTEXITCODE",
        )).joinToString("\n", postfix = "\n")
    }

    /**
     * POSIX shell script that updates `~/.roamtty/voice-mic` and keeps `~/.roamtty/agy` on the
     * dynamic default template (so ephemeral fallback ports never stick permanently after hangup).
     */
    fun posixUpdateLauncherScript(micEndpoint: String?): String {
        val micFileCmd = if (micEndpoint != null) {
            val validated = validateEndpoint(micEndpoint)
            "printf '%s\\n' '$validated' > \"\$HOME/.roamtty/voice-mic\"\n"
        } else {
            "rm -f \"\$HOME/.roamtty/voice-mic\"\n"
        }
        val launcher = posixLauncher(null)
        return "mkdir -p \"\$HOME/.roamtty\"\n" +
            micFileCmd +
            "cat > \"\$HOME/.roamtty/agy\" <<'__ROAMTTY_AGY_EOF__'\n" +
            launcher +
            "__ROAMTTY_AGY_EOF__\n" +
            "chmod 755 \"\$HOME/.roamtty/agy\"\n"
    }

    /** PowerShell command that updates `%USERPROFILE%\.roamtty\voice-mic` and `agy.ps1`. */
    fun windowsUpdateLauncherCommand(micEndpoint: String?): String {
        val micFileLine = if (micEndpoint != null) {
            val validated = validateEndpoint(micEndpoint)
            "[IO.File]::WriteAllText(\"\${dir}\\voice-mic\", '$validated')"
        } else {
            "Remove-Item \"\${dir}\\voice-mic\" -ErrorAction SilentlyContinue"
        }
        val encoded = java.util.Base64.getEncoder().encodeToString(windowsLauncherScript(null).toByteArray(Charsets.UTF_8))
        return PowerShell.command("""
            ${'$'}dir = "${'$'}env:USERPROFILE\.roamtty"
            New-Item -ItemType Directory -Force ${'$'}dir | Out-Null
            $micFileLine
            [IO.File]::WriteAllText("${'$'}dir\agy.ps1", [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('$encoded')))
            exit 0
        """.trimIndent())
    }

    /** `tmux set-environment -t <session> ANTIGRAVITY_MIC 127.0.0.1:<port>`. */
    fun tmuxSetEnvCommand(sessionName: String, micEndpoint: String): String =
        Tmux.setEnvironmentCommand(sessionName, ENV_NAME, validateEndpoint(micEndpoint))

    /** `tmux set-environment -u -t <session> ANTIGRAVITY_MIC`. */
    fun tmuxUnsetEnvCommand(sessionName: String): String =
        Tmux.unsetEnvironmentCommand(sessionName, ENV_NAME)

    /** `psmux set-environment -t <session> ANTIGRAVITY_MIC 127.0.0.1:<port>`. */
    fun psmuxSetEnvCommand(sessionName: String, micEndpoint: String): String =
        Psmux.setEnvironmentCommand(sessionName, ENV_NAME, validateEndpoint(micEndpoint))

    /** `psmux set-environment -u -t <session> ANTIGRAVITY_MIC`. */
    fun psmuxUnsetEnvCommand(sessionName: String): String =
        Psmux.unsetEnvironmentCommand(sessionName, ENV_NAME)

    /** Injects `ANTIGRAVITY_MIC=127.0.0.1:<micPort>` into launchers and every attached tmux/psmux session. */
    suspend fun inject(remote: RemoteHost, windowsHost: Boolean, micPort: Int, tmuxSessions: List<String>) {
        val ep = endpoint(micPort)
        if (windowsHost) {
            runCatching { remote.runCommand(windowsUpdateLauncherCommand(ep), 10_000) {} }
            tmuxSessions.distinct().forEach { name ->
                runCatching { remote.runCommand(psmuxSetEnvCommand(name, ep), 5_000) {} }
            }
        } else {
            runCatching { remote.run(posixUpdateLauncherScript(ep), 10_000) {} }
            tmuxSessions.distinct().forEach { name ->
                runCatching { remote.runCommand(tmuxSetEnvCommand(name, ep), 5_000) {} }
            }
        }
    }

    /** Removes `ANTIGRAVITY_MIC` from launchers and tmux/psmux sessions when voice ends cleanly. */
    suspend fun clear(remote: RemoteHost, windowsHost: Boolean, tmuxSessions: List<String>) {
        if (windowsHost) {
            runCatching { remote.runCommand(windowsUpdateLauncherCommand(null), 10_000) {} }
            tmuxSessions.distinct().forEach { name ->
                runCatching { remote.runCommand(psmuxUnsetEnvCommand(name), 5_000) {} }
            }
        } else {
            runCatching { remote.run(posixUpdateLauncherScript(null), 10_000) {} }
            tmuxSessions.distinct().forEach { name ->
                runCatching { remote.runCommand(tmuxUnsetEnvCommand(name), 5_000) {} }
            }
        }
    }
}

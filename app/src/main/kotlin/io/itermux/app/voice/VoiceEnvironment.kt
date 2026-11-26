package io.itermux.app.voice

import io.itermux.app.session.AgentHooks
import io.itermux.ssh.PowerShell
import io.itermux.ssh.Psmux
import io.itermux.ssh.RemoteHost
import io.itermux.ssh.Tmux
import kotlinx.coroutines.CancellationException

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

    /** Combined POSIX script updating `~/.roamtty/voice-mic`, `~/.roamtty/agy`, and all tmux sessions in one exec. */
    fun posixEnvScript(micEndpoint: String?, tmuxSessions: List<String>): String = buildString {
        append(posixUpdateLauncherScript(micEndpoint))
        val sessions = tmuxSessions.distinct()
        if (sessions.isNotEmpty()) {
            append(Tmux.PATH_SETUP).append('\n')
            val validated = micEndpoint?.let(::validateEndpoint)
            for (name in sessions) {
                val safe = Tmux.sanitizeSessionName(name)
                if (validated != null) {
                    append("tmux set-environment -t ").append(safe).append(' ')
                        .append(ENV_NAME).append(' ').append(validated).append(" 2>/dev/null || true\n")
                } else {
                    append("tmux set-environment -u -t ").append(safe).append(' ')
                        .append(ENV_NAME).append(" 2>/dev/null || true\n")
                }
            }
        }
    }

    /** PowerShell command that updates `%USERPROFILE%\.roamtty\voice-mic` and `agy.ps1`. */
    fun windowsUpdateLauncherCommand(micEndpoint: String?): String = windowsEnvCommand(micEndpoint, emptyList())

    /** Combined PowerShell command updating `voice-mic`, `agy.ps1`, and all psmux sessions in one exec. */
    fun windowsEnvCommand(micEndpoint: String?, tmuxSessions: List<String>): String {
        val validated = micEndpoint?.let(::validateEndpoint)
        val micFileLine = if (validated != null) {
            "[IO.File]::WriteAllText(\"\${dir}\\voice-mic\", '$validated')"
        } else {
            "Remove-Item \"\${dir}\\voice-mic\" -ErrorAction SilentlyContinue"
        }
        val encoded = java.util.Base64.getEncoder().encodeToString(windowsLauncherScript(null).toByteArray(Charsets.UTF_8))
        val sessions = tmuxSessions.distinct()
        val psmuxBlock = if (sessions.isEmpty()) "" else buildString {
            append('\n').append(Psmux.PATH_SETUP)
            for (name in sessions) {
                val safe = Tmux.sanitizeSessionName(name)
                if (validated != null) {
                    append("\npsmux set-environment -t '").append(safe).append("' ")
                        .append(ENV_NAME).append(' ').append(validated).append(" 2>\$null")
                } else {
                    append("\npsmux set-environment -u -t '").append(safe).append("' ")
                        .append(ENV_NAME).append(" 2>\$null")
                }
            }
        }
        return PowerShell.command("""
            ${'$'}dir = "${'$'}env:USERPROFILE\.roamtty"
            New-Item -ItemType Directory -Force ${'$'}dir | Out-Null
            $micFileLine
            [IO.File]::WriteAllText("${'$'}dir\agy.ps1", [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('$encoded')))$psmuxBlock
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
        try {
            if (windowsHost) {
                remote.runCommand(windowsEnvCommand(ep, tmuxSessions), 10_000) {}
            } else {
                remote.run(posixEnvScript(ep, tmuxSessions), 10_000) {}
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** Removes `ANTIGRAVITY_MIC` from launchers and tmux/psmux sessions when voice ends cleanly. */
    suspend fun clear(remote: RemoteHost, windowsHost: Boolean, tmuxSessions: List<String>) {
        try {
            if (windowsHost) {
                remote.runCommand(windowsEnvCommand(null, tmuxSessions), 10_000) {}
            } else {
                remote.run(posixEnvScript(null, tmuxSessions), 10_000) {}
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
}

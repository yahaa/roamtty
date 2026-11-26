package io.itermux.ssh

/**
 * psmux, the native Windows tmux, keeps sessions alive on hosts whose OpenSSH shell is cmd.exe or
 * PowerShell. Its session server leaves sshd's kill-on-close job, so a dropped SSH connection
 * ends only the client. Every command runs through [PowerShell.command].
 */
object Psmux {
    const val PROBE = "__ITERMUX_PSMUX__="

    /**
     * sshd's environment predates an install made in this login, and the winget, cargo, scoop and
     * Chocolatey shims may not be on its PATH; [INSTALL_DIR] is where the release fallback unpacks.
     */
    private const val INSTALL_DIR = "\$env:LOCALAPPDATA\\Programs\\psmux"
    private const val PATH_SETUP = "\$env:Path += ';' + ((\"\$env:LOCALAPPDATA\\Microsoft\\WinGet\\Links\"," +
        "\"\$env:USERPROFILE\\.cargo\\bin\",\"\$env:USERPROFILE\\scoop\\shims\",\"\$env:ProgramData\\chocolatey\\bin\"," +
        "\"$INSTALL_DIR\") -join ';')"
    private const val HAS = "function Has(\$c) { [bool](Get-Command \$c -CommandType Application -ErrorAction SilentlyContinue) }"

    private fun script(sessionName: String, body: String) =
        "$PATH_SETUP\n\$n = '${Tmux.sanitizeSessionName(sessionName)}'\n$body"

    val probeCommand: String = PowerShell.command("$PATH_SETUP\n" +
        "'$PROBE' + (Get-Command psmux -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1).Source")

    /** Whether psmux is installed; null when no PowerShell answered (not a native Windows shell). */
    fun parseProbe(output: String?): Boolean? = output?.lineSequence()?.map { it.trim() }
        ?.firstOrNull { it.startsWith(PROBE) }?.let { it.removePrefix(PROBE).isNotBlank() }

    /**
     * Attaches [sessionName], creating it first so the status line can be hidden before the client
     * draws. Mouse mode turns swipes into wheel scrolling of psmux's history, as with tmux.
     */
    fun attachCommand(sessionName: String): String = PowerShell.command(script(sessionName, """
        psmux has-session -t "=${'$'}n" 2>${'$'}null
        if (${'$'}LASTEXITCODE) { psmux new-session -d -s ${'$'}n; if (${'$'}LASTEXITCODE) { exit ${'$'}LASTEXITCODE } }
        psmux set-option -t ${'$'}n status off 2>${'$'}null
        psmux set-option -t ${'$'}n mouse on 2>${'$'}null
        psmux attach-session -t ${'$'}n
        exit ${'$'}LASTEXITCODE
    """.trimIndent()))

    fun displayCommand(sessionName: String, format: String): String {
        require('\'' !in format)
        return PowerShell.command(script(sessionName, "psmux display-message -p -t \$n '$format'\nexit \$LASTEXITCODE"))
    }

    /**
     * Restarts the pane in its folder. Prints nothing: psmux panes run PowerShell, and the ended
     * program's command line is not read back the way the tmux path reads it from `ps`.
     */
    fun restartPaneCommand(sessionName: String): String = PowerShell.command(script(sessionName, """
        ${'$'}d = psmux display-message -p -t ${'$'}n '#{pane_current_path}'
        if (${'$'}d) { psmux respawn-pane -k -c "${'$'}d" -t ${'$'}n } else { psmux respawn-pane -k -t ${'$'}n }
        exit ${'$'}LASTEXITCODE
    """.trimIndent()))

    /** Ends [sessionName] and its panes; exits 0 when the session is already gone. */
    fun killSessionCommand(sessionName: String): String = PowerShell.command(script(sessionName, """
        psmux has-session -t "=${'$'}n" 2>${'$'}null
        if (${'$'}LASTEXITCODE) { exit 0 }
        psmux kill-session -t ${'$'}n
        exit ${'$'}LASTEXITCODE
    """.trimIndent()))

    /** Sets a psmux session environment variable on Windows hosts. */
    fun setEnvironmentCommand(sessionName: String, name: String, value: String): String {
        require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "invalid env var name: $name" }
        require(value.isNotEmpty() && value.none { it.isWhitespace() || it.isISOControl() || it in "'\"\\$`;&|" }) {
            "invalid env var value: $value"
        }
        return PowerShell.command(script(sessionName, "psmux set-environment -t \$n $name $value\nexit \$LASTEXITCODE"))
    }

    /** Removes a psmux session environment variable on Windows hosts. */
    fun unsetEnvironmentCommand(sessionName: String, name: String): String {
        require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "invalid env var name: $name" }
        return PowerShell.command(script(sessionName, "psmux set-environment -u -t \$n $name\nexit \$LASTEXITCODE"))
    }

    /** psmux reports `C:\Users\me`; Windows OpenSSH's SFTP addresses it as `/C:/Users/me`. */
    fun sftpPath(path: String): String? {
        val trimmed = path.trim()
        if (!Regex("^[A-Za-z]:([\\\\/].*)?$").matches(trimmed)) return null
        return "/" + trimmed.replace('\\', '/').trimEnd('/').let { if (it.length == 2) "$it/" else it }
    }

    /**
     * Installs psmux for the login user, no administrator needed: winget's portable package first,
     * else the GitHub release zip for this CPU, checked against the release's SHA-256 digest and
     * added to the user PATH. Ends with [HostPluginInstaller.DONE] once `psmux` resolves.
     */
    val installCommand: String = PowerShell.command("""
        ${'$'}ProgressPreference = 'SilentlyContinue'
        $PATH_SETUP
        $HAS
        try {
          if (-not (Has psmux) -and (Has winget)) {
            winget install --id marlocarlo.psmux -e --silent --disable-interactivity --accept-source-agreements --accept-package-agreements
          }
          if (-not (Has psmux)) {
            [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor 3072
            ${'$'}a = @{ ARM64 = 'arm64'; x86 = 'x86' }[${'$'}env:PROCESSOR_ARCHITECTURE]
            if (-not ${'$'}a) { ${'$'}a = 'x64' }
            ${'$'}r = Invoke-RestMethod https://api.github.com/repos/psmux/psmux/releases/latest -UseBasicParsing
            ${'$'}z = ${'$'}r.assets | Where-Object { ${'$'}_.name -like "*-windows-${'$'}a.zip" } | Select-Object -First 1
            if (-not ${'$'}z) { throw "No psmux release for ${'$'}a" }
            "Downloading ${'$'}(${'$'}z.name)"
            ${'$'}f = Join-Path ${'$'}env:TEMP ${'$'}z.name
            Invoke-WebRequest ${'$'}z.browser_download_url -OutFile ${'$'}f -UseBasicParsing
            if (${'$'}z.digest -and ${'$'}z.digest -ne ('sha256:' + (Get-FileHash ${'$'}f -Algorithm SHA256).Hash)) { throw 'Checksum mismatch' }
            Expand-Archive ${'$'}f "$INSTALL_DIR" -Force
            Remove-Item ${'$'}f
            ${'$'}u = [Environment]::GetEnvironmentVariable('Path', 'User')
            if ((${'$'}u -split ';') -notcontains "$INSTALL_DIR") {
              [Environment]::SetEnvironmentVariable('Path', ("${'$'}u;$INSTALL_DIR").TrimStart(';'), 'User')
            }
          }
          if (Has psmux) { psmux -V; '${HostPluginInstaller.DONE}'; exit 0 }
          'psmux was not found after installing.'
        } catch { "${'$'}_" }
        exit 1
    """.trimIndent())
}

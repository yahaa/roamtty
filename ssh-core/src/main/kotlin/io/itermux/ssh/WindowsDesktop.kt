package io.itermux.ssh

/**
 * Remote desktop on hosts whose OpenSSH shell is cmd.exe or PowerShell. Every script goes through
 * [PowerShell.command]; output uses the same markers as the POSIX probe ([HostPluginProbe]).
 */
object WindowsDesktop {
    private const val BIN = "\$env:USERPROFILE\\.roamtty\\bin"
    private const val TASK = "Roamtty Desktop"

    /**
     * Read-only: the console's signed-in user (works on Home editions, unlike `query user`), the CPU
     * architecture, and the plugin's version line.
     */
    val probeCommand: String = PowerShell.command("""
        ${'$'}d = 'no'
        try {
          ${'$'}u = (Get-CimInstance Win32_ComputerSystem -ErrorAction Stop).UserName
          if (${'$'}u -and (${'$'}u -split '\\')[-1] -eq ${'$'}env:USERNAME) { ${'$'}d = 'yes' } else { ${'$'}d = 'nologin' }
        } catch {}
        '${HostPluginProbe.DESKTOP}' + ${'$'}d
        '${HostPluginProbe.UNAME}Windows ' + ${'$'}env:PROCESSOR_ARCHITECTURE
        ${'$'}p = "$BIN\roamtty-desktop.exe"; ${'$'}v = ''
        if (Test-Path ${'$'}p) { ${'$'}v = (& ${'$'}p --version 2>${'$'}null | Select-Object -First 1) }
        '${HostPluginProbe.DESKTOP_PLUGIN}' + ${'$'}v
    """.trimIndent())

    /** `serve` through cmd /c, whichever shell sshd runs: PowerShell would not pass a binary stream through. */
    fun serveCommand(args: String): String = "cmd /c \"\"%USERPROFILE%\\.roamtty\\bin\\roamtty-desktop.exe\" serve $args\""

    /** cmd's "is not recognized as a command": the plugin is not installed. */
    const val NOT_FOUND = 9009

    private fun quote(s: String) = "'" + s.replace("'", "''") + "'"

    /** Stops a running agent (its exe is locked while it runs), checks [path] against [sha], moves it into place. */
    fun placeScript(path: String, sha: String): String = """
        ${'$'}b = "$BIN"; ${'$'}f = $path
        ${'$'}h = (Get-FileHash ${'$'}f -Algorithm SHA256).Hash.ToLower()
        if (${'$'}h -ne ${quote(sha)}) { '${DesktopPluginInstaller.SHA_MISMATCH} ' + ${'$'}h; Remove-Item -Force ${'$'}f; exit 4 }
        schtasks /End /TN ${quote(TASK)} 2>${'$'}null | Out-Null
        Stop-Process -Name roamtty-desktop -Force -ErrorAction SilentlyContinue
        Start-Sleep -Milliseconds 300
        Move-Item -Force ${'$'}f "${'$'}b\roamtty-desktop.exe"
        exit 0
    """.trimIndent()

    /** The host downloads the exe itself with Windows' own curl.exe (20 s at most). */
    fun hostDownloadScript(url: String, sha: String): String = """
        ${'$'}b = "$BIN"; New-Item -ItemType Directory -Force ${'$'}b | Out-Null
        ${'$'}f = "${'$'}b\roamtty-desktop.part"
        curl.exe -fsSL --max-time 20 -o ${'$'}f ${quote(url)}
        if (${'$'}LASTEXITCODE -ne 0 -or -not (Test-Path ${'$'}f)) { Remove-Item -Force -ErrorAction SilentlyContinue ${'$'}f; '${DesktopPluginInstaller.FETCH_FAILED}'; exit 3 }
    """.trimIndent() + "\n" + placeScript("\"\$b\\roamtty-desktop.part\"", sha)

    val mkdirScript = "New-Item -ItemType Directory -Force \"$BIN\", \"\$env:USERPROFILE\\.roamtty\\desktop\" | Out-Null; exit 0"

    /** OpenH264 (fetched by the plugin itself, or from [uploaded]) and the logon task. */
    fun installScript(uploaded: String? = null): String =
        "& \"$BIN\\roamtty-desktop.exe\" install" + (uploaded?.let { " --openh264 \"\$env:USERPROFILE\\$it\"" } ?: "") + "; exit ${'$'}LASTEXITCODE"
}

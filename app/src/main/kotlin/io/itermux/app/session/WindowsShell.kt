package io.itermux.app.session

import io.itermux.ssh.PowerShell

/** Native Windows paths and PowerShell commands, independent of the SSH server's default shell. */
internal object WindowsShell {
    private val drive = Regex("^[A-Za-z]:[/\\\\].*")
    private val unc = Regex("^(?:\\\\\\\\|//)[^/\\\\]+[/\\\\][^/\\\\]+(?:[/\\\\].*)?$")

    fun isPath(path: String) = drive.matches(path) || unc.matches(path)

    fun root(path: String): String = if (path.startsWith("//"))
        "//" + path.removePrefix("//").split('/').take(2).joinToString("/") else path.take(3)

    fun normalize(path: String): String {
        val slashes = path.replace('\\', '/')
        return slashes.trimEnd('/').let { if (it.length == 2 && it[1] == ':') "$it/" else it }
    }

    fun literal(value: String): String = PowerShell.literal(value)

    fun command(script: String, interactive: Boolean = false): String = PowerShell.command(script, interactive)

    fun launch(directory: String, after: String? = null): String = command(
        "try { Set-Location -LiteralPath ${literal(normalize(directory))} -ErrorAction Stop" +
            (after?.let { "; $it" } ?: "") + " } catch { Write-Error \$_ }", interactive = true)
}

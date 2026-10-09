package io.itermux.app.session

import java.util.Base64

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

    // Decode data inside PowerShell: neither the outer CMD/PowerShell parser nor smart quotes can expand a path.
    fun literal(value: String): String = "([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('" +
        Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8)) + "')))"

    fun command(script: String, interactive: Boolean = false): String {
        val options = if (interactive) "-NoExit" else "-NoProfile -NonInteractive -OutputFormat Text"
        return "powershell.exe -NoLogo $options -EncodedCommand " +
            Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
    }

    fun launch(directory: String, after: String? = null): String = command(
        "try { Set-Location -LiteralPath ${literal(normalize(directory))} -ErrorAction Stop" +
            (after?.let { "; $it" } ?: "") + " } catch { Write-Error \$_ }", interactive = true)
}

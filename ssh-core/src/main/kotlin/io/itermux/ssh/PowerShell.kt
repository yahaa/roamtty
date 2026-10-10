package io.itermux.ssh

import java.util.Base64

/**
 * Windows PowerShell 5.1 commands that run the same from cmd.exe or PowerShell as the OpenSSH
 * default shell. cmd.exe caps a command line at 8191 characters, so an encoded script must stay
 * under about 3000 characters.
 */
object PowerShell {
    // Decode data inside PowerShell: neither the outer CMD/PowerShell parser nor smart quotes can expand a value.
    fun literal(value: String): String = "([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('" +
        Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8)) + "')))"

    fun command(script: String, interactive: Boolean = false): String {
        val options = if (interactive) "-NoExit" else "-NoProfile -NonInteractive -OutputFormat Text"
        return "powershell.exe -NoLogo $options -EncodedCommand " +
            Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
    }
}

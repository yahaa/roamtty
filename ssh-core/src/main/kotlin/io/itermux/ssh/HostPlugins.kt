package io.itermux.ssh

/** Remote helpers iTermux can use: tmux keeps sessions alive, mosh keeps the terminal alive over roaming UDP. */
enum class HostPlugin(val id: String) {
    TMUX("tmux"), MOSH("mosh");

    companion object {
        /** Stored as a comma-separated list; null means "never probed". */
        fun decode(value: String?): Set<HostPlugin>? = value?.split(',')
            ?.mapNotNull { id -> entries.firstOrNull { it.id == id.trim() } }?.toSet()

        fun encode(plugins: Set<HostPlugin>): String = entries.filter { it in plugins }.joinToString(",") { it.id }
    }
}

internal object HostPluginProbe {
    const val TMUX = "__ITERMUX_TMUX__="
    const val MOSH = "__ITERMUX_MOSH__="
    const val command = "/bin/sh -s"

    /** Non-login exec shells often lack Homebrew or /usr/local paths, so mosh-server is also looked up there. */
    const val moshLookup = """m=${'$'}(command -v mosh-server 2>/dev/null)
for p in /usr/local/bin/mosh-server /opt/homebrew/bin/mosh-server /usr/bin/mosh-server /snap/bin/mosh-server; do
  [ -z "${'$'}m" ] && [ -x "${'$'}p" ] && m=${'$'}p
done"""

    val script = """
        printf '$TMUX%s\n' "${'$'}(command -v tmux 2>/dev/null)"
        $moshLookup
        printf '$MOSH%s\n' "${'$'}m"
        exit 0
    """.trimIndent()

    fun parse(output: String?): Set<HostPlugin>? {
        val lines = output?.lineSequence()?.map { it.trim() }?.toList() ?: return null
        val tmux = lines.firstOrNull { it.startsWith(TMUX) } ?: return null
        val mosh = lines.firstOrNull { it.startsWith(MOSH) } ?: return null
        return buildSet {
            if (tmux.removePrefix(TMUX).isNotBlank()) add(HostPlugin.TMUX)
            if (mosh.removePrefix(MOSH).isNotBlank()) add(HostPlugin.MOSH)
        }
    }
}

/** Installs a plugin with the host's package manager. Output is merged into one stream for display. */
object HostPluginInstaller {
    const val SUDO_FAILED = "__ITERMUX_SUDO_FAILED__"
    const val NO_PRIVILEGE = "__ITERMUX_NO_PRIVILEGE__"
    const val NO_PACKAGE_MANAGER = "__ITERMUX_NO_PACKAGE_MANAGER__"
    const val DONE = "__ITERMUX_INSTALL_DONE__"

    enum class Result { INSTALLED, SUDO_PASSWORD, NO_PRIVILEGE, NO_PACKAGE_MANAGER, FAILED }

    /**
     * The script reaches `/bin/sh -s` on stdin, so a sudo password never appears in a process list.
     * `sudo -S -k` always reads the password line, so it is never passed on to the package manager.
     */
    fun script(plugin: HostPlugin, sudoPassword: String?): String {
        val password = sudoPassword?.takeIf { it.isNotEmpty() && '\n' !in it && '\r' !in it }.orEmpty()
        val quoted = "'" + password.replace("'", "'\\''") + "'"
        val pkg = plugin.id
        // RHEL-family hosts ship mosh only in EPEL.
        val epel = if (plugin == HostPlugin.MOSH) """ || { run "${'$'}1" install -y epel-release && run "${'$'}1" install -y $pkg; }""" else ""
        return """
            exec 2>&1
            PW=$quoted
            if [ "${'$'}(id -u)" = 0 ]; then run() { "${'$'}@"; }
            elif command -v sudo >/dev/null 2>&1 && sudo -n true >/dev/null 2>&1; then run() { sudo -n "${'$'}@"; }
            elif command -v sudo >/dev/null 2>&1 && [ -n "${'$'}PW" ]; then
              printf '%s\n' "${'$'}PW" | sudo -S -k -p '' true >/dev/null 2>&1 || { echo $SUDO_FAILED; exit 3; }
              run() { printf '%s\n' "${'$'}PW" | sudo -S -k -p '' "${'$'}@"; }
            elif command -v brew >/dev/null 2>&1; then run() { "${'$'}@"; }
            elif command -v sudo >/dev/null 2>&1; then echo $SUDO_FAILED; exit 3
            else echo $NO_PRIVILEGE; exit 5
            fi
            dnf_like() { run "${'$'}1" install -y $pkg$epel; }
            if command -v brew >/dev/null 2>&1 && [ "${'$'}(uname -s)" = Darwin ]; then brew install $pkg
            elif command -v apt-get >/dev/null 2>&1; then
              run env DEBIAN_FRONTEND=noninteractive apt-get update -q && run env DEBIAN_FRONTEND=noninteractive apt-get install -y -q $pkg
            elif command -v dnf >/dev/null 2>&1; then dnf_like dnf
            elif command -v yum >/dev/null 2>&1; then dnf_like yum
            elif command -v pacman >/dev/null 2>&1; then run pacman -Sy --noconfirm --needed $pkg
            elif command -v zypper >/dev/null 2>&1; then run zypper --non-interactive install $pkg
            elif command -v apk >/dev/null 2>&1; then run apk add $pkg
            elif command -v pkg >/dev/null 2>&1; then run pkg install -y $pkg
            elif command -v brew >/dev/null 2>&1; then brew install $pkg
            else echo $NO_PACKAGE_MANAGER; exit 4
            fi
            status=${'$'}?
            [ ${'$'}status = 0 ] && echo $DONE
            exit ${'$'}status
        """.trimIndent()
    }

    fun classify(exitStatus: Int?, output: String): Result = when {
        exitStatus == 0 && DONE in output -> Result.INSTALLED
        SUDO_FAILED in output -> Result.SUDO_PASSWORD
        NO_PRIVILEGE in output -> Result.NO_PRIVILEGE
        NO_PACKAGE_MANAGER in output -> Result.NO_PACKAGE_MANAGER
        else -> Result.FAILED
    }

    /** Hides the script's internal markers from the log the user reads. */
    fun displayLine(line: String): String? = line.takeUnless { it.startsWith("__ITERMUX_") }
}

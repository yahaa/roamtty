package io.itermux.ssh

/**
 * Remote helpers Roamtty can use: tmux keeps sessions alive, mosh keeps the terminal alive over
 * roaming UDP. [windows] plugins are for hosts whose OpenSSH shell is cmd.exe or PowerShell, where
 * psmux takes tmux's place; MSYS2 and Cygwin shells use the POSIX ones. The desktop plugin is
 * Roamtty's own binary: the desktop page installs it, never a package manager.
 */
enum class HostPlugin(val id: String, val windows: Boolean = false, val packaged: Boolean = true) {
    TMUX("tmux"), MOSH("mosh"), PSMUX("psmux", windows = true), DESKTOP("desktop", packaged = false);

    /** Keeps remote work running across disconnects, so new sessions run inside it. */
    val keepsSessions: Boolean get() = this == TMUX || this == PSMUX

    companion object {
        /** The plugins that can be installed on a host with (or without) a native Windows shell. */
        fun offered(nativeWindows: Boolean): List<HostPlugin> = entries.filter { it.packaged && it.windows == nativeWindows }

        /** Stored as a comma-separated list; null means "never probed". */
        fun decode(value: String?): Set<HostPlugin>? = value?.split(',')
            ?.mapNotNull { id -> entries.firstOrNull { it.id == id.trim() } }?.toSet()

        fun encode(plugins: Set<HostPlugin>): String = entries.filter { it in plugins }.joinToString(",") { it.id }
    }
}

/**
 * What the read-only desktop probe saw: [desktop] is yes, no or nologin; [uname] is `uname -sm`;
 * [pluginVersion] the first line of `roamtty-desktop --version`, null when it is not installed.
 */
data class HostDesktop(val desktop: String, val uname: String, val pluginVersion: String?) {
    /** "roamtty-desktop 0.1.0 proto 1" → 0.1.0 */
    val version: String? get() = pluginVersion?.split(' ')?.getOrNull(1)
}

internal object HostPluginProbe {
    const val TMUX = "__ITERMUX_TMUX__="
    const val MOSH = "__ITERMUX_MOSH__="
    const val DESKTOP = "__ITERMUX_DESKTOP__="
    const val UNAME = "__ITERMUX_UNAME__="
    const val DESKTOP_PLUGIN = "__ITERMUX_DESKTOP_PLUGIN__="
    const val command = "/bin/sh -s"

    /** Looks mosh-server up in [m] with the Homebrew, MacPorts and snap paths a non-login shell misses. */
    const val moshLookup = """${Tmux.PATH_SETUP}
m=${'$'}(command -v mosh-server 2>/dev/null)"""

    /**
     * Read-only: a graphical session this user owns. Linux: an X or Wayland socket of the user's
     * (Xorg as the user, Xvfb, a compositor) or a logind x11/wayland session; `unsupported` for GNOME or
     * KDE on Wayland (they need the desktop portal, which the plugin lacks), unless a real X server runs
     * beside them. macOS: someone at the console.
     */
    private val desktopProbe = """
        d=no
        case "${'$'}(uname -s 2>/dev/null)" in
          Darwin) if who 2>/dev/null | grep -q console; then d=yes; else d=nologin; fi ;;
          Linux)
            u=${'$'}(id -u); x=no; w=no
            for s in /tmp/.X11-unix/X*; do [ -S "${'$'}s" ] && [ -O "${'$'}s" ] && x=yes; done
            for s in "${'$'}{XDG_RUNTIME_DIR:-/run/user/${'$'}u}"/wayland-[0-9]*; do [ -S "${'$'}s" ] && [ -O "${'$'}s" ] && w=yes; done
            [ ${'$'}x = yes ] || [ ${'$'}w = yes ] && d=yes
            if [ ${'$'}w = yes ] && pgrep -u "${'$'}u" -x 'gnome-shell|kwin_wayland' >/dev/null 2>&1; then
              if [ ${'$'}x = no ] || pgrep -u "${'$'}u" -x Xwayland >/dev/null 2>&1; then d=unsupported; fi
            fi
            if [ ${'$'}d = no ] && command -v loginctl >/dev/null 2>&1; then
              for i in ${'$'}(loginctl list-sessions --no-legend 2>/dev/null | awk -v u="${'$'}u" '${'$'}2==u{print ${'$'}1}'); do
                case "${'$'}(loginctl show-session "${'$'}i" -p Type --value 2>/dev/null)" in
                  x11) d=yes ;;
                  wayland) case "${'$'}(loginctl show-session "${'$'}i" -p Desktop --value 2>/dev/null)" in
                      *[Gg][Nn][Oo][Mm][Ee]*|*[Kk][Dd][Ee]*|*[Pp]lasma*) [ ${'$'}d = yes ] || d=unsupported ;;
                      *) d=yes ;;
                    esac ;;
                esac
              done
            fi ;;
        esac
        printf '$DESKTOP%s\n' "${'$'}d"
        printf '$UNAME%s\n' "${'$'}(uname -sm 2>/dev/null)"
        printf '$DESKTOP_PLUGIN%s\n' "${'$'}("${'$'}HOME/.roamtty/bin/roamtty-desktop" --version 2>/dev/null | head -n 1)"
    """.trimIndent().replace("\n", "\n        ")

    val script = """
        $moshLookup
        printf '$TMUX%s\n' "${'$'}(command -v tmux 2>/dev/null)"
        printf '$MOSH%s\n' "${'$'}m"
        $desktopProbe
        exit 0
    """.trimIndent()

    fun parseDesktop(output: String?): HostDesktop? {
        val lines = output?.lineSequence()?.map { it.trim() }?.toList() ?: return null
        fun value(prefix: String) = lines.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
        val desktop = value(DESKTOP)?.takeIf { it in setOf("yes", "no", "nologin", "unsupported") } ?: return null
        return HostDesktop(desktop, value(UNAME).orEmpty(), value(DESKTOP_PLUGIN)?.takeIf { it.startsWith("roamtty-desktop ") })
    }

    fun parse(output: String?): Set<HostPlugin>? {
        val lines = output?.lineSequence()?.map { it.trim() }?.toList() ?: return null
        val tmux = lines.firstOrNull { it.startsWith(TMUX) } ?: return null
        val mosh = lines.firstOrNull { it.startsWith(MOSH) } ?: return null
        return buildSet {
            if (tmux.removePrefix(TMUX).isNotBlank()) add(HostPlugin.TMUX)
            if (mosh.removePrefix(MOSH).isNotBlank()) add(HostPlugin.MOSH)
            if (parseDesktop(output)?.pluginVersion != null) add(HostPlugin.DESKTOP)
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
        // Homebrew, MSYS2, Cygwin and Termux install into the user's own prefix; the system
        // package managers of Linux, MacPorts and BSD need root or sudo.
        return """
            exec 2>&1
            ${Tmux.PATH_SETUP}
            PW=$quoted
            need_root() {
              if [ "${'$'}(id -u)" = 0 ]; then run() { "${'$'}@"; }
              elif command -v sudo >/dev/null 2>&1 && sudo -n true >/dev/null 2>&1; then run() { sudo -n "${'$'}@"; }
              elif command -v sudo >/dev/null 2>&1 && [ -n "${'$'}PW" ]; then
                printf '%s\n' "${'$'}PW" | sudo -S -k -p '' true >/dev/null 2>&1 || { echo $SUDO_FAILED; exit 3; }
                run() { printf '%s\n' "${'$'}PW" | sudo -S -k -p '' "${'$'}@"; }
              elif command -v sudo >/dev/null 2>&1; then echo $SUDO_FAILED; exit 3
              else echo $NO_PRIVILEGE; exit 5
              fi
            }
            has() { command -v "${'$'}1" >/dev/null 2>&1; }
            none() { echo $NO_PACKAGE_MANAGER; exit 4; }
            dnf_like() { need_root; run "${'$'}1" install -y $pkg$epel; }
            case "${'$'}(uname -s 2>/dev/null)" in
              Darwin)
                if has brew; then brew install $pkg
                elif has port; then need_root; run port -N install $pkg
                else none; fi ;;
              MSYS*|MINGW*)
                if has pacman; then pacman -Sy --noconfirm --needed $pkg; else none; fi ;;
              CYGWIN*)
                if has apt-cyg; then apt-cyg install $pkg; else none; fi ;;
              *)
                if [ -n "${'$'}TERMUX_VERSION" ] && has pkg; then pkg install -y $pkg
                elif [ "${'$'}(id -u)" != 0 ] && ! has sudo && has brew; then brew install $pkg
                elif has apt-get; then
                  need_root
                  run env DEBIAN_FRONTEND=noninteractive apt-get update -q && run env DEBIAN_FRONTEND=noninteractive apt-get install -y -q $pkg
                elif has dnf; then dnf_like dnf
                elif has yum; then dnf_like yum
                elif has pacman; then need_root; run pacman -Sy --noconfirm --needed $pkg
                elif has zypper; then need_root; run zypper --non-interactive install $pkg
                elif has apk; then need_root; run apk add $pkg
                elif has pkg; then need_root; run pkg install -y $pkg
                elif has brew; then brew install $pkg
                else none; fi ;;
            esac
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

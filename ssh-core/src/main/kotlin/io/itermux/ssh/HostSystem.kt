package io.itermux.ssh

import java.util.Locale

/** Stable names are stored in the existing hosts.os column. */
enum class HostSystem(val label: String, val isLinux: Boolean = false) {
    UNKNOWN("未知系统"), OTHER("其他系统"), LINUX("Linux", true),
    WINDOWS("Windows"), MACOS("macOS"),
    UBUNTU("Ubuntu", true), DEBIAN("Debian", true), CENTOS("CentOS", true),
    FEDORA("Fedora", true), ARCH("Arch Linux", true),
    REDHAT("Red Hat Enterprise Linux", true), OPENSUSE("openSUSE", true),
    ROCKY("Rocky Linux", true), ALMALINUX("AlmaLinux", true),
    GENTOO("Gentoo", true), NIXOS("NixOS", true), LINUXMINT("Linux Mint", true);

    companion object {
        fun fromStored(value: String) = entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

/** Read release files as data, never source them as shell code. ID_LIKE is deliberately ignored:
 * a derivative without its own supported logo should retain Tux, not a parent's brand. */
internal object HostSystemProbe {
    const val KERNEL = "__ITERMUX_KERNEL__"
    const val RELEASE = "__ITERMUX_OS_RELEASE__"
    const val LEGACY = "__ITERMUX_LEGACY_RELEASE__"
    val command = """
        printf '$KERNEL\n'; uname -s 2>/dev/null
        printf '$RELEASE\n'
        if [ -r /etc/os-release ]; then head -c 4096 /etc/os-release
        elif [ -r /usr/lib/os-release ]; then head -c 4096 /usr/lib/os-release; fi
        printf '\n$LEGACY\n'
        if [ -r /etc/centos-release ]; then head -c 512 /etc/centos-release
        elif [ -r /etc/redhat-release ]; then head -c 512 /etc/redhat-release; fi
        exit 0
    """.trimIndent()
    const val windowsCommand = "cmd.exe /c ver"

    fun parse(unix: String?, windows: String? = null): HostSystem {
        val kernel = unix.orEmpty().substringAfter("$KERNEL\n", "")
            .substringBefore(RELEASE).trim().lowercase(Locale.ROOT)
        return when {
            kernel == "linux" -> linux(
                unix.orEmpty().substringAfter("$RELEASE\n", "").substringBefore(LEGACY),
                unix.orEmpty().substringAfter("$LEGACY\n", ""))
            kernel == "darwin" -> HostSystem.MACOS
            kernel.startsWith("mingw") || kernel.startsWith("msys") || kernel.startsWith("cygwin") -> HostSystem.WINDOWS
            Regex("(?im)^\\s*Microsoft Windows\\s+\\[").containsMatchIn(windows.orEmpty()) -> HostSystem.WINDOWS
            kernel in setOf("freebsd", "openbsd", "netbsd", "dragonfly", "sunos", "aix") -> HostSystem.OTHER
            else -> HostSystem.UNKNOWN
        }
    }

    private fun linux(release: String, legacy: String): HostSystem {
        val values = release.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith('#') && '=' in it }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim().removeSurrounding("\"").removeSurrounding("'") }
        return when (values["ID"]?.lowercase(Locale.ROOT)) {
            "ubuntu" -> HostSystem.UBUNTU
            "debian" -> HostSystem.DEBIAN
            "centos" -> HostSystem.CENTOS
            "fedora" -> HostSystem.FEDORA
            "arch" -> HostSystem.ARCH
            "rhel" -> HostSystem.REDHAT
            "opensuse", "opensuse-leap", "opensuse-tumbleweed" -> HostSystem.OPENSUSE
            "rocky" -> HostSystem.ROCKY
            "almalinux" -> HostSystem.ALMALINUX
            "gentoo" -> HostSystem.GENTOO
            "nixos" -> HostSystem.NIXOS
            "linuxmint" -> HostSystem.LINUXMINT
            // Old CentOS/RHEL releases predate os-release. Don't let compatibility files
            // override a modern, explicitly identified (but unsupported) distribution.
            null, "" -> when {
                legacy.trimStart().startsWith("CentOS", ignoreCase = true) -> HostSystem.CENTOS
                legacy.trimStart().startsWith("Red Hat Enterprise Linux", ignoreCase = true) -> HostSystem.REDHAT
                else -> HostSystem.LINUX
            }
            else -> HostSystem.LINUX
        }
    }
}

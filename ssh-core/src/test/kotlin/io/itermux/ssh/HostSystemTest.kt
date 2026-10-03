package io.itermux.ssh

import org.junit.Assert.*
import org.junit.Test

class HostSystemTest {
    private fun output(release: String = "", kernel: String = "Linux", legacy: String = "") =
        "Login banner\n${HostSystemProbe.KERNEL}\n$kernel\n${HostSystemProbe.RELEASE}\n$release\n${HostSystemProbe.LEGACY}\n$legacy"

    @Test fun distributionIdWinsOverParentAndDisplayName() {
        val systems = mapOf(
            "ubuntu" to HostSystem.UBUNTU, "debian" to HostSystem.DEBIAN, "centos" to HostSystem.CENTOS,
            "fedora" to HostSystem.FEDORA, "arch" to HostSystem.ARCH, "rhel" to HostSystem.REDHAT,
            "opensuse" to HostSystem.OPENSUSE, "opensuse-leap" to HostSystem.OPENSUSE,
            "opensuse-tumbleweed" to HostSystem.OPENSUSE, "rocky" to HostSystem.ROCKY,
            "almalinux" to HostSystem.ALMALINUX, "gentoo" to HostSystem.GENTOO,
            "nixos" to HostSystem.NIXOS, "linuxmint" to HostSystem.LINUXMINT)
        systems.forEach { (id, system) ->
            assertEquals(id, system, HostSystemProbe.parse(output("NAME=Linux\nID=$id\nID_LIKE=debian")))
        }
    }

    @Test fun releaseAssignmentsHandleQuotesWhitespaceCommentsAndRepeatedKeys() {
        assertEquals(HostSystem.UBUNTU, HostSystemProbe.parse(output("  # ID=debian\nID='debian'\n ID = \"ubuntu\" \nNAME=Ubuntu")))
        assertEquals(HostSystem.CENTOS, HostSystemProbe.parse(output("ID='centos'\nNAME=\"CentOS Stream\"")))
    }

    @Test fun unsupportedAndMissingLinuxIdsKeepTux() {
        listOf("", "ID=linux", "ID=custom\nID_LIKE=ubuntu", "NAME=Ubuntu", "ID=alpine", "ID=sles", "ID=\"$(echo ubuntu)\"")
            .forEach { assertEquals(it, HostSystem.LINUX, HostSystemProbe.parse(output(it))) }
    }

    @Test fun oldCentosAndRedHatUseTheirReleaseFiles() {
        assertEquals(HostSystem.CENTOS, HostSystemProbe.parse(output(legacy = "CentOS release 6.10 (Final)")))
        assertEquals(HostSystem.REDHAT, HostSystemProbe.parse(output(legacy = "Red Hat Enterprise Linux Server release 6.10")))
        assertEquals(HostSystem.LINUX, HostSystemProbe.parse(output("ID=custom", legacy = "CentOS release 7")))
    }

    @Test fun detectsMacWindowsAndOtherUnixWithoutMistakingBannersForResults() {
        assertEquals(HostSystem.MACOS, HostSystemProbe.parse(output(kernel = "Darwin")))
        listOf("MINGW64_NT-10.0", "MSYS_NT-10.0", "CYGWIN_NT-10.0").forEach {
            assertEquals(HostSystem.WINDOWS, HostSystemProbe.parse(output(kernel = it)))
        }
        assertEquals(HostSystem.WINDOWS, HostSystemProbe.parse(null, "\r\nMicrosoft Windows [Version 10.0.20348.2402]\r\n"))
        assertEquals(HostSystem.OTHER, HostSystemProbe.parse(output(kernel = "FreeBSD")))
        assertEquals(HostSystem.UNKNOWN, HostSystemProbe.parse("Welcome to Ubuntu Linux", "cmd.exe: command not found"))
        assertEquals(HostSystem.UNKNOWN, HostSystemProbe.parse(null))
    }

    @Test fun existingStoredTypesRemainReadable() {
        listOf(HostSystem.UNKNOWN, HostSystem.LINUX, HostSystem.WINDOWS, HostSystem.MACOS, HostSystem.OTHER)
            .forEach { assertEquals(it, HostSystem.fromStored(it.name)) }
        assertEquals(HostSystem.UNKNOWN, HostSystem.fromStored("future-value"))
    }
}

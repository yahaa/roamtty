package io.itermux.ssh

import com.jcraft.jsch.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What an authenticated connection learns of its host beside the terminal: the system, the plugins it has and
 * its desktop. [run] runs a command (with optional stdin) on its own exec channel and gives what it printed.
 */
internal class HostProbes(private val run: suspend (ssh: Session, command: String, input: String?) -> String?) {
    private val mutableSystem = MutableStateFlow<HostSystem?>(null)
    val system = mutableSystem.asStateFlow()
    private val mutablePlugins = MutableStateFlow<Set<HostPlugin>?>(null)
    /** tmux/mosh availability, probed with the system on every authenticated connection. */
    val plugins = mutablePlugins.asStateFlow()
    private val mutableDesktop = MutableStateFlow<HostDesktop?>(null)
    /** The read-only desktop probe that runs with the plugin probe; null until it answered. */
    val desktop = mutableDesktop.asStateFlow()
    private val mutableNativeWindows = MutableStateFlow<Boolean?>(null)
    /** True once a plugin probe found cmd.exe or PowerShell, not a POSIX shell; null until probed. */
    val nativeWindows = mutableNativeWindows.asStateFlow()

    suspend fun probeSystem(ssh: Session) {
        val unix = run(ssh, HostSystemProbe.command, HostSystemProbe.script)?.replace("\r\n", "\n")
        var result = HostSystemProbe.parse(unix)
        if (result == HostSystem.UNKNOWN) {
            result = HostSystemProbe.parse(unix, run(ssh, HostSystemProbe.windowsCommand, null))
        }
        if (result != HostSystem.UNKNOWN) mutableSystem.value = result
        // Windows hosts whose OpenSSH shell is MSYS2 or Cygwin bash run the same POSIX probe.
        if (unix?.contains(HostSystemProbe.KERNEL) == true) probePlugins(ssh)
        else if (result == HostSystem.WINDOWS) probeWindowsPlugins(ssh)
    }

    /**
     * JSch's channel open waits out its whole timeout when the connection drops and ignores
     * cancellation, so the terminal must not join the probe: a drop would be reported 5 s late.
     */
    fun launch(ssh: Session): Job =
        CoroutineScope(Dispatchers.IO).launch { runCatching { probeSystem(ssh) } }

    /** POSIX shells first; a host without one may still answer through PowerShell. */
    suspend fun probePlugins(ssh: Session): Set<HostPlugin>? {
        val output = run(ssh, HostPluginProbe.command, HostPluginProbe.script)
        HostPluginProbe.parseDesktop(output)?.let { mutableDesktop.value = it }
        return HostPluginProbe.parse(output)
            ?.also { mutableNativeWindows.value = false; mutablePlugins.value = it }
            ?: probeWindowsPlugins(ssh)
    }

    private suspend fun probeWindowsPlugins(ssh: Session): Set<HostPlugin>? {
        val found = Psmux.parseProbe(run(ssh, Psmux.probeCommand, null))
            ?.let { found -> if (found) setOf(HostPlugin.PSMUX) else emptySet() } ?: return null
        mutableNativeWindows.value = true
        // The desktop side of a native Windows shell: the console user and the plugin, read-only.
        val desktop = HostPluginProbe.parseDesktop(run(ssh, WindowsDesktop.probeCommand, null))
        desktop?.let { mutableDesktop.value = it }
        val plugins = if (desktop?.pluginVersion != null) found + HostPlugin.DESKTOP else found
        mutablePlugins.value = plugins
        return plugins
    }
}

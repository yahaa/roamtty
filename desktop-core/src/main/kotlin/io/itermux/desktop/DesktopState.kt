package io.itermux.desktop

/** What the read-only probe found on a host: a desktop session, none, or a desktop nobody is logged in to. */
enum class DesktopProbe(val id: String) {
    YES("yes"), NO("no"), NOLOGIN("nologin");

    companion object {
        fun decode(value: String?): DesktopProbe? = entries.firstOrNull { it.id == value }
        /** Probe results older than this are probed again before they count. */
        const val CACHE_MS = 7L * 24 * 3600 * 1000
    }
}

/** Where one host's desktop connection is. */
sealed interface DesktopPhase {
    data object Idle : DesktopPhase
    /** Probing, installing or starting the plugin, or waiting for the first frame. */
    data class Connecting(val step: Step, val detail: String = "") : DesktopPhase
    /** The host needs the user: the [needs] of the hello, e.g. `not-logged-in`. */
    data class NeedsAction(val needs: List<String>) : DesktopPhase
    data class Connected(val poor: Boolean = false, val paused: Boolean = false) : DesktopPhase
    /** The link dropped after the first frame; the last frame stays on screen. */
    data class Recovering(val sinceMs: Long) : DesktopPhase
    data class Failed(val message: String) : DesktopPhase

    enum class Step { LOGIN, INSTALL, START, FIRST_FRAME }
}

/** The desktop button on a session card. */
enum class DesktopButton {
    HIDDEN, OPENABLE, CONNECTING, NEEDS_ACTION, CONNECTED, POOR, RECOVERING;

    companion object {
        /**
         * §4.3 of the plan. [osSupported]: the pinned plugin has a build for the host's system.
         * Linux hosts show the button only once a probe found a desktop; macOS and Windows show it unprobed.
         */
        fun derive(enabled: Boolean, hostOnline: Boolean, sessionConnected: Boolean, os: String, osSupported: Boolean,
            probe: DesktopProbe?, probedAt: Long?, now: Long, phase: DesktopPhase): DesktopButton {
            // A desktop already open keeps its button whatever the session or probe say now.
            when (phase) {
                is DesktopPhase.Connected -> return if (phase.poor) POOR else CONNECTED
                is DesktopPhase.Recovering -> return RECOVERING
                is DesktopPhase.Connecting -> return CONNECTING
                is DesktopPhase.NeedsAction -> return NEEDS_ACTION
                else -> {}
            }
            if (!enabled || !hostOnline || !sessionConnected || !osSupported) return HIDDEN
            val fresh = probe?.takeIf { probedAt != null && now - probedAt < DesktopProbe.CACHE_MS }
            return when (fresh) {
                DesktopProbe.NO -> HIDDEN
                DesktopProbe.NOLOGIN -> NEEDS_ACTION
                DesktopProbe.YES -> OPENABLE
                null -> if (os == "MACOS" || os == "WINDOWS") OPENABLE else HIDDEN
            }
        }
    }
}

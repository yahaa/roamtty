package io.itermux.app.ui

/** The top-level pages of [ItermuxScreen]. */
internal enum class AppPage {
    /** The home page, with its sessions and hosts tabs. */
    HOME,
    /** One host's detail page. */
    HOST,
    TERMINAL,
    /** The SFTP browser of the selected session. */
    FILES,
    SETTINGS,
}

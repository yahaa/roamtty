package io.itermux.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.itermux.app.data.Host
import io.itermux.app.login.PendingPrompt
import io.itermux.app.session.CodingAgent
import io.itermux.app.session.TerminalConnection

/**
 * A launch sheet to open: on [host], for an agent or a terminal, with [agent] already picked when there is one.
 * [newSession]: the last step of a new session from the sessions tab, after what runs and the host were picked.
 */
internal data class LaunchRequest(val host: Host, val forAgent: Boolean, val agent: CodingAgent? = null, val newSession: Boolean = false)

/**
 * Where [ItermuxScreen] is and which of its sheets and dialogs are open. What names a page, a session or a host by id
 * survives the activity being recreated (turning the phone); what holds live objects or typed secrets does not.
 */
@Stable
internal class ScreenState(
    page: MutableState<AppPage>,
    settingsReturnPage: MutableState<AppPage>,
    selected: MutableState<String?>,
    detailHostId: MutableState<String?>,
    desktopHostId: MutableState<String?>,
    pipDesktopHostId: MutableState<String?>,
    desktopFromTerminal: MutableState<Boolean>,
    search: MutableState<String>,
    searchExpanded: MutableState<Boolean>,
    editorOpen: MutableState<Boolean>,
    editHostId: MutableState<String?>,
    continueOnId: MutableState<String?>,
    newSessionOpen: MutableState<Boolean>,
    newHostFor: MutableState<String?>,
    showAbout: MutableState<Boolean>,
) {
    var page by page
    /** The page the settings were opened from, which closing them returns to. */
    var settingsReturnPage by settingsReturnPage
    /** The session shown on [AppPage.TERMINAL] and [AppPage.FILES]. */
    var selected by selected
    /** The host whose detail page is shown on [AppPage.HOST]. */
    var detailHostId by detailHostId
    /** The host whose desktop is shown on [AppPage.DESKTOP]. */
    var desktopHostId by desktopHostId
    /** The host whose desktop shows in the small window over the terminal (while one of its sessions is shown). */
    var pipDesktopHostId by pipDesktopHostId
    /** Set when the desktop page was opened from the terminal's small window: leaving it goes back to that terminal. */
    var desktopFromTerminal by desktopFromTerminal
    var search by search
    var searchExpanded by searchExpanded
    var editorOpen by editorOpen
    /** The host the editor changes; null while it adds one. */
    var editHostId by editHostId
    /** The host of the 继续会话 sheet. */
    var continueOnId by continueOnId
    /** The home page's "new session" sheet, which picks the host before the launch sheet. */
    var newSessionOpen by newSessionOpen
    /** Set while the host editor fills in a new host for a new session: the agent it starts, or "" for a terminal. */
    var newHostFor by newHostFor
    var showAbout by showAbout

    var deleteHost by mutableStateOf<Host?>(null)
    /** A launch on a host: from its menu (the agent chosen on the launch sheet) or from 新建会话 (the agent picked first). */
    var launchRequest by mutableStateOf<LaunchRequest?>(null)
    /** A host just saved for a new session, waiting for the host list to carry it: its id, and the agent it starts. */
    var savedForSession by mutableStateOf<Pair<String, CodingAgent?>?>(null)
    /** A login waiting for a secret to be typed. */
    var connectRequest by mutableStateOf<ConnectRequest?>(null)
    var terminatePrompt by mutableStateOf<PendingPrompt?>(null)
    var reverify by mutableStateOf<TerminalConnection?>(null)
    var pluginTarget by mutableStateOf<PluginTarget?>(null)
    /** Session id → when it last reconnected by itself on open; see [AUTO_RECONNECT_INTERVAL_MS]. */
    val autoReconnectedAt = mutableMapOf<String, Long>()

    /** Shows session [id] on the terminal page, or on [on]. */
    fun showSession(id: String, on: AppPage = AppPage.TERMINAL) {
        selected = id
        page = on
    }

    fun showHost(id: String) {
        detailHostId = id
        page = AppPage.HOST
    }

    fun showDesktop(hostId: String) {
        desktopHostId = hostId
        page = AppPage.DESKTOP
    }

    /** The desktop page takes the screen from the terminal's small window; leaving it comes back to the terminal. */
    fun showDesktopFromSmallWindow(hostId: String) {
        desktopHostId = hostId
        desktopFromTerminal = true
        page = AppPage.DESKTOP
    }

    /** Opens the host editor on host [id], or on a new host for null. */
    fun editHost(id: String?) {
        editHostId = id
        editorOpen = true
    }

    fun closeEditor() {
        editorOpen = false
        editHostId = null
    }

    /** Settings is a drawer destination: closing returns to the page it was opened from. */
    fun openSettings() {
        if (page != AppPage.SETTINGS) settingsReturnPage = page
        page = AppPage.SETTINGS
    }

    fun closeSettings() { page = settingsReturnPage }
}

@Composable
internal fun rememberScreenState(): ScreenState {
    val page = rememberSaveable { mutableStateOf(AppPage.HOME) }
    val settingsReturnPage = rememberSaveable { mutableStateOf(AppPage.HOME) }
    val selected = rememberSaveable { mutableStateOf<String?>(null) }
    val detailHostId = rememberSaveable { mutableStateOf<String?>(null) }
    val desktopHostId = rememberSaveable { mutableStateOf<String?>(null) }
    val pipDesktopHostId = rememberSaveable { mutableStateOf<String?>(null) }
    val desktopFromTerminal = rememberSaveable { mutableStateOf(false) }
    val search = rememberSaveable { mutableStateOf("") }
    val searchExpanded = rememberSaveable { mutableStateOf(false) }
    val editorOpen = rememberSaveable { mutableStateOf(false) }
    val editHostId = rememberSaveable { mutableStateOf<String?>(null) }
    val continueOnId = rememberSaveable { mutableStateOf<String?>(null) }
    val newSessionOpen = rememberSaveable { mutableStateOf(false) }
    val newHostFor = rememberSaveable { mutableStateOf<String?>(null) }
    val showAbout = rememberSaveable { mutableStateOf(false) }
    return remember {
        ScreenState(page, settingsReturnPage, selected, detailHostId, desktopHostId, pipDesktopHostId, desktopFromTerminal,
            search, searchExpanded, editorOpen, editHostId, continueOnId, newSessionOpen, newHostFor, showAbout)
    }
}

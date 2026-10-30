package io.itermux.app.session

import io.itermux.app.tr
import io.itermux.app.trf

import android.app.*
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.itermux.app.*
import io.itermux.app.data.Host
import io.itermux.app.data.installedPlugins
import io.itermux.app.data.prefersMosh
import io.itermux.app.desktop.DesktopConnection
import io.itermux.app.desktop.DesktopRegistry
import io.itermux.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class SessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableSessions = MutableStateFlow<List<TerminalConnection>>(emptyList())
    val sessions = mutableSessions.asStateFlow()
    val pendingSharedImages = MutableStateFlow<List<android.net.Uri>>(emptyList())
    private val binder = LocalBinder()
    private var foreground = false
    private lateinit var journal: SessionJournal
    private lateinit var restoration: Job
    private lateinit var wakeLock: PowerManager.WakeLock
    private val stateWatchers = mutableMapOf<String, Job>()
    /** The agent notification each session shows now, by session id; see [AgentAlerts]. */
    private val alerts = mutableMapOf<String, AgentAlerts.Kind>()
    /** The session the app's terminal page shows, which needs no notification while the app is in sight. */
    private var viewing: String? = null
    private var appVisible = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val app get() = application as ItermuxApp
    /** Remote desktops, one per host; see [DesktopRegistry]. */
    internal val desktops by lazy { DesktopRegistry(app, scope) { scope.launch { watchDesktops() } } }
    private var desktopWatcher: Job? = null
    inner class LocalBinder : Binder() { val service get() = this@SessionService }
    override fun onBind(intent: Intent?) = binder
    override fun onCreate() {
        super.onCreate()
        journal = SessionJournal(this)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Roamtty:SSH").apply { setReferenceCounted(false) }
        refreshLanguage()
        watchNetwork()
        // A session that goes takes its notification with it.
        scope.launch {
            sessions.collect { list -> (alerts.keys - list.map { it.id }.toSet()).forEach(::withdrawAlert) }
        }
        app.appSettings().registerOnSharedPreferenceChangeListener(settingListener)
        restoration = scope.launch {
            val usedNumbers = mutableMapOf<String, MutableSet<Int>>()
            mutableSessions.value = journal.read().mapNotNull { entry ->
                app.database.hosts().find(entry.hostId)?.let { host ->
                    val used = usedNumbers.getOrPut(host.id) { mutableSetOf() }
                    val number = entry.sessionNumber.takeIf { it > 0 && it !in used } ?: nextSessionNumber(used)
                    used.add(number)
                    TerminalConnection(
                        host, entry.mode, null, app.pins, scope, entry.id, entry.title,
                        sessionNumber = number,
                        remoteSessionName = entry.remoteSessionName,
                        external = entry.external,
                        onRename = ::saveSessions,
                        lastAgentStatus = entry.agentStatus,
                        onAgentReport = ::saveSessions
                    )
                }
            }
            saveSessions()
        }
    }
    fun refreshLanguage() {
        getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
            NotificationChannel(CHANNEL, tr("SSH 会话"), NotificationManager.IMPORTANCE_LOW).apply { description = tr("保持主动打开的 SSH 连接") },
            NotificationChannel(WAITING_CHANNEL, tr("Agent 等你确认"), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = tr("Agent 停下来请求权限或提问时提醒") },
            // A finished turn waits in the shade without a sound; the user can raise it in the system settings.
            NotificationChannel(DONE_CHANNEL, tr("Agent 已完成"), NotificationManager.IMPORTANCE_LOW)
                .apply { description = tr("Agent 做完一轮（运行超过 1 分钟）时提醒") },
        ))
        if (foreground) refreshNotification()
    }
    suspend fun awaitRestoration() { restoration.join() }
    private fun saveSessions() = journal.save(mutableSessions.value)
    /**
     * Opens a new local entry. TMUX mode allocates a fresh remote session name, unless [attach] names a tmux session
     * already on the server to attach as it is.
     */
    suspend fun open(host: Host, mode: SessionMode, credentials: Credentials, preferMosh: Boolean = host.prefersMosh,
        attach: String? = null): TerminalConnection {
        awaitRestoration()
        check(mutableSessions.value.count { it.host.id == host.id } < MAX_SESSIONS_PER_HOST) {
            trf("每台主机最多保留 %d 个会话，请先关闭这台主机的旧会话", MAX_SESSIONS_PER_HOST)
        }
        val connection = TerminalConnection(
            host, if (attach != null) SessionMode.TMUX else mode, credentials, app.pins, scope,
            initialTitle = attach.orEmpty(),
            sessionNumber = nextSessionNumber(mutableSessions.value.filter { it.host.id == host.id }.map { it.sessionNumber }),
            remoteSessionName = attach ?: if (mode == SessionMode.TMUX) Tmux.newSessionName() else Tmux.LEGACY_SESSION,
            external = attach != null,
            onRename = ::saveSessions,
            onHostSystemDetected = { updateHostSystem(host, it) },
            preferMosh = preferMosh,
            onPluginsDetected = { updatePlugins(host, it) },
            onDesktopDetected = { updateDesktop(host, it) },
            onAgentReport = ::saveSessions
        )
        mutableSessions.value += connection
        track(connection)
        return connection
    }

    private suspend fun updateHostSystem(host: Host, system: HostSystem) {
        app.database.hosts().updateSystem(
            host.id, host.address, host.port, host.username, system.name)
    }

    private suspend fun updatePlugins(host: Host, plugins: Set<HostPlugin>) {
        app.database.hosts().updatePlugins(
            host.id, host.address, host.port, host.username, HostPlugin.encode(plugins))
    }

    internal suspend fun updateDesktop(host: Host, desktop: HostDesktop) {
        app.database.hosts().updateDesktop(host.id, host.address, host.port, host.username, desktop.desktop, System.currentTimeMillis())
    }

    /** Opens (or returns) [host]'s desktop and keeps the service in the foreground while it lives. */
    internal fun openDesktop(host: Host, password: String, passphrase: String, width: Int, height: Int, dpi: Int): DesktopConnection {
        val connection = desktops.open(host, password, passphrase, width, height, dpi)
        ContextCompat.startForegroundService(this, Intent(this, SessionService::class.java))
        watchDesktops()
        return connection
    }

    private fun watchDesktops() {
        desktopWatcher?.cancel()
        desktopWatcher = scope.launch {
            val all = desktops.connections.value.values.toList()
            if (all.isEmpty()) { refreshNotification(); return@launch }
            kotlinx.coroutines.flow.merge(*all.map { it.phase }.toTypedArray()).collect { refreshNotification() }
        }
    }

    /** Keeps the foreground notification and wake lock in step with an active connection. */
    private fun track(connection: TerminalConnection) {
        ContextCompat.startForegroundService(this, Intent(this, SessionService::class.java))
        saveSessions()
        stateWatchers[connection.id] = scope.launch {
            launch { watchAgent(connection) }
            connection.state.collect { refreshNotification() }
        }
    }

    /** Notifies what [connection]'s agent reports from now on; the report it starts with was told of already. */
    private suspend fun watchAgent(connection: TerminalConnection) {
        var previous: AgentStatus? = null
        var first = true
        connection.lastAgentStatus.collect { current ->
            if (!first) when (val change = AgentAlerts.next(previous, current, alerts[connection.id])) {
                is AgentAlerts.Change.Show -> if (current != null) showAlert(connection, change.kind, current)
                AgentAlerts.Change.Withdraw -> withdrawAlert(connection.id)
                AgentAlerts.Change.Keep -> Unit
            }
            first = false
            previous = current
        }
    }

    /** Tells the user [connection]'s agent waits or finished; tapping it opens that session. */
    private fun showAlert(connection: TerminalConnection, kind: AgentAlerts.Kind, status: AgentStatus) {
        if (appVisible && connection.id == viewing) return
        // One the user turned off still replaces what the session showed, which is out of date now.
        if (!AgentAlerts.setting(this).allows(kind)) { withdrawAlert(connection.id); return }
        val title = AgentAlerts.title(kind, CodingAgent.fromCommand(status.agent) ?: connection.launchedAgent.value)
        val channel = if (kind == AgentAlerts.Kind.WAITING) WAITING_CHANNEL else DONE_CHANNEL
        val open = PendingIntent.getActivity(this, connection.id.hashCode(),
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SESSION, connection.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // The lock screen names the agent and the host, not the command or the answer.
        val public = NotificationCompat.Builder(this, channel).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(connection.host.name).build()
        val notification = NotificationCompat.Builder(this, channel).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(AgentAlerts.text(kind, connection.host.name, status))
            .setContentIntent(open).setAutoCancel(true).setShowWhen(true)
            .setPriority(if (kind == AgentAlerts.Kind.WAITING) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            // A group of its own: without one, the system folds it into one bundle with the ongoing notification.
            .setGroup("$ALERT_GROUP${connection.id}")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public).build()
        getSystemService(NotificationManager::class.java).notify(connection.id, ALERT, notification)
        alerts[connection.id] = kind
    }

    /** Also cancels one an earlier run of the service left: sessions keep their ids in the journal. */
    private fun withdrawAlert(id: String) {
        alerts.remove(id)
        getSystemService(NotificationManager::class.java).cancel(id, ALERT)
    }

    /** Turning a kind of notification off in the settings takes the ones shown away. */
    private val settingListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != AgentAlerts.SETTING_KEY) return@OnSharedPreferenceChangeListener
        val setting = AgentAlerts.setting(this)
        alerts.filterValues { !setting.allows(it) }.keys.forEach(::withdrawAlert)
    }

    /** The session the app's terminal page shows, null for none: in sight, its notification goes and it gets none. */
    fun setViewing(id: String?) {
        viewing = id
        if (appVisible) id?.let(::withdrawAlert)
    }

    /** Whether the app is on screen; told by the activity, as the UI does not recompose once it is stopped. */
    fun setAppVisible(visible: Boolean) {
        appVisible = visible
        if (visible) viewing?.let(::withdrawAlert)
    }

    /**
     * Reconnects an existing journal entry, reattaching the same remote tmux session name.
     * Replaces the local transport object in place so the session list identity is preserved.
     */
    suspend fun reconnect(id: String, credentials: Credentials): TerminalConnection {
        awaitRestoration()
        val existing = mutableSessions.value.find { it.id == id }
            ?: error(tr("会话不存在"))
        stateWatchers.remove(id)?.cancel()
        existing.disconnect()
        // Plugins may have been installed since this entry was created; keep the entry's mode.
        val current = app.database.hosts().find(existing.host.id) ?: existing.host
        // Callbacks capture the host, never `existing`: each replacement would otherwise keep the connection it
        // replaced (and its terminal buffer) alive, chaining every reconnect of the session.
        val host = existing.host
        val replacement = TerminalConnection(
            host, existing.mode, credentials, app.pins, scope,
            id = existing.id,
            initialTitle = existing.title.value,
            sessionNumber = existing.sessionNumber,
            remoteSessionName = existing.remoteSessionName,
            external = existing.external,
            onRename = ::saveSessions,
            onHostSystemDetected = { updateHostSystem(host, it) },
            preferMosh = current.prefersMosh,
            onPluginsDetected = { updatePlugins(host, it) },
            onDesktopDetected = { updateDesktop(host, it) },
            lastAgentStatus = existing.lastAgentStatus.value,
            onAgentReport = ::saveSessions
        )
        mutableSessions.value = mutableSessions.value.map { if (it.id == id) replacement else it }
        track(replacement)
        return replacement
    }

    /**
     * Drops the local connection without ending a remote tmux session. Use when deleting a host or clearing an
     * endpoint's trust: the dialog promises remote work keeps running. [forgetCard] removes the session list entry;
     * otherwise a tmux card stays so the user can reconnect.
     */
    fun disconnectLocal(id: String, forgetCard: Boolean = true) {
        val existing = mutableSessions.value.find { it.id == id } ?: return
        stateWatchers.remove(id)?.cancel()
        existing.disconnect()
        if (forgetCard || existing.external || existing.mode != SessionMode.TMUX) {
            mutableSessions.value = mutableSessions.value.filterNot { it.id == id }
        }
        saveSessions()
        refreshNotification()
    }

    /**
     * Ends a session outright: a tmux session is killed on the server first, with everything running in it, and the
     * card goes. A tmux session that cannot be reached keeps its card so it can still be ended later. A tmux session
     * that was already on the server is only detached: its card goes and it keeps running.
     */
    fun close(id: String) {
        val existing = mutableSessions.value.find { it.id == id } ?: return
        stateWatchers.remove(id)?.cancel()
        if (existing.external) {
            existing.disconnect()
            mutableSessions.value = mutableSessions.value.filterNot { it.id == id }
        } else if (existing.mode == SessionMode.TMUX && existing.state.value.phase == Phase.CONNECTED) {
            mutableSessions.value = mutableSessions.value.filterNot { it.id == id }
            scope.launch {
                if (!existing.killRemoteSession()) android.util.Log.w("RoamttyConn", "close $id: remote tmux not ended")
                existing.disconnect()
            }
        } else {
            existing.disconnect()
            if (existing.mode != SessionMode.TMUX) mutableSessions.value = mutableSessions.value.filterNot { it.id == id }
        }
        saveSessions()
        refreshNotification()
    }
    fun forget(id: String) {
        val existing = mutableSessions.value.find { it.id == id } ?: return
        check(!existing.state.value.active)
        stateWatchers.remove(id)?.cancel()
        // A transport that ended by itself still has service-scoped metadata/history observers.
        existing.disconnect()
        mutableSessions.value = mutableSessions.value.filterNot { it.id == id }
        saveSessions()
    }
    /**
     * Ends the remote tmux session of a closed entry over a separate login, then forgets the entry.
     * Returns null on success, else why the session may still be running (the entry is kept).
     */
    suspend fun terminate(id: String, credentials: Credentials, interaction: suspend (Prompt) -> List<String>?): String? {
        val existing = mutableSessions.value.find { it.id == id } ?: return null
        check(!existing.state.value.active)
        return endRemoteSession(existing, credentials, interaction).also { if (it == null) forget(id) }
    }
    /**
     * Force delete: the entry goes at once, whatever the host does. Its tmux session is then ended over a login of its
     * own that answers no question, so returns null once it ended, else why it may still be running.
     */
    suspend fun forceDelete(id: String, credentials: Credentials): String? {
        val existing = mutableSessions.value.find { it.id == id } ?: return null
        forget(id)
        if (existing.mode != SessionMode.TMUX || existing.external) return null
        return endRemoteSession(existing, credentials) { null }
    }
    /** Ends [existing]'s remote tmux session over a separate login: null once it ended, else why it may still run. */
    private suspend fun endRemoteSession(existing: TerminalConnection, credentials: Credentials, interaction: suspend (Prompt) -> List<String>?): String? {
        val ssh = JschSession(existing.host.target(SessionMode.SHELL), credentials, app.pins,
            interaction = interaction, connectTimeoutMs = 15_000)
        var status: Int? = null
        try {
            ssh.administer { remote ->
                status = remote.run(Tmux.killSessionScript(existing.remoteSessionName), 15_000) {}
                // A native Windows shell cannot run the POSIX script; its sessions live in psmux.
                if (status != 0 && existing.host.installedPlugins?.contains(HostPlugin.PSMUX) == true)
                    status = remote.runCommand(Psmux.killSessionCommand(existing.remoteSessionName), 15_000) {}
            }
        } finally { ssh.disconnect() }
        return when (status) {
            0 -> null
            null -> ssh.state.value.message.takeIf { it.isNotBlank() }?.let(::tr) ?: tr("无法连接服务器")
            else -> trf("tmux 退出码 %d", status!!)
        }
    }
    /** Sockets bound to a lost network or frozen by a long sleep can look connected for minutes. */
    fun verifyConnections() {
        android.util.Log.i("RoamttyConn", "verify ${mutableSessions.value.size} sessions")
        mutableSessions.value.forEach { it.verifyAlive() }
    }

    private fun watchNetwork() {
        var current: Network? = null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // The first callback reports the network the sessions already use.
                val changed = current != null && current != network
                android.util.Log.i("RoamttyConn", "default network $network available, changed=$changed")
                current = network
                if (changed) scope.launch { verifyConnections(); desktops.networkChanged() }
            }
            // No replacement network (airplane mode, Wi-Fi gone without mobile data) sends no onAvailable.
            override fun onLost(network: Network) { android.util.Log.i("RoamttyConn", "default network $network lost"); scope.launch { verifyConnections() } }
        }
        networkCallback = runCatching {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(callback)
            callback
        }.getOrNull()
    }

    fun disconnectAll() { mutableSessions.value.forEach { it.disconnect() }; desktops.closeAll(); refreshNotification() }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, NOTIFICATION, notification(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        foreground = true
        refreshNotification()
        return START_NOT_STICKY
    }
    private fun notification(): Notification {
        val live = desktops.live
        // One live desktop: tapping goes back to it. Several: the app opens on its list, where each has its button.
        val desktopHost = live.singleOrNull()?.host?.id
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).apply {
            if (desktopHost != null) putExtra(MainActivity.EXTRA_DESKTOP_HOST, desktopHost)
        }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val active = sessions.value.count { it.state.value.active }
        // Only the count: what needs the user comes as notifications of its own. Live desktops get the line, by name.
        val text = when (live.size) {
            0 -> null
            1 -> trf("%s 桌面 · 已连接", live[0].host.name)
            else -> trf("%d 个桌面已连接 · %s", live.size, live.joinToString("、") { it.host.name })
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(trf("%d 个会话", active))
            .setContentText(text)
            .setOngoing(true).setSilent(true).setContentIntent(launch)
            // Kept apart from the agent notifications; see showAlert.
            .setGroup(ONGOING_GROUP)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
    }
    // This is a user-controlled SSH session, not a fixed-duration job. A timer would silently
    // break long connections again. Every inactive state, explicit disconnect and onDestroy release it.
    @SuppressLint("WakelockTimeout")
    private fun refreshNotification() {
        val active = sessions.value.any { it.state.value.active } || desktops.live.isNotEmpty()
        // A foreground notification alone does not keep the CPU running with the screen off.
        if (active && !wakeLock.isHeld) wakeLock.acquire()
        if (!active && wakeLock.isHeld) wakeLock.release()
        if (!active) {
            if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false }
            stopSelf()
        } else if (foreground) getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
    }
    override fun onDestroy() {
        networkCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        mutableSessions.value.forEach { it.disconnect() }
        desktops.closeAll()
        app.appSettings().unregisterOnSharedPreferenceChangeListener(settingListener)
        scope.cancel()
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }
    companion object {
        const val MAX_SESSIONS_PER_HOST = 8
        private const val CHANNEL = "ssh-sessions"
        private const val NOTIFICATION = 20
        /** Agent notifications share this id; each is tagged with its session's id. */
        private const val ALERT = 21
        private const val ALERT_GROUP = "agent-alert-"
        private const val ONGOING_GROUP = "ongoing"
        private const val WAITING_CHANNEL = "agent-waiting"
        private const val DONE_CHANNEL = "agent-done"
    }
}

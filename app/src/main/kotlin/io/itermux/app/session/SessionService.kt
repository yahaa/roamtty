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
import io.itermux.app.data.prefersMosh
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
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val agentAlerts = AgentAlerts()
    /** One event follower per host, on any of its connected sessions. */
    private val agentWatchers = mutableMapOf<String, Job>()
    private var appVisible = false
    private var screenSession: String? = null
    private val lastViewed = mutableMapOf<String, Long>()
    /** Reminders waiting out [AGENT_SHOW_DELAY_MS]; a resolved event in time means none is shown. */
    private val pendingAlerts = mutableMapOf<String, Job>()
    inner class LocalBinder : Binder() { val service get() = this@SessionService }
    override fun onBind(intent: Intent?) = binder
    override fun onCreate() {
        super.onCreate()
        journal = SessionJournal(this)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Roamtty:SSH").apply { setReferenceCounted(false) }
        refreshLanguage()
        watchNetwork()
        restoration = scope.launch {
            val app = application as ItermuxApp
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
                        onRename = ::saveSessions
                    )
                }
            }
            saveSessions()
        }
    }
    fun refreshLanguage() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, tr("SSH 会话"), NotificationManager.IMPORTANCE_LOW).apply { description = tr("保持主动打开的 SSH 连接") }
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(AGENT_CHANNEL, tr("AI 交互提醒"), NotificationManager.IMPORTANCE_HIGH).apply {
                description = tr("Claude Code、Codex、Grok 请求权限或向你提问时提醒")
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
        if (foreground) refreshNotification()
    }

    /** The activity is visible; reminders for the session on screen are then not needed. */
    fun setAppVisible(visible: Boolean) {
        appVisible = visible
        screenSession?.let { if (visible) viewed(it) }
    }

    /** The terminal page shows [sessionId], or no session when null. */
    fun setScreenSession(sessionId: String?) {
        screenSession = sessionId
        if (sessionId != null && appVisible) viewed(sessionId)
    }

    private fun viewed(sessionId: String) {
        lastViewed[sessionId] = SystemClock.elapsedRealtime()
        agentAlerts.onViewed(sessionId)?.let(::apply)
    }

    /** Plugins were installed or removed on [hostId]: its connected sessions probe again. */
    fun refreshPlugins(hostId: String) {
        mutableSessions.value.filter { it.host.id == hostId && it.state.value.phase == Phase.CONNECTED }
            .forEach { scope.launch { it.reprobePlugins() } }
    }

    /** Re-reads the "AI 交互提醒" setting: starts or stops following events and withdraws reminders. */
    fun refreshAgentAlerts() {
        if (!AgentAlertSettings.enabled(this)) mutableSessions.value.forEach { agentAlerts.forget(it.id)?.let(::apply) }
        reconcileAgentWatchers()
    }

    private fun reconcileAgentWatchers() {
        val hosts = if (!AgentAlertSettings.enabled(this)) emptySet()
            else mutableSessions.value.filter { it.followsAgentEvents }.mapTo(mutableSetOf()) { it.host.id }
        agentWatchers.keys.filter { it !in hosts }.forEach { agentWatchers.remove(it)?.cancel() }
        hosts.filter { it !in agentWatchers }.forEach { hostId -> agentWatchers[hostId] = scope.launch { followAgentEvents(hostId) } }
    }

    private suspend fun followAgentEvents(hostId: String) {
        try {
            while (true) {
                val connection = mutableSessions.value.firstOrNull { it.host.id == hostId && it.followsAgentEvents } ?: break
                connection.followAgentEvents { line -> AgentEvent.parse(line)?.let { onAgentEvent(hostId, it) } }
                // The SSH side dropped (mosh keeps the terminal) or the command ended; try again shortly.
                delay(AGENT_RETRY_MS)
            }
        } finally {
            if (agentWatchers[hostId] === currentCoroutineContext()[Job]) agentWatchers.remove(hostId)
        }
    }

    private fun onAgentEvent(hostId: String, event: AgentEvent) {
        val sessions = mutableSessions.value.filter { it.host.id == hostId }
        // Live sessions first, the most recently viewed one leading; detached tmux entries can still own a reminder.
        val candidates = sessions.sortedWith(compareByDescending<TerminalConnection> { it.state.value.active }
            .thenByDescending { lastViewed[it.id] ?: 0L })
            .map { AgentAlerts.Candidate(it.id, it.tmuxName) }
        agentAlerts.onEvent(hostId, event, candidates, screenSession.takeIf { appVisible })?.let(::apply)
    }

    private fun apply(action: AgentAlerts.Action) {
        val notifications = getSystemService(NotificationManager::class.java)
        pendingAlerts.remove(action.sessionId)?.cancel()
        when (action) {
            is AgentAlerts.Action.Cancel -> notifications.cancel(action.sessionId, AGENT_NOTIFICATION)
            // CLIs sometimes ask and settle at once (auto-approved requests); those should not buzz.
            is AgentAlerts.Action.Show -> pendingAlerts[action.sessionId] = scope.launch {
                delay(AGENT_SHOW_DELAY_MS)
                pendingAlerts.remove(action.sessionId)
                val connection = mutableSessions.value.find { it.id == action.sessionId } ?: return@launch
                notifications.notify(action.sessionId, AGENT_NOTIFICATION, agentNotification(connection, action.event, action.alert))
            }
        }
    }

    private fun agentNotification(connection: TerminalConnection, event: AgentEvent, alert: Boolean): Notification {
        val open = PendingIntent.getActivity(this, connection.id.hashCode(),
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SESSION_ID, connection.id)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val agent = CodingAgent.entries.firstOrNull { it.command == event.agent }?.shortLabel ?: event.agent.replaceFirstChar { it.uppercase() }
        val question = event.reason == "question"
        val text = when {
            event.msg.isBlank() -> trf(if (question) "%s 在等你回答" else "%s 请求权限", agent)
            else -> trf(if (question) "%s 在等你回答：%s" else "%s 请求权限：%s", agent, event.msg)
        }
        // The lock screen shows that an agent waits, never what it asks.
        val public = NotificationCompat.Builder(this, AGENT_CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Roamtty").setContentText(tr("AI 助手在等你操作")).build()
        return NotificationCompat.Builder(this, AGENT_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(connection.displayTitle)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setSilent(!alert)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
    }
    suspend fun awaitRestoration() { restoration.join() }
    private fun saveSessions() = journal.save(mutableSessions.value)
    /** Opens a new local entry. TMUX mode always allocates a fresh remote session name. */
    suspend fun open(host: Host, mode: SessionMode, credentials: Credentials, preferMosh: Boolean = host.prefersMosh): TerminalConnection {
        awaitRestoration()
        check(mutableSessions.value.count { it.host.id == host.id } < MAX_SESSIONS_PER_HOST) {
            trf("每台主机最多保留 %d 个会话，请先关闭这台主机的旧会话", MAX_SESSIONS_PER_HOST)
        }
        val connection = TerminalConnection(
            host, mode, credentials, (application as ItermuxApp).pins, scope,
            sessionNumber = nextSessionNumber(mutableSessions.value.filter { it.host.id == host.id }.map { it.sessionNumber }),
            onRename = ::saveSessions,
            onHostSystemDetected = { updateHostSystem(host, it) },
            preferMosh = preferMosh,
            onPluginsDetected = { updatePlugins(host, it) }
        )
        mutableSessions.value += connection
        track(connection)
        return connection
    }

    private suspend fun updateHostSystem(host: Host, system: HostSystem) {
        (application as ItermuxApp).database.hosts().updateSystem(
            host.id, host.address, host.port, host.username, system.name)
    }

    private suspend fun updatePlugins(host: Host, plugins: Set<HostPlugin>) {
        (application as ItermuxApp).database.hosts().updatePlugins(
            host.id, host.address, host.port, host.username, HostPlugin.encode(plugins))
    }

    /** Keeps the foreground notification and wake lock in step with an active connection. */
    private fun track(connection: TerminalConnection) {
        ContextCompat.startForegroundService(this, Intent(this, SessionService::class.java))
        saveSessions()
        stateWatchers[connection.id] = scope.launch {
            launch { connection.detectedPlugins.collect { reconcileAgentWatchers() } }
            connection.state.collect { refreshNotification(); reconcileAgentWatchers() }
        }
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
        val current = (application as ItermuxApp).database.hosts().find(existing.host.id) ?: existing.host
        // Callbacks capture the host, never `existing`: each replacement would otherwise keep the connection it
        // replaced (and its terminal buffer) alive, chaining every reconnect of the session.
        val host = existing.host
        val replacement = TerminalConnection(
            host, existing.mode, credentials, (application as ItermuxApp).pins, scope,
            id = existing.id,
            initialTitle = existing.title.value,
            sessionNumber = existing.sessionNumber,
            remoteSessionName = existing.remoteSessionName,
            onRename = ::saveSessions,
            onHostSystemDetected = { updateHostSystem(host, it) },
            preferMosh = current.prefersMosh,
            onPluginsDetected = { updatePlugins(host, it) }
        )
        mutableSessions.value = mutableSessions.value.map { if (it.id == id) replacement else it }
        track(replacement)
        return replacement
    }

    fun close(id: String) {
        stateWatchers.remove(id)?.cancel()
        val existing = mutableSessions.value.find { it.id == id } ?: return
        existing.disconnect()
        agentAlerts.forget(id)?.let(::apply)
        reconcileAgentWatchers()
        // Detach preserves the remote task. Keep its addressable identity for reconnect.
        if (existing.mode != SessionMode.TMUX)
            mutableSessions.value = mutableSessions.value.filterNot { it.id == id }
        saveSessions()
        refreshNotification()
    }
    fun forget(id: String) {
        val existing = mutableSessions.value.find { it.id == id } ?: return
        check(!existing.state.value.active)
        stateWatchers.remove(id)?.cancel()
        mutableSessions.value = mutableSessions.value.filterNot { it.id == id }
        agentAlerts.forget(id)?.let(::apply)
        lastViewed.remove(id)
        saveSessions()
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
                if (changed) scope.launch { verifyConnections() }
            }
            // No replacement network (airplane mode, Wi-Fi gone without mobile data) sends no onAvailable.
            override fun onLost(network: Network) { android.util.Log.i("RoamttyConn", "default network $network lost"); scope.launch { verifyConnections() } }
        }
        networkCallback = runCatching {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(callback)
            callback
        }.getOrNull()
    }

    fun disconnectAll() { mutableSessions.value.forEach { it.disconnect() }; refreshNotification(); reconcileAgentWatchers() }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == DISCONNECT_ALL) { disconnectAll(); stopSelf(); return START_NOT_STICKY }
        ServiceCompat.startForeground(this, NOTIFICATION, notification(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        foreground = true
        refreshNotification()
        return START_NOT_STICKY
    }
    private fun notification(): Notification {
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val close = PendingIntent.getService(this, 1, Intent(this, SessionService::class.java).setAction(DISCONNECT_ALL), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val active = sessions.value.count { it.state.value.active }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(trf("Roamtty · %d 个 SSH 会话", active))
            .setContentText(tr("轻触返回终端 · 长任务建议使用 tmux"))
            .setOngoing(true).setSilent(true).setContentIntent(launch)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, tr("全部断开"), close).build()
    }
    // This is a user-controlled SSH session, not a fixed-duration job. A timer would silently
    // break long connections again. Every inactive state, explicit disconnect and onDestroy release it.
    @SuppressLint("WakelockTimeout")
    private fun refreshNotification() {
        val active = sessions.value.any { it.state.value.active }
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
        scope.cancel()
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }
    companion object {
        const val MAX_SESSIONS_PER_HOST = 8
        private const val CHANNEL = "ssh-sessions"
        private const val NOTIFICATION = 20
        private const val DISCONNECT_ALL = "io.itermux.DISCONNECT_ALL"
        private const val AGENT_CHANNEL = "agent-attention"
        /** Tagged with the session id: one reminder per session. */
        private const val AGENT_NOTIFICATION = 21
        private const val AGENT_RETRY_MS = 3_000L
        private const val AGENT_SHOW_DELAY_MS = 1_500L
    }
}

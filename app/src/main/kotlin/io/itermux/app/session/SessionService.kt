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
        if (foreground) refreshNotification()
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
        stateWatchers[connection.id] = scope.launch { connection.state.collect { refreshNotification() } }
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
        val replacement = TerminalConnection(
            existing.host, existing.mode, credentials, (application as ItermuxApp).pins, scope,
            id = existing.id,
            initialTitle = existing.title.value,
            sessionNumber = existing.sessionNumber,
            remoteSessionName = existing.remoteSessionName,
            onRename = ::saveSessions,
            onHostSystemDetected = { updateHostSystem(existing.host, it) },
            preferMosh = current.prefersMosh,
            onPluginsDetected = { updatePlugins(existing.host, it) }
        )
        mutableSessions.value = mutableSessions.value.map { if (it.id == id) replacement else it }
        track(replacement)
        return replacement
    }

    fun close(id: String) {
        stateWatchers.remove(id)?.cancel()
        val existing = mutableSessions.value.find { it.id == id } ?: return
        existing.disconnect()
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
        saveSessions()
    }
    /** Sockets bound to a lost network or frozen by a long sleep can look connected for minutes. */
    fun verifyConnections() = mutableSessions.value.forEach { it.verifyAlive() }

    private fun watchNetwork() {
        var current: Network? = null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // The first callback reports the network the sessions already use.
                val changed = current != null && current != network
                current = network
                if (changed) scope.launch { verifyConnections() }
            }
        }
        networkCallback = runCatching {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(callback)
            callback
        }.getOrNull()
    }

    fun disconnectAll() { mutableSessions.value.forEach { it.disconnect() }; refreshNotification() }
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
    }
}

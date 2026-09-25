package io.itermux.app.session

import io.itermux.app.tr
import io.itermux.app.trf

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.itermux.app.*
import io.itermux.app.data.Host
import io.itermux.ssh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class SessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableSessions = MutableStateFlow<List<TerminalConnection>>(emptyList())
    val sessions = mutableSessions.asStateFlow()
    private val binder = LocalBinder()
    private var foreground = false
    private val stateWatchers = mutableMapOf<String, Job>()
    inner class LocalBinder : Binder() { val service get() = this@SessionService }
    override fun onBind(intent: Intent?) = binder
    override fun onCreate() {
        super.onCreate()
        refreshLanguage()
    }
    fun refreshLanguage() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, tr("SSH 会话"), NotificationManager.IMPORTANCE_LOW).apply { description = tr("保持主动打开的 SSH 连接") }
        )
        if (foreground) refreshNotification()
    }
    fun open(host: Host, mode: SessionMode, credentials: Credentials): TerminalConnection {
        check(mutableSessions.value.size < 8) { tr("最多保留 8 个会话，请先关闭旧会话") }
        ContextCompat.startForegroundService(this, Intent(this, SessionService::class.java))
        val connection = TerminalConnection(host, mode, credentials, (application as ItermuxApp).pins, scope)
        mutableSessions.value += connection
        stateWatchers[connection.id] = scope.launch { connection.state.collect { refreshNotification() } }
        return connection
    }
    fun close(id: String) {
        stateWatchers.remove(id)?.cancel()
        mutableSessions.value.find { it.id == id }?.disconnect()
        mutableSessions.value = mutableSessions.value.filterNot { it.id == id }
        refreshNotification()
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
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(trf("iTermux · %d 个 SSH 会话", active))
            .setContentText(tr("轻触返回终端 · 长任务建议使用 tmux"))
            .setOngoing(true).setSilent(true).setContentIntent(launch)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, tr("全部断开"), close).build()
    }
    private fun refreshNotification() {
        if (sessions.value.none { it.state.value.active }) {
            if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false }
            stopSelf()
        } else if (foreground) getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
    }
    override fun onDestroy() { mutableSessions.value.forEach { it.disconnect() }; scope.cancel(); super.onDestroy() }
    companion object {
        private const val CHANNEL = "ssh-sessions"
        private const val NOTIFICATION = 20
        private const val DISCONNECT_ALL = "io.itermux.DISCONNECT_ALL"
    }
}

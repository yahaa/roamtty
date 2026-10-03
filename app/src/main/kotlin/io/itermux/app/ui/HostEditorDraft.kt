package io.itermux.app.ui

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.itermux.app.ItermuxApp
import io.itermux.app.data.Host
import io.itermux.app.session.PendingPrompt
import io.itermux.app.tr
import io.itermux.ssh.*
import kotlinx.coroutines.*
import java.util.UUID

internal class HostEditorDraft : ViewModel() {
    private var active = false
    private var sourceId: String? = null
    var id = ""
        private set
    val name = mutableStateOf("")
    val address = mutableStateOf("")
    val port = mutableStateOf("22")
    val username = mutableStateOf("")
    val auth = mutableStateOf("PASSWORD")
    val mode = mutableStateOf("TMUX")
    val os = mutableStateOf("UNKNOWN")
    val savePassword = mutableStateOf(false)
    val password = mutableStateOf("")
    val passphrase = mutableStateOf("")
    val imported = mutableStateOf<ByteArray?>(null)
    val keyLabel = mutableStateOf<String?>(null)
    val busy = mutableStateOf(false)
    val detecting = mutableStateOf(false)
    val saved = mutableStateOf(false)
    val error = mutableStateOf<String?>(null)
    val pending = mutableStateOf<PendingPrompt?>(null)
    private var detector: JschSession? = null
    private var savingJob: Job? = null

    fun open(original: Host?) {
        if (active && sourceId == original?.id) return
        close()
        sourceId = original?.id
        id = original?.id ?: UUID.randomUUID().toString()
        name.value = original?.name.orEmpty()
        address.value = original?.address.orEmpty()
        port.value = original?.port?.toString() ?: "22"
        username.value = original?.username.orEmpty()
        auth.value = original?.auth ?: "PASSWORD"
        mode.value = original?.mode ?: "TMUX"
        os.value = original?.os ?: "UNKNOWN"
        savePassword.value = original?.encryptedPassword != null
        keyLabel.value = original?.keyLabel
        active = true
    }

    fun save(app: ItermuxApp, original: Host?) {
        if (busy.value) return
        busy.value = true
        error.value = null
        savingJob = viewModelScope.launch {
            var credentials: Credentials? = null
            try {
                require(name.value.trim().isNotEmpty() && name.value.length <= 80) { tr("名称需为 1–80 个字符") }
                val target = Target(address.value.trim(), port.value.toIntOrNull() ?: 0, username.value.trim(),
                    AuthMethod.valueOf(auth.value), SessionMode.valueOf(mode.value))
                require(auth.value != "KEY" || imported.value != null || original?.encryptedKey != null) { tr("请先导入私钥") }
                require(!savePassword.value || auth.value != "PASSWORD" || password.value.isNotEmpty() || original?.encryptedPassword != null) { tr("请输入要保存的密码") }
                val sameTarget = original?.let { it.address == target.address && it.port == target.port && it.username == target.username } == true
                val hostName = name.value.trim()
                val label = keyLabel.value.takeIf { auth.value == "KEY" }
                val keepPassword = savePassword.value
                val secret = password.value.toByteArray()
                val key = imported.value?.copyOf()
                val phrase = passphrase.value.takeIf { it.isNotEmpty() }?.toByteArray()
                val host = try {
                    withContext(Dispatchers.IO) {
                        credentials = Credentials(
                            password = if (target.auth == AuthMethod.PASSWORD) secret.takeIf { it.isNotEmpty() }?.copyOf()
                                ?: original?.encryptedPassword?.let { app.vault.decrypt(it, "$id:password") } else null,
                            privateKey = if (target.auth == AuthMethod.KEY) key?.copyOf()
                                ?: original?.encryptedKey?.let { app.vault.decrypt(it, "$id:key") } else null,
                            passphrase = phrase?.copyOf())
                        Host(id, hostName, target.address, target.port, target.username, target.auth.name, target.mode.name,
                            encryptedPassword = credentials?.password?.takeIf { target.auth == AuthMethod.PASSWORD && keepPassword }
                                ?.let { app.vault.encrypt(it, "$id:password") },
                            encryptedKey = credentials?.privateKey?.let { app.vault.encrypt(it, "$id:key") },
                            keyLabel = label, os = if (sameTarget) original!!.os else HostSystem.UNKNOWN.name)
                            .also { app.database.hosts().save(it) }
                    }
                } finally { secret.fill(0); key?.fill(0); phrase?.fill(0) }
                // Saving remains possible offline or without a password. The next authenticated
                // terminal connection will fill in metadata through the same detector.
                val canDetect = target.auth != AuthMethod.PASSWORD || credentials?.password?.isNotEmpty() == true
                if (canDetect) {
                    detecting.value = true
                    val probe = JschSession(target, requireNotNull(credentials), app.pins, interaction = { prompt ->
                        val request = PendingPrompt(prompt)
                        withContext(Dispatchers.Main.immediate) { pending.value = request }
                        try { request.answer.await() }
                        finally { withContext(NonCancellable + Dispatchers.Main.immediate) { pending.value = null } }
                    }, connectTimeoutMs = 8_000)
                    detector = probe
                    val system = probe.detectHostSystem()
                    if (system != HostSystem.UNKNOWN) {
                        app.database.hosts().updateSystem(host.id, host.address, host.port, host.username, system.name)
                        os.value = system.name
                    }
                }
                saved.value = true
            } catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { error.value = e.message }
            catch (_: Exception) { error.value = tr("保存失败，无法使用本机加密存储。请重试。") }
            finally {
                credentials?.clear()
                detector?.disconnect()
                detector = null
                detecting.value = false
                busy.value = false
            }
        }
    }

    fun skipDetection() {
        pending.value?.answer?.complete(null)
        detector?.disconnect()
    }

    fun close() {
        skipDetection()
        savingJob?.cancel()
        savingJob = null
        imported.value?.fill(0)
        imported.value = null
        password.value = ""
        passphrase.value = ""
        error.value = null
        saved.value = false
        busy.value = false
        detecting.value = false
        active = false
        sourceId = null
    }

    override fun onCleared() { close() }
}

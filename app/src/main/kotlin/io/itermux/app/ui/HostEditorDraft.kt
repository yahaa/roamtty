package io.itermux.app.ui

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import io.itermux.app.data.Host
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
    val os = mutableStateOf("LINUX")
    val savePassword = mutableStateOf(false)
    val password = mutableStateOf("")
    val imported = mutableStateOf<ByteArray?>(null)
    val keyLabel = mutableStateOf<String?>(null)

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
        os.value = original?.os ?: "LINUX"
        savePassword.value = original?.encryptedPassword != null
        keyLabel.value = original?.keyLabel
        active = true
    }

    fun close() {
        imported.value?.fill(0)
        imported.value = null
        password.value = ""
        active = false
        sourceId = null
    }

    override fun onCleared() { close() }
}

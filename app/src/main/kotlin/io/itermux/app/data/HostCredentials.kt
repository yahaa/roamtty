package io.itermux.app.data

import io.itermux.ssh.AuthMethod
import io.itermux.ssh.Credentials
import io.itermux.ssh.privateKeyNeedsPassphrase
import java.util.concurrent.ConcurrentHashMap

/** Whether opening the host page can log in by itself, or needs a password typed first. */
internal val Host.needsTypedPassword: Boolean get() = auth == AuthMethod.PASSWORD.name && encryptedPassword == null

/** Stored key (host id + envelope hash) → whether it is passphrase-protected; reading it means a Keystore decrypt. */
private val keyNeedsPassphrase = ConcurrentHashMap<String, Boolean>()

/**
 * Whether connecting needs something typed first: an unsaved password, or the passphrase of an encrypted key.
 * A key without a passphrase logs in straight away, like a saved password.
 */
internal fun Host.needsTypedSecret(vault: SecretVault): Boolean {
    if (auth != AuthMethod.KEY.name) return needsTypedPassword
    val envelope = encryptedKey ?: return false
    return keyNeedsPassphrase.getOrPut("$id:${envelope.contentHashCode()}") {
        runCatching {
            val key = vault.decrypt(envelope, "$id:key")
            try { privateKeyNeedsPassphrase(key) } finally { key.fill(0) }
        }.getOrDefault(true)
    }
}

/**
 * The credentials of one login to [host]: its saved password or key, else the [password] typed for it, and a typed key
 * [passphrase]. Saved secrets are decrypted with the Keystore, so call this off the main thread.
 */
internal fun SecretVault.credentialsFor(host: Host, password: String, passphrase: String) = Credentials(
    password = if (host.auth == AuthMethod.PASSWORD.name) host.encryptedPassword?.let { decrypt(it, "${host.id}:password") } ?: password.toByteArray() else null,
    privateKey = host.encryptedKey?.takeIf { host.auth == AuthMethod.KEY.name }?.let { decrypt(it, "${host.id}:key") },
    passphrase = passphrase.takeIf { it.isNotEmpty() }?.toByteArray())

/**
 * What a login to this host is made of. Pages keep their login while this stays the same, so editing the
 * address, user or credentials logs in again, while a plugin probe or OS detection saving the host does not.
 */
internal val Host.loginKey: List<Any?> get() =
    listOf(id, address, port, username, auth, encryptedPassword?.contentHashCode(), encryptedKey?.contentHashCode())

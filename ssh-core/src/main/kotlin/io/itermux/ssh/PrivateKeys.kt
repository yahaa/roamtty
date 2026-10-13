package io.itermux.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair

/**
 * Whether [key] (OpenSSH or PEM) is protected by a passphrase. A key JSch cannot read counts as encrypted, so the
 * caller still asks rather than failing silently.
 */
fun privateKeyNeedsPassphrase(key: ByteArray): Boolean {
    val copy = key.copyOf()
    return try {
        val pair = KeyPair.load(JSch(), copy, null)
        try { pair.isEncrypted } finally { pair.dispose() }
    } catch (_: Exception) {
        true
    } finally {
        copy.fill(0)
    }
}

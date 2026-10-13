package io.itermux.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class PrivateKeysTest {
    /** A PEM RSA key written by JSch. */
    private fun pemKey(passphrase: String?): ByteArray {
        val pair = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 1024)
        return ByteArrayOutputStream().also { pair.writePrivateKey(it, passphrase?.toByteArray()) }.toByteArray()
            .also { pair.dispose() }
    }

    /** An OpenSSH-format Ed25519 key written by ssh-keygen, as users import them. */
    private fun openSshKey(passphrase: String): ByteArray {
        val dir = Files.createTempDirectory("keys").toFile()
        try {
            val file = dir.resolve("id")
            val made = runCatching {
                ProcessBuilder("ssh-keygen", "-q", "-t", "ed25519", "-N", passphrase, "-f", file.path)
                    .redirectErrorStream(true).start().waitFor() == 0
            }.getOrDefault(false)
            assumeTrue("ssh-keygen unavailable", made)
            return file.readBytes()
        } finally { dir.deleteRecursively() }
    }

    @Test fun plainPemKeyNeedsNoPassphrase() = assertFalse(privateKeyNeedsPassphrase(pemKey(null)))
    @Test fun encryptedPemKeyNeedsPassphrase() = assertTrue(privateKeyNeedsPassphrase(pemKey("secret1")))
    @Test fun plainOpenSshKeyNeedsNoPassphrase() = assertFalse(privateKeyNeedsPassphrase(openSshKey("")))
    @Test fun encryptedOpenSshKeyNeedsPassphrase() = assertTrue(privateKeyNeedsPassphrase(openSshKey("secret1")))
    @Test fun unreadableKeyStillAsks() = assertTrue(privateKeyNeedsPassphrase("not a key".toByteArray()))
}

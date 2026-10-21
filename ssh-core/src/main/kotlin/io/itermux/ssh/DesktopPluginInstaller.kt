package io.itermux.ssh

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPInputStream

/**
 * Installs roamtty-desktop: download → upload → verify → register (plan §6.3). The host downloads
 * first (10 s at most); when it cannot reach the release, the phone downloads and uploads over SFTP.
 * Either way the unpacked binary must match the SHA-256 pinned in the app.
 */
class DesktopPluginInstaller(
    private val remote: RemoteHost,
    /** Downloads a URL on the phone, following redirects. */
    private val fetch: suspend (String) -> ByteArray,
    private val onStep: (Step) -> Unit = {},
    private val onLine: (String) -> Unit = {},
) {
    /** One pinned build for the host's target. [openh264Url] is Cisco's .bz2 for it, null when not needed. */
    data class Build(val version: String, val url: String, val sha256: String, val openh264Url: String?)

    enum class Step { HOST_DOWNLOAD, PHONE_DOWNLOAD, UPLOAD, VERIFY, ENCODER_HOST, ENCODER_PHONE, REGISTER, DONE }

    sealed interface Result {
        data object Installed : Result
        data class Failed(val reason: Reason, val detail: String = "") : Result
    }
    enum class Reason { DOWNLOAD, CHECKSUM, ENCODER, HOST }

    suspend fun install(build: Build): Result {
        if (remote.run("mkdir -p \"\$HOME/.roamtty/bin\" \"\$HOME/.roamtty/desktop\"", 15_000, onLine) != 0)
            return Result.Failed(Reason.HOST, "mkdir")
        onStep(Step.HOST_DOWNLOAD)
        val hostStatus = remote.run(hostDownloadScript(build), 30_000, onLine)
        if (hostStatus != 0) {
            onStep(Step.PHONE_DOWNLOAD)
            val packed = try { fetch(build.url) } catch (e: Exception) {
                return Result.Failed(Reason.DOWNLOAD, e.message.orEmpty())
            }
            val binary = try { GZIPInputStream(ByteArrayInputStream(packed)).readBytes() } catch (e: Exception) {
                return Result.Failed(Reason.CHECKSUM, "gzip")
            }
            if (sha256(binary) != build.sha256) return Result.Failed(Reason.CHECKSUM, sha256(binary))
            onStep(Step.UPLOAD)
            val part = ".roamtty/bin/.roamtty-desktop-${UUID.randomUUID()}.part"
            try { remote.upload(part, ByteArrayInputStream(binary)) } catch (e: Exception) {
                return Result.Failed(Reason.HOST, e.message.orEmpty())
            }
            onStep(Step.VERIFY)
            if (remote.run(placeScript("\$HOME/$part", build.sha256), 15_000, onLine) != 0) return Result.Failed(Reason.CHECKSUM)
        }
        build.openh264Url?.let { url ->
            onStep(Step.ENCODER_HOST)
            if (remote.run("exec 2>&1; \"\$HOME/.roamtty/bin/roamtty-desktop\" install", 30_000, onLine) != 0) {
                onStep(Step.ENCODER_PHONE)
                val packed = try { fetch(url) } catch (e: Exception) { return Result.Failed(Reason.ENCODER, e.message.orEmpty()) }
                val part = ".roamtty/desktop/openh264-${UUID.randomUUID()}.bz2"
                try { remote.upload(part, ByteArrayInputStream(packed)) } catch (e: Exception) {
                    return Result.Failed(Reason.HOST, e.message.orEmpty())
                }
                // The plugin unpacks it and loads only Cisco's own build (it checks the hash).
                val status = remote.run("exec 2>&1; f=\"\$HOME/$part\"; \"\$HOME/.roamtty/bin/roamtty-desktop\" install --openh264 \"\$f\"; s=\$?; rm -f \"\$f\"; exit \$s",
                    30_000, onLine)
                if (status != 0) return Result.Failed(Reason.ENCODER)
            }
        }
        onStep(Step.REGISTER)
        onStep(Step.DONE)
        return Result.Installed
    }

    companion object {
        const val FETCH_FAILED = "__ROAMTTY_FETCH_FAILED__"
        const val SHA_MISMATCH = "__ROAMTTY_SHA_MISMATCH__"

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /** Checks [path] against [sha] and renames it into place atomically; the checksum tool differs by system. */
        internal fun placeScript(path: String, sha: String) = """
            exec 2>&1
            f="$path"
            got=${'$'}( (sha256sum "${'$'}f" 2>/dev/null || shasum -a 256 "${'$'}f" 2>/dev/null) | cut -d' ' -f1)
            if [ "${'$'}got" != "$sha" ]; then echo $SHA_MISMATCH "${'$'}got"; rm -f "${'$'}f"; exit 4; fi
            chmod 755 "${'$'}f" && mv -f "${'$'}f" "${'$'}HOME/.roamtty/bin/roamtty-desktop"
        """.trimIndent()

        internal fun hostDownloadScript(build: Build): String {
            val url = build.url.replace("'", "")
            return """
                exec 2>&1
                f="${'$'}HOME/.roamtty/bin/.roamtty-desktop.part"
                fetch() {
                  if command -v curl >/dev/null 2>&1; then curl -fsSL --max-time 10 '$url'
                  elif command -v wget >/dev/null 2>&1; then wget -q -T 10 -O - '$url'
                  else return 9; fi
                }
                if ! fetch | gzip -dc > "${'$'}f" 2>/dev/null || [ ! -s "${'$'}f" ]; then rm -f "${'$'}f"; echo $FETCH_FAILED; exit 3; fi
            """.trimIndent() + "\n" + placeScript("\$f", build.sha256)
        }
    }
}

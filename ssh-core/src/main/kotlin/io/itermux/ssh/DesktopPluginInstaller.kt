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
    /**
     * One pinned build for the host's target. [openh264Url] is Cisco's .bz2 for it, null when not needed.
     * A `.zip` [url] is the notarized macOS app: [sha256] is the archive's own, it is unpacked into
     * ~/.roamtty and its binary linked to ~/.roamtty/bin; `roamtty-desktop install` then starts the agent.
     */
    data class Build(val version: String, val url: String, val sha256: String, val openh264Url: String?) {
        val app: Boolean get() = url.endsWith(".zip")
        /** A native Windows host: PowerShell scripts, an .exe, a logon task. */
        val windows: Boolean get() = url.endsWith(".exe")
    }

    enum class Step { HOST_DOWNLOAD, PHONE_DOWNLOAD, UPLOAD, VERIFY, ENCODER_HOST, ENCODER_PHONE, REGISTER, DONE }

    sealed interface Result {
        data object Installed : Result
        data class Failed(val reason: Reason, val detail: String = "") : Result
    }
    enum class Reason { DOWNLOAD, CHECKSUM, ENCODER, HOST }

    suspend fun install(build: Build): Result {
        if (build.windows) return installWindows(build)
        if (remote.run("mkdir -p \"\$HOME/.roamtty/bin\" \"\$HOME/.roamtty/desktop\"", 15_000, onLine) != 0)
            return Result.Failed(Reason.HOST, "mkdir")
        onStep(Step.HOST_DOWNLOAD)
        val hostStatus = remote.run(if (build.app) hostAppScript(build) else hostDownloadScript(build), 60_000, onLine)
        if (hostStatus != 0 && build.app) {
            onStep(Step.PHONE_DOWNLOAD)
            val zip = try { fetch(build.url) } catch (e: Exception) { return Result.Failed(Reason.DOWNLOAD, e.message.orEmpty()) }
            if (sha256(zip) != build.sha256) return Result.Failed(Reason.CHECKSUM, sha256(zip))
            onStep(Step.UPLOAD)
            val part = ".roamtty/bin/.roamtty-desktop-${UUID.randomUUID()}.zip"
            try { remote.upload(part, ByteArrayInputStream(zip)) } catch (e: Exception) { return Result.Failed(Reason.HOST, e.message.orEmpty()) }
            onStep(Step.VERIFY)
            if (remote.run(placeAppScript("\$HOME/$part", build.sha256), 30_000, onLine) != 0) return Result.Failed(Reason.CHECKSUM)
        } else if (hostStatus != 0) {
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
        // macOS: (re)load the LaunchAgent; it runs in the GUI session, where TCC permissions apply.
        if (build.app && remote.run("exec 2>&1; \"\$HOME/.roamtty/bin/roamtty-desktop\" install", 30_000, onLine) != 0)
            return Result.Failed(Reason.HOST, "agent")
        onStep(Step.DONE)
        return Result.Installed
    }

    private suspend fun ps(script: String, timeoutMs: Long) = remote.runCommand(PowerShell.command(script), timeoutMs, onLine)

    private suspend fun installWindows(build: Build): Result {
        if (ps(WindowsDesktop.mkdirScript, 15_000) != 0) return Result.Failed(Reason.HOST, "mkdir")
        onStep(Step.HOST_DOWNLOAD)
        if (ps(WindowsDesktop.hostDownloadScript(build.url, build.sha256), 60_000) != 0) {
            onStep(Step.PHONE_DOWNLOAD)
            val exe = try { fetch(build.url) } catch (e: Exception) { return Result.Failed(Reason.DOWNLOAD, e.message.orEmpty()) }
            if (sha256(exe) != build.sha256) return Result.Failed(Reason.CHECKSUM, sha256(exe))
            onStep(Step.UPLOAD)
            // Windows OpenSSH's SFTP starts in the user's profile.
            val part = ".roamtty/bin/roamtty-desktop-${UUID.randomUUID()}.part"
            try { remote.upload(part, ByteArrayInputStream(exe)) } catch (e: Exception) { return Result.Failed(Reason.HOST, e.message.orEmpty()) }
            onStep(Step.VERIFY)
            if (ps(WindowsDesktop.placeScript("\"\$env:USERPROFILE\\${part.replace('/', '\\')}\"", build.sha256), 30_000) != 0)
                return Result.Failed(Reason.CHECKSUM)
        }
        onStep(Step.ENCODER_HOST)
        var status = ps(WindowsDesktop.installScript(), 60_000)
        if (status == 3 && build.openh264Url != null) {
            onStep(Step.ENCODER_PHONE)
            val packed = try { fetch(build.openh264Url) } catch (e: Exception) { return Result.Failed(Reason.ENCODER, e.message.orEmpty()) }
            val part = ".roamtty/desktop/openh264-${UUID.randomUUID()}.bz2"
            try { remote.upload(part, ByteArrayInputStream(packed)) } catch (e: Exception) { return Result.Failed(Reason.HOST, e.message.orEmpty()) }
            status = ps(WindowsDesktop.installScript(part.replace('/', '\\')), 60_000)
        }
        onStep(Step.REGISTER)
        if (status != 0) return Result.Failed(if (status == 3) Reason.ENCODER else Reason.HOST, "install $status")
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

        /** Checks the archive [path] against [sha], unpacks the app into ~/.roamtty and links its binary. */
        internal fun placeAppScript(path: String, sha: String) = """
            exec 2>&1
            f="$path"
            got=${'$'}( (shasum -a 256 "${'$'}f" 2>/dev/null || sha256sum "${'$'}f" 2>/dev/null) | cut -d' ' -f1)
            if [ "${'$'}got" != "$sha" ]; then echo $SHA_MISMATCH "${'$'}got"; rm -f "${'$'}f"; exit 4; fi
            d="${'$'}HOME/.roamtty"
            rm -rf "${'$'}d/.app-new" && mkdir -p "${'$'}d/.app-new" && ditto -x -k "${'$'}f" "${'$'}d/.app-new" || { rm -f "${'$'}f"; exit 5; }
            rm -f "${'$'}f"
            rm -rf "${'$'}d/Roamtty Desktop.app" && mv "${'$'}d/.app-new/Roamtty Desktop.app" "${'$'}d/" && rm -rf "${'$'}d/.app-new"
            mkdir -p "${'$'}d/bin" && ln -sf "${'$'}d/Roamtty Desktop.app/Contents/MacOS/roamtty-desktop" "${'$'}d/bin/roamtty-desktop"
        """.trimIndent()

        internal fun hostAppScript(build: Build): String {
            val url = build.url.replace("'", "")
            return """
                exec 2>&1
                f="${'$'}HOME/.roamtty/bin/.roamtty-desktop.zip"
                if ! curl -fsSL --max-time 20 '$url' -o "${'$'}f" || [ ! -s "${'$'}f" ]; then rm -f "${'$'}f"; echo $FETCH_FAILED; exit 3; fi
            """.trimIndent() + "\n" + placeAppScript("\$f", build.sha256)
        }

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

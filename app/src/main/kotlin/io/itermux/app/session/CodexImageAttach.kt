package io.itermux.app.session

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.termux.terminal.TerminalEmulator
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.math.min

/**
 * Pick → PNG → SFTP `/tmp/itermux-img-*.png` → optional bracketed-paste of the remote path
 * into Codex/Grok CLI. Detection prefers false-negatives (upload + show path) over injecting
 * into an ordinary shell that would treat the path as a command.
 */
object CodexImageAttach {
    /** Whole-token match for `codex` / `grok` in title or recent screen text. */
    private val MARKER = Regex("""(?i)(?<![A-Za-z0-9_])(codex|grok)(?![A-Za-z0-9_])""")

    fun newRemotePngPath(): String = "/tmp/itermux-img-${UUID.randomUUID()}.png"

    /** Pure heuristic used by unit tests and [looksLikeCodexOrGrokCli]. */
    fun looksLikeCodexOrGrok(haystack: String): Boolean = MARKER.containsMatchIn(haystack)

    /**
     * Inspect OSC title plus the visible screen and a short scrollback window.
     * Alternate-buffer TUIs (typical for Codex) still expose the banner on the active screen.
     */
    fun looksLikeCodexOrGrokCli(emulator: TerminalEmulator): Boolean {
        val title = emulator.title.orEmpty()
        val screen = emulator.screen
        val cols = emulator.columns
        val rows = emulator.rows
        val lookback = min(64, screen.activeTranscriptRows)
        val recent = runCatching {
            screen.getSelectedText(0, -lookback, cols, rows)
        }.getOrDefault("")
        return looksLikeCodexOrGrok("$title\n$recent")
    }

    /** Decode JPEG/PNG/(decoder-supported) gallery images and rewrite as PNG for a stable remote suffix. */
    fun convertToPngFile(context: Context, uri: Uri): File {
        val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input)
        } ?: throw IOException("无法解码图片")
        val out = File(context.cacheDir, "itermux-img-${UUID.randomUUID()}.png")
        try {
            FileOutputStream(out).use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    throw IOException("无法编码为 PNG")
                }
            }
        } catch (e: Exception) {
            out.delete()
            throw e
        } finally {
            bitmap.recycle()
        }
        return out
    }
}

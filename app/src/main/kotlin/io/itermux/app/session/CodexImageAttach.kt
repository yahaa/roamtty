package io.itermux.app.session

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** Convert each explicitly selected image to PNG and insert its remote path into the current prompt. */
object CodexImageAttach {
    fun newRemotePngPath(): String = "/tmp/itermux-img-${UUID.randomUUID()}.png"

    /** Spaces separate successive attachments and any surrounding prompt text; never submit. */
    fun pathForPaste(path: String): String = " $path "

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

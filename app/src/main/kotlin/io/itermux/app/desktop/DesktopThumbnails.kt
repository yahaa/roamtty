package io.itermux.app.desktop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.graphics.scale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * The last picture of each host's desktop, small, for its card in the hosts list: taken from the page's surface while
 * a desktop shows, kept on disk so an offline host still shows what it looked like.
 */
internal object DesktopThumbnails {
    /** Wide enough for a card on a tablet, a few tens of KB as JPEG. */
    const val WIDTH = 640

    private val mutableVersions = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** Bumped per host on every save or removal, so a card reloads its picture. */
    val versions: StateFlow<Map<String, Long>> = mutableVersions.asStateFlow()

    /** Generation per host: remove() bumps it so in-flight saves for an older generation are dropped. */
    private val generations = ConcurrentHashMap<String, AtomicLong>()
    private val writers = ConcurrentHashMap<String, Any>()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "desktop-thumbs").apply { isDaemon = true } }

    private fun dir(context: Context) = File(context.filesDir, "desktop-thumbs")
    private fun file(context: Context, hostId: String) = File(dir(context), "${hostId.filter { it.isLetterOrDigit() || it == '-' }}.jpg")
    private fun generation(hostId: String) = generations.getOrPut(hostId) { AtomicLong(0) }
    private fun lockFor(hostId: String) = writers.getOrPut(hostId) { Any() }

    fun load(context: Context, hostId: String): Bitmap? =
        file(context, hostId).takeIf { it.isFile }?.let { runCatching { BitmapFactory.decodeFile(it.path) }.getOrNull() }

    fun save(context: Context, hostId: String, bitmap: Bitmap, expectedGeneration: Long? = null) {
        val gen = generation(hostId)
        if (expectedGeneration != null && gen.get() != expectedGeneration) return
        synchronized(lockFor(hostId)) {
            if (expectedGeneration != null && gen.get() != expectedGeneration) return
            val target = file(context, hostId)
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, "${target.name}.${Thread.currentThread().name}.${System.nanoTime()}.tmp")
            runCatching {
                temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                if (expectedGeneration != null && gen.get() != expectedGeneration) {
                    temp.delete()
                    return
                }
                if (!temp.renameTo(target)) {
                    target.delete()
                    temp.renameTo(target)
                }
            }.onFailure { temp.delete(); return }
            mutableVersions.value = mutableVersions.value + (hostId to System.currentTimeMillis())
        }
    }

    fun remove(context: Context, hostId: String) {
        generation(hostId).incrementAndGet()
        synchronized(lockFor(hostId)) {
            file(context, hostId).delete()
            dir(context).listFiles()?.filter {
                it.name.startsWith(file(context, hostId).name) && it.extension == "tmp" ||
                    it.name.startsWith(file(context, hostId).name + ".")
            }?.forEach { it.delete() }
            // Also clear any leftover *.tmp for this host id stem.
            val stem = file(context, hostId).name
            dir(context).listFiles()?.filter { it.name.startsWith(stem) && it.name != stem }?.forEach { it.delete() }
        }
        mutableVersions.value = mutableVersions.value + (hostId to System.currentTimeMillis())
    }

    /** Copies what [view] shows, scaled to [WIDTH], and hands it over on the main thread; null when the copy failed. */
    fun capture(view: SurfaceView, videoWidth: Int, videoHeight: Int, done: (Bitmap?) -> Unit) {
        if (videoWidth <= 0 || videoHeight <= 0 || !view.holder.surface.isValid) return done(null)
        val width = minOf(WIDTH, videoWidth)
        val bitmap = createBitmap(width, (videoHeight.toLong() * width / videoWidth).toInt().coerceAtLeast(1))
        runCatching {
            PixelCopy.request(view, bitmap, { result -> done(bitmap.takeIf { result == PixelCopy.SUCCESS && !it.isBlank() }) },
                Handler(Looper.getMainLooper()))
        }.onFailure { done(null) }
    }

    /** [capture], then saved off the main thread as [hostId]'s picture. */
    fun captureAndSave(context: Context, hostId: String, view: SurfaceView, videoWidth: Int, videoHeight: Int) {
        val app = context.applicationContext
        val expected = generation(hostId).get()
        capture(view, videoWidth, videoHeight) { bitmap ->
            if (bitmap != null) io.execute { save(app, hostId, bitmap, expectedGeneration = expected) }
        }
    }

    /** A frame not yet drawn copies as all black (or all transparent): not worth keeping over an older picture. */
    private fun Bitmap.isBlank(): Boolean {
        val step = maxOf(1, width / 16)
        for (y in 0 until height step maxOf(1, height / 16)) for (x in 0 until width step step) {
            val p = this[x, y]
            if ((p ushr 24) != 0 && (p and 0xFFFFFF) > 0x101010) return false
        }
        return true
    }

    /**
     * A small, softly blurred copy of [source] for the frosted strip over a card's picture: drawn stretched, its
     * bilinear filtering finishes the blur.
     */
    fun frosted(source: Bitmap): Bitmap {
        val w = 48
        val h = (source.height.toLong() * w / source.width).toInt().coerceAtLeast(1)
        val small = source.scale(w, h)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        repeat(2) { boxBlur(pixels, w, h, 2) }
        val out = createBitmap(w, h)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== source) small.recycle()
        return out
    }

    private fun boxBlur(pixels: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(pixels.size)
        fun pass(src: IntArray, dst: IntArray, horizontal: Boolean) {
            val (lines, length) = if (horizontal) h to w else w to h
            for (line in 0 until lines) for (i in 0 until length) {
                var red = 0; var green = 0; var blue = 0; var n = 0
                for (k in maxOf(0, i - r)..minOf(length - 1, i + r)) {
                    val p = src[if (horizontal) line * w + k else k * w + line]
                    red += (p shr 16) and 0xFF; green += (p shr 8) and 0xFF; blue += p and 0xFF; n++
                }
                dst[if (horizontal) line * w + i else i * w + line] = (0xFF shl 24) or (red / n shl 16) or (green / n shl 8) or (blue / n)
            }
        }
        pass(pixels, tmp, true)
        pass(tmp, pixels, false)
    }
}

package io.itermux.app.desktop

import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.itermux.app.ItermuxApp
import io.itermux.app.data.Host
import io.itermux.desktop.DesktopProtocol
import io.itermux.desktop.ServerMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DesktopMemoryTest {
    private fun gc() = repeat(4) { Runtime.getRuntime().gc(); System.runFinalization(); Thread.sleep(100) }

    private fun submitBurst(decoder: VideoDecoder): List<WeakReference<ByteArray>> = List(128) { i ->
        val bytes = ByteArray(64 * 1024)
        decoder.submit(ServerMessage.Video(i.toLong(), if (i == 0) DesktopProtocol.VIDEO_KEY else 0,
            0, 0, 64, 64, bytes, 0))
        WeakReference(bytes)
    }

    @Test fun stalledDecoderDoesNotRetainAnUnboundedVideoMailbox() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val texture = SurfaceTexture(false)
        val surface = Surface(texture)
        val decoder = VideoDecoder(onDecoded = { _, _ -> }, onRendered = { _, _ -> }, onNeedKeyframe = {
            entered.countDown()
            check(resume.await(15, TimeUnit.SECONDS))
        })
        try {
            decoder.attach(surface)
            assertTrue("Decoder thread did not start", entered.await(5, TimeUnit.SECONDS))
            val refs = submitBurst(decoder)
            gc()
            val retained = refs.count { it.get() != null }
            android.util.Log.i("MEMORY_AUDIT", "blocked decoder retained $retained / 128 payloads")
            assertTrue("Stalled decoder retained $retained video payloads", retained <= 8)
        } finally {
            // Detach must discard the queued test payloads before the decoder thread resumes.
            decoder.detach()
            resume.countDown()
            decoder.close()
            surface.release()
            texture.release()
        }
    }

    @Test fun missingDecodedFramesDoNotGrowReceptionBookkeepingForever() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ItermuxApp
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val connection = DesktopConnection(Host(name = "memory probe", address = "127.0.0.1", username = "test"),
            app, scope, "", "", 64, 64, 160, {})
        // Exercise the actual receive boundary without a host or a Surface: all frames are discarded by the decoder.
        val receive = DesktopConnection::class.java.getDeclaredMethod("handle", ServerMessage::class.java).apply { isAccessible = true }
        val received = DesktopConnection::class.java.getDeclaredField("received").apply { isAccessible = true }
        try {
            repeat(10_000) { frame ->
                receive.invoke(connection, ServerMessage.Video(frame.toLong(), 0, 0, 0, 64, 64, byteArrayOf(0), 0))
            }
            val count = (received.get(connection) as Map<*, *>).size
            android.util.Log.i("MEMORY_AUDIT", "no-surface receiver retained $count / 10000 timestamps")
            assertTrue("Reception timestamps retained without a successful decode: $count", count <= 240)
        } finally {
            connection.close()
            connection.release()
            scope.cancel()
        }
    }
}

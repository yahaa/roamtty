package io.itermux.desktop

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * This client against the real plugin on loopback. Needs a built plugin and an X display with
 * OpenH264 installed under HOME: ROAMTTY_DESKTOP_BIN, ROAMTTY_DESKTOP_HOME, DISPLAY. Skipped otherwise.
 */
class UdpPluginIntegrationTest {
    private val bin = System.getenv("ROAMTTY_DESKTOP_BIN")
    private val home = System.getenv("ROAMTTY_DESKTOP_HOME")
    private val display = System.getenv("DISPLAY")

    private class Serve(val p: Process, val hello: Hello) {
        val sshVideo = AtomicLong()
        fun send(frame: ByteArray) { p.outputStream.write(frame); p.outputStream.flush() }
    }

    private fun serve(): Serve {
        val p = ProcessBuilder(bin, "serve", "--proto", "1", "--client", "1280x720@160").apply {
            environment()["HOME"] = home; environment()["DISPLAY"] = display; environment()["ROAMTTY_DESKTOP_TEST"] = "1"
            environment().remove("SSH_CLIENT")
            redirectError(ProcessBuilder.Redirect.DISCARD)
        }.start()
        val hello = (DesktopProtocol.parseStart(DesktopProtocol.readLine(p.inputStream)!!) as Start.Ready).hello
        val s = Serve(p, hello)
        Thread {
            val input = java.io.DataInputStream(p.inputStream.buffered())
            while (true) { val f = try { DesktopProtocol.read(input) } catch (_: Exception) { null } ?: break; if (f.type == DesktopProtocol.VIDEO) s.sshVideo.incrementAndGet() }
        }.apply { isDaemon = true; start() }
        return s
    }

    private fun waitFor(ms: Long, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) { assertTrue("timed out: $what", System.currentTimeMillis() < end); Thread.sleep(20) }
    }

    @Test fun videoOverUdpWithLossFallbackAndRoaming() {
        assumeTrue("plugin fixture not configured", bin != null && home != null && display != null && File(bin).canExecute())
        val s = serve()
        val udp = s.hello.udp!!
        assertTrue(udp.port in 60000 until 61000)
        val frames = ConcurrentLinkedQueue<Pair<Long, Long>>() // (frame, arrival ms)
        val lost = AtomicLong()
        lateinit var link: UdpLink
        link = UdpLink(java.net.InetAddress.getLoopbackAddress(), udp.port, udp.key, onFrame = { f ->
            if (f.type == DesktopProtocol.VIDEO) {
                val v = DesktopProtocol.decode(f) as ServerMessage.Video
                frames += v.frame to System.currentTimeMillis()
                link.send(DesktopProtocol.ack(v.frame, 0, 0), reliable = false)
            }
        }, onVideoLost = { lost.incrementAndGet(); link.send(DesktopProtocol.req(DesktopProtocol.REQ_KEYFRAME), true) })
        link.start()
        waitFor(5000, "first video over UDP") { frames.isNotEmpty() }
        assertTrue(link.alive)

        // 5% loss each way: typing keeps the screen changing; FEC and resends carry it.
        s.send(DesktopProtocol.config(mapOf("debug_loss" to 0.05)))
        link.dropRate = 0.05
        // Settle the reports of the clean frames first.
        Thread.sleep(300)
        val before = frames.size
        val typed = "UDP_LOSS_" + System.nanoTime()
        link.send(DesktopProtocol.abs(0, 100, 100), false)
        repeat(60) { i -> link.send(DesktopProtocol.text("${typed.take(4)}$i "), reliable = true); Thread.sleep(80) }
        Thread.sleep(800)
        val got = frames.size - before
        println("loss run: frames=$got lost=${link.lostMessages} fec=${link.fecRecovered} nacks=${link.nacks} loss=${"%.3f".format(link.loss)} rtt=${link.rttMs}")
        assertTrue("frames kept coming under loss ($got)", got >= 30)
        assertTrue("measured loss is about the 10% injected (${link.loss})", link.loss == 0.0 || link.loss in 0.02..0.25)
        assertTrue("FEC rebuilt some frames", link.fecRecovered > 0)
        assertTrue("few frames lost (${link.lostMessages} of $got)", link.lostMessages * 10 <= got)

        // UDP blocked: after ~3 s the host sends video down the SSH channel instead.
        link.dropRate = 1.0
        s.send(DesktopProtocol.config(mapOf("debug_loss" to 0.0)))
        val sshBefore = s.sshVideo.get()
        val blockedAt = System.currentTimeMillis()
        waitFor(8000, "fallback to SSH") {
            if (System.currentTimeMillis() - blockedAt > 3500) s.send(DesktopProtocol.text("x"))
            s.sshVideo.get() > sshBefore
        }
        println("fallback after ${System.currentTimeMillis() - blockedAt} ms")
        // Unblocked: back on UDP.
        link.dropRate = 0.0
        val udpBefore = frames.size
        waitFor(4000, "back on UDP") { s.send(DesktopProtocol.text("y")); Thread.sleep(150); frames.size > udpBefore }

        // A network change: new socket, the host follows within a second.
        Thread.sleep(500)
        val roamFrom = frames.size
        val t0 = System.currentTimeMillis()
        link.rebind()
        link.send(DesktopProtocol.req(DesktopProtocol.REQ_KEYFRAME), true)
        waitFor(3000, "video after rebind") { frames.size > roamFrom }
        val roamMs = System.currentTimeMillis() - t0
        println("roam recovered in $roamMs ms")
        assertTrue("roam ≤ 1 s ($roamMs ms)", roamMs <= 1000)

        // SSH closes: the desktop keeps going on UDP.
        s.p.outputStream.close()
        Thread.sleep(500)
        val afterClose = frames.size
        link.send(DesktopProtocol.text("z"), true)
        waitFor(3000, "video after the SSH channel closed") { frames.size > afterClose }
        assertTrue(s.p.isAlive)
        link.close()
        s.p.destroy()
    }

    private fun pointer(): Pair<Int, Int> {
        val out = ProcessBuilder("xdotool", "getmouselocation").apply { environment()["DISPLAY"] = display }.start()
            .inputStream.bufferedReader().readText()
        val v = Regex("""x:(\d+) y:(\d+)""").find(out)!!.groupValues
        return v[1].toInt() to v[2].toInt()
    }

    /** Pointer moves are plain datagrams (the newest wins): they still reach the desktop. */
    @Test fun pointerMovesOverUdp() {
        assumeTrue("plugin fixture not configured", bin != null && home != null && display != null && File(bin).canExecute())
        val s = serve()
        val udp = s.hello.udp!!
        val frames = AtomicLong()
        lateinit var link: UdpLink
        link = UdpLink(java.net.InetAddress.getLoopbackAddress(), udp.port, udp.key, onFrame = { f ->
            if (f.type == DesktopProtocol.VIDEO) {
                frames.incrementAndGet()
                link.send(DesktopProtocol.ack((DesktopProtocol.decode(f) as ServerMessage.Video).frame, 0, 0), reliable = false)
            }
        }, onVideoLost = {})
        link.start()
        waitFor(5000, "first video over UDP") { frames.get() > 0 }
        link.send(DesktopProtocol.abs(0, 100, 100), UdpLink.reliable(DesktopProtocol.abs(0, 100, 100)))
        waitFor(2000, "absolute move") { pointer() == 100 to 100 }
        repeat(10) { link.send(DesktopProtocol.rel(10, 5), UdpLink.reliable(DesktopProtocol.rel(10, 5))); Thread.sleep(16) }
        waitFor(2000, "relative moves (at ${pointer()})") { pointer() == 200 to 150 }
        link.close()
        s.p.destroy()
    }

    /**
     * A clipboard many shards long, over a lossy link both ways: the host RACKs it only once it is
     * whole (roamtty-desktop#7), so it lands on the desktop, and the copy coming back arrives whole.
     */
    @Test fun bigClipboardOverLossyUdp() {
        assumeTrue("plugin fixture not configured", bin != null && home != null && display != null && File(bin).canExecute())
        val s = serve()
        val udp = s.hello.udp!!
        val frames = AtomicLong()
        val clips = ConcurrentLinkedQueue<String>()
        lateinit var link: UdpLink
        link = UdpLink(java.net.InetAddress.getLoopbackAddress(), udp.port, udp.key, onFrame = { f ->
            when (val m = DesktopProtocol.decode(f)) {
                is ServerMessage.Video -> { frames.incrementAndGet(); link.send(DesktopProtocol.ack(m.frame, 0, 0), reliable = false) }
                is ServerMessage.State -> m.clip?.let { clips += it }
                else -> {}
            }
        }, onVideoLost = {})
        link.start()
        waitFor(5000, "first video over UDP") { frames.get() > 0 }
        s.send(DesktopProtocol.config(mapOf("debug_loss" to 0.2)))
        link.dropRate = 0.2
        Thread.sleep(300)
        val rnd = java.util.Random(7)
        val text = buildString { while (length < 30_000) append("剪贴板-${rnd.nextInt(1_000_000)} line\n") }
        link.send(DesktopProtocol.clipSet(text), reliable = true)
        waitFor(10_000, "clipboard set on the desktop") {
            val p = ProcessBuilder("xclip", "-o", "-selection", "clipboard").apply { environment()["DISPLAY"] = display }.start()
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
            p.waitFor()
            out == text
        }
        link.send(DesktopProtocol.req(DesktopProtocol.REQ_CLIP_GET), reliable = true)
        waitFor(10_000, "clipboard back from the desktop") { clips.any { it == text } }
        println("clipboard run: ${text.toByteArray().size} bytes, nacks=${link.nacks} loss=${"%.3f".format(link.loss)}")
        link.close()
        s.p.destroy()
    }

    /** No loss on the way: the phone asks for nothing again, whatever the pacing and the frame sizes. */
    @Test fun noNacksWithoutLoss() {
        assumeTrue("plugin fixture not configured", bin != null && home != null && display != null && File(bin).canExecute())
        // A busy screen: big frames, split into many shards and paced out.
        val xterm = ProcessBuilder("xterm", "-geometry", "160x50+0+0", "-e", "sh", "-c", "while :; do head -c 4000 /dev/urandom | base64; done")
            .apply { environment()["DISPLAY"] = display }.start()
        try {
            Thread.sleep(500)
            val s = serve()
            val udp = s.hello.udp!!
            val frames = AtomicLong()
            lateinit var link: UdpLink
            link = UdpLink(java.net.InetAddress.getLoopbackAddress(), udp.port, udp.key, onFrame = { f ->
                if (f.type == DesktopProtocol.VIDEO) {
                    frames.incrementAndGet()
                    link.send(DesktopProtocol.ack((DesktopProtocol.decode(f) as ServerMessage.Video).frame, 0, 0), reliable = false)
                }
            }, onVideoLost = {})
            link.start()
            waitFor(5000, "first video over UDP") { frames.get() > 0 }
            val (f0, n0) = frames.get() to link.nacks
            Thread.sleep(6000)
            val got = frames.get() - f0
            val nacks = link.nacks - n0
            println("no-loss run: frames=$got nacks=$nacks fec=${link.fecRecovered} lost=${link.lostMessages} loss=${link.loss}")
            assertTrue("frames came ($got)", got >= 30)
            assertTrue("NACKs without loss: $nacks for $got frames", nacks * 50 <= got)
            link.close()
            s.p.destroy()
        } finally { xterm.destroy() }
    }
}

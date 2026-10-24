package io.itermux.desktop

import org.junit.Assert.*
import org.junit.Test

/** The Kotlin codec against the plugin's golden samples (roamtty-desktop/testdata). */
class DesktopProtocolTest {
    private fun golden(name: String): ByteArray = javaClass.getResourceAsStream("/desktop-proto/$name")!!.readBytes()

    @Test fun clientMessagesMatchGoldenBytes() {
        assertArrayEquals(golden("input-abs.bin"), DesktopProtocol.abs(0, 1512, 982))
        assertArrayEquals(golden("input-rel.bin"), DesktopProtocol.rel(-5, 12))
        assertArrayEquals(golden("input-button.bin"), DesktopProtocol.button(2, true))
        assertArrayEquals(golden("input-wheel.bin"), DesktopProtocol.wheel(0, -240))
        assertArrayEquals(golden("input-key.bin"), DesktopProtocol.key(0xE3, true))
        assertArrayEquals(golden("input-text.bin"), DesktopProtocol.text("你好 ok"))
        assertArrayEquals(golden("action.bin"), DesktopProtocol.action(DesktopProtocol.ACTION_ALL_WINDOWS))
        assertArrayEquals(golden("req.bin"), DesktopProtocol.req(DesktopProtocol.REQ_KEYFRAME))
        assertArrayEquals(golden("ack.bin"), DesktopProtocol.ack(7, 1_000_001, 1_004_002))
        assertArrayEquals(golden("clip-set.bin"), DesktopProtocol.clipSet("copied"))
        assertArrayEquals(golden("ping.bin"), DesktopProtocol.ping(123_456_789))
        assertArrayEquals(golden("pong.bin"), DesktopProtocol.pong(1, 2, 3))
        // JSON key order is free; compare the decoded object.
        val config = DesktopProtocol.read(golden("config.bin").inputStream())!!
        assertEquals(DesktopProtocol.CONFIG, config.type)
        val ours = DesktopProtocol.read(DesktopProtocol.config(mapOf("fps" to 60, "quality" to "clear")).inputStream())!!
        assertEquals(org.json.JSONObject(String(config.payload)).toMap(), org.json.JSONObject(String(ours.payload)).toMap())
    }

    @Test fun serverMessagesDecode() {
        val video = DesktopProtocol.decode(DesktopProtocol.read(golden("video.bin").inputStream())!!) as ServerMessage.Video
        assertEquals(42L, video.frame)
        assertTrue(video.keyframe)
        assertFalse(video.refine)
        assertEquals(10L, video.captureUs)
        assertEquals(20L, video.encodedUs)
        assertEquals(1920 to 1080, video.width to video.height)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x67, 0x42), video.data.copyOfRange(video.offset, video.data.size))

        val pos = DesktopProtocol.decode(DesktopProtocol.read(golden("cursor-pos.bin").inputStream())!!) as ServerMessage.CursorPos
        assertEquals(ServerMessage.CursorPos(300, 200, true, 0xDEADBEEFL), pos)

        val shape = DesktopProtocol.decode(DesktopProtocol.read(golden("cursor-shape.bin").inputStream())!!) as ServerMessage.CursorShape
        assertEquals(0xDEADBEEFL, shape.hash)
        assertEquals(listOf(2, 1, 1, 0), listOf(shape.width, shape.height, shape.hotX, shape.hotY))
        assertEquals(8, shape.rgba.size)

        val state = DesktopProtocol.decode(DesktopProtocol.read(golden("state.bin").inputStream())!!) as ServerMessage.State
        assertTrue(state.kicked)
    }

    @Test fun helloParses() {
        val start = DesktopProtocol.parseStart(String(golden("hello.json")).trim()) as Start.Ready
        assertEquals(1, start.hello.proto)
        assertEquals("linux", start.hello.os)
        assertEquals(1920, start.hello.displays.single().width)
        assertTrue(start.hello.displays.single().primary)
        assertEquals(listOf("h264"), start.hello.encoders)
        assertTrue(start.hello.needs.isEmpty())
    }

    @Test fun startErrorsParse() {
        assertEquals(Start.Failed("proto_unsupported", null, 2, 3),
            DesktopProtocol.parseStart("""{"error":"proto_unsupported","min":2,"max":3}"""))
        assertEquals(Start.Failed("no_desktop", "no X display"),
            DesktopProtocol.parseStart("""{"error":"no_desktop","detail":"no X display"}"""))
        assertEquals("internal", (DesktopProtocol.parseStart("bash: not json") as Start.Failed).code)
    }

    @Test fun helloLineLeavesFramesUnread() {
        val bytes = "{\"a\":1}\n".toByteArray() + DesktopProtocol.ping(5)
        val input = bytes.inputStream()
        assertEquals("{\"a\":1}", DesktopProtocol.readLine(input))
        assertEquals(ServerMessage.Ping(5), DesktopProtocol.decode(DesktopProtocol.read(input)!!))
        assertNull(DesktopProtocol.read(input))
    }

    @Test fun manifestAndTargets() {
        val m = PluginManifest.parse("""{"version":"0.1.0","baseUrl":"https://x/releases/download/v0.1.0/","proto":{"min":1,"max":1},
            "openh264":"2.6.0","targets":{"linux-x86_64":{"file":"linux-x86_64.gz","sha256":"ab","size":9}}}""")
        assertEquals("https://x/releases/download/v0.1.0/linux-x86_64.gz", m.url(m.targets.getValue("linux-x86_64")))
        assertEquals("linux-x86_64", PluginManifest.targetFor("Linux x86_64\n"))
        assertEquals("linux-aarch64", PluginManifest.targetFor("Linux aarch64"))
        assertEquals("macos-universal", PluginManifest.targetFor("Darwin arm64"))
        assertNull(PluginManifest.targetFor("Linux riscv64"))
        assertEquals("windows-x64", PluginManifest.targetFor("Windows AMD64"))
        assertEquals("windows-arm64", PluginManifest.targetFor("Windows ARM64"))
        assertEquals("openh264-2.6.0-win64.dll.bz2", PluginManifest.openh264File("windows-x64", "2.6.0"))
        assertNull(PluginManifest.openh264File("macos-universal", "2.6.0"))
        assertEquals("libopenh264-2.6.0-linux64.8.so.bz2", PluginManifest.openh264File("linux-x86_64", "2.6.0"))
    }
}

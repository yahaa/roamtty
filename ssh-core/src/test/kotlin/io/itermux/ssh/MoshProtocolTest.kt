package io.itermux.ssh

import org.junit.Assert.*
import org.junit.Test

class MoshProtocolTest {
    @Test fun parsesConnectLine() {
        assertEquals(Mosh.Connect(60001, "4NeCCgvZFe2RnPgrcU1PQw"),
            Mosh.parseConnect("\r\nMOSH CONNECT 60001 4NeCCgvZFe2RnPgrcU1PQw\r\n\nmosh-server (mosh 1.4.0)"))
        assertNull(Mosh.parseConnect("The locale requested by LANG=en_US.UTF-8 isn't available here."))
        assertNull(Mosh.parseConnect("MOSH CONNECT 70000 4NeCCgvZFe2RnPgrcU1PQw"))
    }

    @Test fun instructionRoundTripsThroughCompressionAndFragments() {
        val diff = ByteArray(3000) { (it * 31).toByte() }
        val instruction = Mosh.Instruction(3, Mosh.SHUTDOWN_NUM, 7, 3, diff)
        val fragments = Mosh.fragments(9, Mosh.compress(instruction.encode()), 400)
        val assembly = Mosh.Assembly()
        // Out-of-order delivery still reassembles once every fragment has arrived.
        val results = fragments.reversed().map { assembly.add(it) }
        val decoded = Mosh.Instruction.decode(Mosh.decompress(results.last()!!))
        assertTrue(results.dropLast(1).all { it == null })
        assertEquals(listOf(3L, Mosh.SHUTDOWN_NUM, 7L, 3L, Mosh.PROTOCOL_VERSION),
            listOf(decoded.oldNum, decoded.newNum, decoded.ackNum, decoded.throwawayNum, decoded.protocolVersion))
        assertArrayEquals(diff, decoded.diff)
    }

    @Test fun parsesHostMessage() {
        val bytes = Proto.Writer().message(2, Proto.Writer().bytes(4, "hi".toByteArray()))
        val resize = Proto.Writer().message(3, Proto.Writer().varint(5, 80).varint(6, 24))
        val echo = Proto.Writer().message(7, Proto.Writer().varint(8, 5))
        val message = Proto.Writer().message(1, bytes).message(1, resize).message(1, echo).toByteArray()
        val events = Mosh.hostMessage(message)
        assertEquals("hi", (events[0] as Mosh.HostEvent.Bytes).bytes.toString(Charsets.UTF_8))
        assertEquals(Mosh.HostEvent.Resize(80, 24), events[1])
        assertEquals(2, events.size)
    }

    @Test fun cryptoRejectsTamperingAndClientDirection() {
        val crypto = Mosh.Crypto("4NeCCgvZFe2RnPgrcU1PQw")
        val sealed = crypto.seal("hello".toByteArray())
        // Our own (client → server) packets must not be accepted as server packets.
        assertNull(crypto.open(sealed))
        sealed[0] = (sealed[0].toInt() or 0x80).toByte()
        assertNull(crypto.open(sealed))
    }

    @Test fun parsesPluginProbe() {
        assertEquals(setOf(HostPlugin.TMUX), HostPluginProbe.parse("__ITERMUX_TMUX__=/usr/bin/tmux\n__ITERMUX_MOSH__=\n"))
        assertEquals(emptySet<HostPlugin>(), HostPluginProbe.parse("__ITERMUX_TMUX__=\n__ITERMUX_MOSH__=\n"))
        assertNull(HostPluginProbe.parse("sh: not found"))
        assertEquals(setOf(HostPlugin.MOSH, HostPlugin.TMUX), HostPlugin.decode(HostPlugin.encode(setOf(HostPlugin.MOSH, HostPlugin.TMUX))))
        assertNull(HostPlugin.decode(null))
        assertEquals("tmux,mosh", HostPlugin.encode(setOf(HostPlugin.MOSH, HostPlugin.TMUX)))
    }

    @Test fun installerClassifiesResults() {
        assertEquals(HostPluginInstaller.Result.INSTALLED, HostPluginInstaller.classify(0, "ok\n${HostPluginInstaller.DONE}\n"))
        assertEquals(HostPluginInstaller.Result.SUDO_PASSWORD, HostPluginInstaller.classify(3, HostPluginInstaller.SUDO_FAILED))
        assertEquals(HostPluginInstaller.Result.FAILED, HostPluginInstaller.classify(100, "E: Unable to locate package"))
        assertFalse(HostPluginInstaller.script(HostPlugin.MOSH, "a'b\nc").contains("a'b"))
        assertTrue(HostPluginInstaller.script(HostPlugin.MOSH, "a'b").contains("PW='a'\\''b'"))
    }
}

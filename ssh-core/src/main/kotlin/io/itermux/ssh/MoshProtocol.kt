package io.itermux.ssh

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.OCBBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Wire format of mosh's State Synchronization Protocol (protocol version 2), client side.
 * Datagram = 8-byte direction|sequence nonce + AES-128-OCB(timestamps + fragment), and a
 * fragment carries a slice of a zlib-compressed `TransportBuffers.Instruction` protobuf.
 */
internal object Mosh {
    const val PROTOCOL_VERSION = 2L
    /** `uint64(-1)`: the sender is shutting down. */
    const val SHUTDOWN_NUM = -1L
    private const val TO_CLIENT = Long.MIN_VALUE

    /** "MOSH CONNECT <port> <22-char base64 key>" as printed by `mosh-server new`. */
    data class Connect(val port: Int, val key: String)

    private val connectLine = Regex("MOSH CONNECT (\\d{1,5}) ([A-Za-z0-9/+]{22})(?:\\s|$)")

    fun parseConnect(output: String): Connect? = connectLine.find(output)?.let {
        Connect(it.groupValues[1].toInt().takeIf { port -> port in 1..65535 } ?: return null, it.groupValues[2])
    }

    class Crypto(key: String) {
        private val key = Base64.getDecoder().decode("$key==").also { require(it.size == 16) { "Bad mosh key" } }
        private var sequence = 0L

        /** Client → server: direction bit clear. [toClient] plays the server side (tests). */
        fun seal(plain: ByteArray, toClient: Boolean = false): ByteArray {
            val nonce = nonce((sequence++ and Long.MAX_VALUE) or (if (toClient) TO_CLIENT else 0L))
            val cipher = cipher(true, nonce)
            val out = ByteArray(8 + cipher.getOutputSize(plain.size))
            System.arraycopy(nonce, 4, out, 0, 8)
            val n = cipher.processBytes(plain, 0, plain.size, out, 8)
            cipher.doFinal(out, 8 + n)
            return out
        }

        /** Returns null for forged, corrupt or wrong-direction datagrams. [fromClient] plays the server side (tests). */
        fun open(packet: ByteArray, length: Int = packet.size, fromClient: Boolean = false): ByteArray? {
            if (length < 8 + 16) return null
            val directionSeq = ByteBuffer.wrap(packet, 0, 8).long
            if ((directionSeq and TO_CLIENT != 0L) == fromClient) return null
            val cipher = cipher(false, nonce(directionSeq))
            val out = ByteArray(cipher.getOutputSize(length - 8))
            return try {
                val n = cipher.processBytes(packet, 8, length - 8, out, 0)
                cipher.doFinal(out, n)
                out
            } catch (_: InvalidCipherTextException) { null }
        }

        private fun nonce(value: Long) = ByteBuffer.allocate(12).putInt(0).putLong(value).array()
        private fun cipher(encrypt: Boolean, nonce: ByteArray) = OCBBlockCipher(AESEngine.newInstance(), AESEngine.newInstance())
            .apply { init(encrypt, AEADParameters(KeyParameter(key), 128, nonce)) }
    }

    data class Instruction(
        val oldNum: Long, val newNum: Long, val ackNum: Long, val throwawayNum: Long,
        val diff: ByteArray = ByteArray(0), val protocolVersion: Long = PROTOCOL_VERSION,
    ) {
        fun encode(): ByteArray = Proto.Writer().apply {
            varint(1, protocolVersion); varint(2, oldNum); varint(3, newNum)
            varint(4, ackNum); varint(5, throwawayNum)
            if (diff.isNotEmpty()) bytes(6, diff)
        }.toByteArray()

        companion object {
            fun decode(bytes: ByteArray): Instruction {
                var version = 0L; var old = 0L; var new = 0L; var ack = 0L; var throwaway = 0L
                var diff = ByteArray(0)
                Proto.read(bytes) { field, value ->
                    when (field) {
                        1 -> version = value.varint; 2 -> old = value.varint; 3 -> new = value.varint
                        4 -> ack = value.varint; 5 -> throwaway = value.varint; 6 -> diff = value.bytes
                    }
                }
                return Instruction(old, new, ack, throwaway, diff, version)
            }
        }
    }

    /** One element of a `ClientBuffers.UserMessage`. */
    sealed class UserEvent {
        class Keys(val bytes: ByteArray) : UserEvent()
        data class Resize(val width: Int, val height: Int) : UserEvent()

        fun encode(): ByteArray = when (this) {
            is Keys -> Proto.Writer().message(2, Proto.Writer().bytes(4, bytes))
            is Resize -> Proto.Writer().message(3, Proto.Writer().varint(5, width.toLong()).varint(6, height.toLong()))
        }.toByteArray()
    }

    fun userMessage(events: List<ByteArray>): ByteArray =
        Proto.Writer().apply { events.forEach { bytes(1, it) } }.toByteArray()

    sealed class HostEvent {
        class Bytes(val bytes: ByteArray) : HostEvent()
        data class Resize(val width: Int, val height: Int) : HostEvent()
    }

    /** Parses a `HostBuffers.HostMessage`; echo acks (used only for local prediction) are skipped. */
    fun hostMessage(diff: ByteArray): List<HostEvent> {
        val events = mutableListOf<HostEvent>()
        Proto.read(diff) { field, instruction ->
            if (field != 1) return@read
            Proto.read(instruction.bytes) { kind, body ->
                when (kind) {
                    2 -> Proto.read(body.bytes) { f, v -> if (f == 4) events += HostEvent.Bytes(v.bytes) }
                    3 -> {
                        var width = 0; var height = 0
                        Proto.read(body.bytes) { f, v -> if (f == 5) width = v.varint.toInt() else if (f == 6) height = v.varint.toInt() }
                        events += HostEvent.Resize(width, height)
                    }
                }
            }
        }
        return events
    }

    fun compress(bytes: ByteArray): ByteArray {
        val deflater = Deflater()
        try {
            deflater.setInput(bytes); deflater.finish()
            val out = ByteArrayOutputStream(bytes.size / 2 + 16)
            val buffer = ByteArray(4096)
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
            return out.toByteArray()
        } finally { deflater.end() }
    }

    fun decompress(bytes: ByteArray, limit: Int = 16 * 1024 * 1024): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(bytes)
            val out = ByteArrayOutputStream(bytes.size * 4)
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw IllegalArgumentException("Truncated mosh instruction")
                out.write(buffer, 0, n)
                require(out.size() <= limit) { "Mosh instruction too large" }
            }
            return out.toByteArray()
        } finally { inflater.end() }
    }

    /** Fragment = instruction id (8) + final bit | fragment number (2) + payload slice. */
    fun fragments(id: Long, payload: ByteArray, maxPayload: Int): List<ByteArray> {
        val count = maxOf(1, (payload.size + maxPayload - 1) / maxPayload)
        return List(count) { index ->
            val start = index * maxPayload
            val end = minOf(payload.size, start + maxPayload)
            val number = index or (if (index == count - 1) 0x8000 else 0)
            ByteBuffer.allocate(10 + end - start).putLong(id).putShort(number.toShort())
                .put(payload, start, end - start).array()
        }
    }

    /** Reassembles one instruction at a time; a newer id discards a partial older one (as mosh does). */
    class Assembly {
        private var currentId = -1L
        private val parts = HashMap<Int, ByteArray>()
        private var total = -1

        fun add(fragment: ByteArray): ByteArray? {
            if (fragment.size < 10) return null
            val buffer = ByteBuffer.wrap(fragment)
            val id = buffer.long
            val raw = buffer.short.toInt() and 0xFFFF
            val number = raw and 0x7FFF
            if (id != currentId) { currentId = id; parts.clear(); total = -1 }
            parts[number] = fragment.copyOfRange(10, fragment.size)
            if (raw and 0x8000 != 0) total = number + 1
            if (total < 0 || parts.size != total || (0 until total).any { it !in parts }) return null
            val out = ByteArrayOutputStream()
            for (i in 0 until total) out.write(parts.getValue(i))
            parts.clear(); total = -1; currentId = -1
            return out.toByteArray()
        }
    }
}

/** Just enough protobuf for mosh's messages: varint (0) and length-delimited (2) fields. */
internal object Proto {
    class Value(val varint: Long, private val data: ByteArray?) {
        val bytes: ByteArray get() = data ?: ByteArray(0)
    }

    class Writer {
        private val out = ByteArrayOutputStream()
        fun varint(field: Int, value: Long) = apply { raw((field shl 3).toLong()); raw(value) }
        fun bytes(field: Int, value: ByteArray) = apply { raw(((field shl 3) or 2).toLong()); raw(value.size.toLong()); out.write(value) }
        fun message(field: Int, value: Writer) = bytes(field, value.toByteArray())
        fun toByteArray(): ByteArray = out.toByteArray()
        private fun raw(value: Long) {
            var v = value
            while (v and 0x7FL.inv() != 0L) { out.write(((v and 0x7F) or 0x80).toInt()); v = v ushr 7 }
            out.write(v.toInt())
        }
    }

    fun read(bytes: ByteArray, onField: (Int, Value) -> Unit) {
        var pos = 0
        fun varint(): Long {
            var result = 0L; var shift = 0
            while (true) {
                require(pos < bytes.size && shift < 64) { "Malformed protobuf" }
                val b = bytes[pos++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
        while (pos < bytes.size) {
            val key = varint()
            val field = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> onField(field, Value(varint(), null))
                1 -> { require(pos + 8 <= bytes.size) { "Malformed protobuf" }; pos += 8 }
                2 -> {
                    val length = varint()
                    require(length >= 0 && pos + length <= bytes.size) { "Malformed protobuf" }
                    onField(field, Value(0, bytes.copyOfRange(pos, pos + length.toInt())))
                    pos += length.toInt()
                }
                5 -> { require(pos + 4 <= bytes.size) { "Malformed protobuf" }; pos += 4 }
                else -> throw IllegalArgumentException("Unsupported protobuf wire type")
            }
        }
    }
}

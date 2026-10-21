package io.itermux.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * The roamtty-desktop wire protocol (PROTOCOL.md in the plugin repository): a JSON hello line,
 * then `u8 type | u24 len | payload` frames, big endian. The plugin's golden samples in
 * src/test/resources/desktop-proto keep both sides in step.
 */
object DesktopProtocol {
    /** The protocol versions this app speaks. */
    val SUPPORTED = 1..1
    const val MAX_LEN = (1 shl 24) - 1

    const val CONFIG = 0x01
    const val INPUT = 0x02
    const val ACTION = 0x03
    const val REQ = 0x04
    const val ACK = 0x05
    const val CLIP_SET = 0x06
    const val PING = 0x07
    const val PONG = 0x08
    const val VIDEO = 0x81
    const val CURSOR_POS = 0x82
    const val CURSOR_SHAPE = 0x83
    const val STATE = 0x84

    const val REQ_KEYFRAME = 1
    const val REQ_PAUSE = 2
    const val REQ_RESUME = 3
    const val REQ_VIEW_ONLY_ON = 4
    const val REQ_VIEW_ONLY_OFF = 5
    const val REQ_CLIP_GET = 6

    const val ACTION_SHOW_DESKTOP = 1
    const val ACTION_ALL_WINDOWS = 2

    const val VIDEO_KEY = 1
    const val VIDEO_REFINE = 2

    fun frame(type: Int, payload: ByteArray): ByteArray {
        require(payload.size <= MAX_LEN) { "frame too long" }
        val n = payload.size
        return byteArrayOf(type.toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte()) + payload
    }

    /** One frame, or null at a clean end of stream. */
    fun read(input: InputStream): Frame? {
        val data = input as? DataInputStream ?: DataInputStream(input)
        val type = data.read()
        if (type < 0) return null
        val n = (data.readUnsignedByte() shl 16) or (data.readUnsignedByte() shl 8) or data.readUnsignedByte()
        val payload = ByteArray(n)
        try { data.readFully(payload) } catch (e: EOFException) { return null }
        return Frame(type, payload)
    }

    fun write(output: OutputStream, type: Int, payload: ByteArray) { output.write(frame(type, payload)) }

    /** Reads the hello line byte by byte so nothing after it is consumed. */
    fun readLine(input: InputStream, max: Int = 64 * 1024): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (bytes.size() == 0) null else bytes.toString(Charsets.UTF_8.name())
            if (b == '\n'.code) return bytes.toString(Charsets.UTF_8.name())
            if (bytes.size() >= max) return null
            bytes.write(b)
        }
    }

    fun parseStart(line: String): Start = try {
        val json = JSONObject(line)
        if (json.has("error")) Start.Failed(json.getString("error"), json.optString("detail").ifEmpty { null },
            json.optInt("min", 0).takeIf { json.has("min") }, json.optInt("max", 0).takeIf { json.has("max") })
        else Start.Ready(Hello.parse(json))
    } catch (e: org.json.JSONException) { Start.Failed("internal", "bad hello: ${line.take(200)}") }

    // Client messages.
    fun config(values: Map<String, Any>): ByteArray = frame(CONFIG, JSONObject(values).toString().toByteArray())
    fun abs(display: Int, x: Int, y: Int) = input(1) { put(display.toByte()); putShort(x.toShort()); putShort(y.toShort()) }
    fun rel(dx: Int, dy: Int) = input(2) { putShort(dx.coerceIn(-32768, 32767).toShort()); putShort(dy.coerceIn(-32768, 32767).toShort()) }
    fun button(button: Int, down: Boolean) = input(3) { put(button.toByte()); put(if (down) 1 else 0) }
    fun wheel(dx: Int, dy: Int) = input(4) { putShort(dx.coerceIn(-32768, 32767).toShort()); putShort(dy.coerceIn(-32768, 32767).toShort()) }
    fun key(hid: Int, down: Boolean) = input(5) { putShort(hid.toShort()); put(if (down) 1 else 0) }
    fun text(text: String): ByteArray = frame(INPUT, byteArrayOf(6) + text.toByteArray(Charsets.UTF_8))
    fun action(action: Int) = frame(ACTION, byteArrayOf(action.toByte()))
    fun req(req: Int) = frame(REQ, byteArrayOf(req.toByte()))
    fun ack(frame: Long, recvUs: Long, decodedUs: Long) =
        frame(ACK, ByteBuffer.allocate(20).putInt(frame.toInt()).putLong(recvUs).putLong(decodedUs).array())
    fun clipSet(text: String) = frame(CLIP_SET, text.toByteArray(Charsets.UTF_8))
    fun ping(t1: Long) = frame(PING, ByteBuffer.allocate(8).putLong(t1).array())
    fun pong(t1: Long, t2: Long, t3: Long) = frame(PONG, ByteBuffer.allocate(24).putLong(t1).putLong(t2).putLong(t3).array())

    private inline fun input(kind: Int, size: Int = 5, fill: ByteBuffer.() -> Unit): ByteArray {
        val b = ByteBuffer.allocate(1 + size).put(kind.toByte())
        b.fill()
        return frame(INPUT, b.array().copyOf(b.position()))
    }

    /** Decodes a frame from the plugin; null for types this app does not know. */
    fun decode(frame: Frame): ServerMessage? {
        val b = ByteBuffer.wrap(frame.payload)
        return when (frame.type) {
            VIDEO -> ServerMessage.Video(b.int.toLong() and 0xFFFFFFFFL, b.get().toInt() and 0xFF, b.long, b.long,
                b.short.toInt() and 0xFFFF, b.short.toInt() and 0xFFFF, frame.payload, 25)
            CURSOR_POS -> ServerMessage.CursorPos(b.short.toInt() and 0xFFFF, b.short.toInt() and 0xFFFF, b.get().toInt() != 0, b.long)
            CURSOR_SHAPE -> {
                val hash = b.long
                val w = b.short.toInt() and 0xFFFF
                val h = b.short.toInt() and 0xFFFF
                ServerMessage.CursorShape(hash, w, h, b.short.toInt() and 0xFFFF, b.short.toInt() and 0xFFFF,
                    frame.payload.copyOfRange(16, frame.payload.size))
            }
            STATE -> ServerMessage.State(JSONObject(String(frame.payload, Charsets.UTF_8)))
            PING -> ServerMessage.Ping(b.long)
            PONG -> ServerMessage.Pong(b.long, b.long, b.long)
            else -> null
        }
    }
}

class Frame(val type: Int, val payload: ByteArray)

data class DisplayInfo(val id: Int, val width: Int, val height: Int, val scale: Double, val x: Int, val y: Int,
    val primary: Boolean, val name: String)

data class Hello(val proto: Int, val version: String, val os: String, val session: String, val resumeToken: String,
    val displays: List<DisplayInfo>, val encoders: List<String>, val hardware: String, val needs: List<String>) {
    companion object {
        fun parse(json: JSONObject) = Hello(
            proto = json.getInt("proto"), version = json.optString("ver"), os = json.optString("os"),
            session = json.optString("session"), resumeToken = json.optString("resume_token"),
            displays = json.optJSONArray("displays").objects().map {
                DisplayInfo(it.getInt("id"), it.getInt("w"), it.getInt("h"), it.optDouble("scale", 1.0), it.optInt("x"),
                    it.optInt("y"), it.optBoolean("primary"), it.optString("name"))
            },
            encoders = json.optJSONArray("enc").strings(), hardware = json.optString("hw"),
            needs = json.optJSONArray("needs").strings())
    }
}

internal fun JSONArray?.objects(): List<JSONObject> = this?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
internal fun JSONArray?.strings(): List<String> = this?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()

sealed interface Start {
    data class Ready(val hello: Hello) : Start
    /** [code] as in the protocol: proto_unsupported, no_desktop, not_logged_in, permission, … */
    data class Failed(val code: String, val detail: String?, val min: Int? = null, val max: Int? = null) : Start
}

sealed interface ServerMessage {
    /** [data] from [offset] on is the H.264 Annex-B access unit. */
    class Video(val frame: Long, val flags: Int, val captureUs: Long, val encodedUs: Long, val width: Int, val height: Int,
        val data: ByteArray, val offset: Int) : ServerMessage {
        val keyframe get() = flags and DesktopProtocol.VIDEO_KEY != 0
        val refine get() = flags and DesktopProtocol.VIDEO_REFINE != 0
    }
    data class CursorPos(val x: Int, val y: Int, val visible: Boolean, val shape: Long) : ServerMessage
    class CursorShape(val hash: Long, val width: Int, val height: Int, val hotX: Int, val hotY: Int, val rgba: ByteArray) : ServerMessage
    class State(val json: JSONObject) : ServerMessage {
        val kicked get() = json.optBoolean("kicked")
        val clip: String? get() = if (json.has("clip")) json.optString("clip") else null
        val needs: List<String>? get() = json.optJSONArray("needs")?.strings()
        val locked: Boolean? get() = if (json.has("locked")) json.optBoolean("locked") else null
        val error: String? get() = json.optString("error").ifEmpty { null }
    }
    data class Ping(val t1: Long) : ServerMessage
    data class Pong(val t1: Long, val t2: Long, val t3: Long) : ServerMessage
}

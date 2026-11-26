package io.itermux.desktop

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The roamtty-desktop `voice` subcommand wire protocol (V1):
 * a JSON hello line on stdout, then `u8 type | u24 BE len | payload` frames shared with [DesktopProtocol].
 * Voice frame payload integers are little-endian and PCM is mono 16-bit signed little-endian (s16le).
 */
object VoiceProtocol {
    const val PROTO_VERSION = 1
    const val DEFAULT_MIC_PORT = 24713
    const val DEFAULT_IDLE_TIMEOUT_SEC = 300
    const val MIC_SAMPLE_RATE = 16_000
    const val MIC_CHANNELS = 1
    /** Android AudioRecord capture chunk: 40 ms at 16 kHz mono s16le. */
    const val MIC_RECORD_CHUNK_BYTES = 1280
    /** Wire chunk size for MIC_AUDIO: 20 ms at 16 kHz mono s16le (fits in a single UDP shard). */
    const val MIC_FRAME_CHUNK_BYTES = 640
    const val SPK_RATE_MIN = 8_000
    const val SPK_RATE_MAX = 192_000
    const val SPK_ERR_MAX_BYTES = 235

    // Client -> Server voice frames (non-overlapping with DesktopProtocol 0x01..0x08).
    const val MIC_AUDIO = 0x10
    const val SPK_ACK = 0x11

    // Server -> Client voice frames (non-overlapping with DesktopProtocol 0x81..0x85).
    const val MIC_START = 0x90
    const val MIC_STOP = 0x91
    const val SPK_START = 0x92
    const val SPK_AUDIO = 0x93
    const val SPK_CLEAR = 0x94
    const val SPK_STOP = 0x95

    /** CLI arguments for `roamtty-desktop voice`. */
    fun voiceArgs(
        proto: Int = PROTO_VERSION,
        port: Int? = DEFAULT_MIC_PORT,
        resumeSession: String? = null,
        resumeToken: String? = null,
        idleTimeoutSec: Int? = null,
    ): String = buildString {
        append("--proto ").append(proto)
        if (port != null) append(" --port ").append(port)
        if (!resumeSession.isNullOrEmpty() && !resumeToken.isNullOrEmpty()) {
            val cleanSession = resumeSession.filter { it.isLetterOrDigit() || it in "-_" }
            val cleanToken = resumeToken.filter { it.isLetterOrDigit() || it in "+/=-_" }
            if (cleanSession.isNotEmpty() && cleanToken.isNotEmpty()) {
                append(" --resume ").append(cleanSession).append(" --token ").append(cleanToken)
            }
        }
        if (idleTimeoutSec != null) append(" --idle-timeout ").append(idleTimeoutSec.coerceAtLeast(0))
    }

    /** Parses the first stdout JSON line of `roamtty-desktop voice`. */
    fun parseStart(line: String): VoiceStart = try {
        val json = JSONObject(line)
        if (json.has("error")) {
            VoiceStart.Failed(
                code = json.getString("error"),
                detail = json.optString("detail").ifEmpty { null },
                min = json.optInt("min", 0).takeIf { json.has("min") },
                max = json.optInt("max", 0).takeIf { json.has("max") },
            )
        } else {
            VoiceStart.Ready(VoiceHello.parse(json))
        }
    } catch (_: org.json.JSONException) {
        VoiceStart.Failed("internal", "bad hello: ${line.take(200)}")
    } catch (_: IllegalArgumentException) {
        VoiceStart.Failed("internal", "bad hello: ${line.take(200)}")
    }

    // ---- Client -> Server encoders ----

    fun config(values: Map<String, Any>): ByteArray = DesktopProtocol.config(values)
    fun ping(t1: Long): ByteArray = DesktopProtocol.ping(t1)
    fun pong(t1: Long, t2: Long, t3: Long): ByteArray = DesktopProtocol.pong(t1, t2, t3)

    /** `0x10` C->S: `stream_id: u32 LE | raw 16kHz mono s16le PCM`. */
    fun micAudio(streamId: Long, pcm: ByteArray, offset: Int = 0, length: Int = pcm.size - offset): ByteArray {
        require(offset >= 0 && length >= 0 && offset + length <= pcm.size) { "invalid pcm slice" }
        val payload = ByteArray(4 + length)
        ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).putInt(streamId.toInt())
        System.arraycopy(pcm, offset, payload, 4, length)
        return DesktopProtocol.frame(MIC_AUDIO, payload)
    }

    /** `0x11` C->S: `stream_id: u32 LE | ok: u8 (1=OK, 0=ERR) | utf8 err_msg (<=235B, empty if ok=1)`. */
    fun spkAck(streamId: Long, ok: Boolean, error: String = ""): ByteArray {
        val sanitized = error.replace('\r', ' ').replace('\n', ' ').trim().ifEmpty { "speaker error" }
        val errBytes = if (ok) ByteArray(0) else truncateUtf8(sanitized, SPK_ERR_MAX_BYTES)
        val payload = ByteArray(5 + errBytes.size)
        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(streamId.toInt())
        b.put(if (ok) 1.toByte() else 0.toByte())
        if (errBytes.isNotEmpty()) b.put(errBytes)
        return DesktopProtocol.frame(SPK_ACK, payload)
    }

    /** Whether a client-to-server voice frame must use reliable UDP delivery. */
    fun isReliable(frame: ByteArray): Boolean = UdpLink.reliable(frame)

    // ---- Server -> Client decoder ----

    /** Decodes a frame received from `roamtty-desktop voice`; returns null for unknown or truncated frames. */
    fun decode(frame: Frame): VoiceServerMessage? = when (frame.type) {
        MIC_START -> {
            if (frame.payload.size < 9) null else {
                val b = ByteBuffer.wrap(frame.payload).order(ByteOrder.LITTLE_ENDIAN)
                VoiceServerMessage.MicStart(
                    streamId = b.int.toLong() and 0xFFFFFFFFL,
                    sampleRate = b.int,
                    channels = b.get().toInt() and 0xFF,
                )
            }
        }
        MIC_STOP -> {
            if (frame.payload.size < 4) null else {
                val id = ByteBuffer.wrap(frame.payload).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                VoiceServerMessage.MicStop(id)
            }
        }
        SPK_START -> {
            if (frame.payload.size < 9) null else {
                val b = ByteBuffer.wrap(frame.payload).order(ByteOrder.LITTLE_ENDIAN)
                VoiceServerMessage.SpkStart(
                    streamId = b.int.toLong() and 0xFFFFFFFFL,
                    sampleRate = b.int,
                    channels = b.get().toInt() and 0xFF,
                )
            }
        }
        SPK_AUDIO -> {
            if (frame.payload.size < 4) null else {
                val id = ByteBuffer.wrap(frame.payload).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                VoiceServerMessage.SpkAudio(id, frame.payload, 4, frame.payload.size - 4)
            }
        }
        SPK_CLEAR -> {
            if (frame.payload.size < 4) null else {
                val id = ByteBuffer.wrap(frame.payload).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                VoiceServerMessage.SpkClear(id)
            }
        }
        SPK_STOP -> {
            if (frame.payload.size < 4) null else {
                val id = ByteBuffer.wrap(frame.payload).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                VoiceServerMessage.SpkStop(id)
            }
        }
        DesktopProtocol.STATE -> runCatching {
            VoiceServerMessage.State(JSONObject(String(frame.payload, Charsets.UTF_8)))
        }.getOrNull()
        DesktopProtocol.PING -> if (frame.payload.size < 8) null else {
            VoiceServerMessage.Ping(ByteBuffer.wrap(frame.payload).long)
        }
        DesktopProtocol.PONG -> if (frame.payload.size < 24) null else {
            val b = ByteBuffer.wrap(frame.payload)
            VoiceServerMessage.Pong(b.long, b.long, b.long)
        }
        else -> null
    }

    /** Truncates [text] to at most [maxBytes] valid UTF-8 bytes without splitting multi-byte code points. */
    internal fun truncateUtf8(text: String, maxBytes: Int): ByteArray {
        val raw = text.toByteArray(Charsets.UTF_8)
        if (raw.size <= maxBytes) return raw
        var end = maxBytes
        // Back off past UTF-8 continuation bytes (10xxxxxx) and any incomplete leading sequence.
        while (end > 0 && (raw[end].toInt() and 0xC0) == 0x80) end--
        return raw.copyOf(end)
    }
}

data class VoiceHello(
    val proto: Int,
    val version: String,
    val os: String,
    val mode: String,
    val micPort: Int,
    val session: String,
    val resumeToken: String,
    val udp: UdpOffer? = null,
    val codecs: List<String> = emptyList(),
) {
    companion object {
        fun parse(json: JSONObject): VoiceHello {
            val port = json.getInt("mic_port")
            require(port in 1..65535) { "invalid mic_port: $port" }
            val udp = json.optJSONObject("udp")?.let { u ->
                val udpPort = u.getInt("port")
                val key = java.util.Base64.getDecoder().decode(u.getString("key"))
                if (udpPort in 1..65535 && key.size == 16) UdpOffer(udpPort, key) else null
            }
            return VoiceHello(
                proto = json.getInt("proto"),
                version = json.optString("ver"),
                os = json.optString("os"),
                mode = json.optString("mode", "voice"),
                micPort = port,
                session = json.optString("session"),
                resumeToken = json.optString("resume_token"),
                udp = udp,
                codecs = json.optJSONArray("codecs").strings(),
            )
        }
    }
}

sealed interface VoiceStart {
    data class Ready(val hello: VoiceHello) : VoiceStart
    data class Failed(val code: String, val detail: String?, val min: Int? = null, val max: Int? = null) : VoiceStart
}

sealed interface VoiceServerMessage {
    data class MicStart(val streamId: Long, val sampleRate: Int, val channels: Int) : VoiceServerMessage
    data class MicStop(val streamId: Long) : VoiceServerMessage
    data class SpkStart(val streamId: Long, val sampleRate: Int, val channels: Int) : VoiceServerMessage
    class SpkAudio(val streamId: Long, val data: ByteArray, val offset: Int, val length: Int) : VoiceServerMessage
    data class SpkClear(val streamId: Long) : VoiceServerMessage
    data class SpkStop(val streamId: Long) : VoiceServerMessage
    class State(val json: JSONObject) : VoiceServerMessage {
        val kicked: Boolean get() = json.optBoolean("kicked")
        val error: String? get() = json.optString("error").ifEmpty { null }
    }
    data class Ping(val t1: Long) : VoiceServerMessage
    data class Pong(val t1: Long, val t2: Long, val t3: Long) : VoiceServerMessage
}

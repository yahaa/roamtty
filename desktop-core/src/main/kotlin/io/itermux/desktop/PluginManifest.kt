package io.itermux.desktop

import org.json.JSONObject

/**
 * The plugin build this app version is pinned to (assets/desktop-plugin.json, written by
 * scripts/desktop/pin.sh): where to download it and the SHA-256 of each unpacked binary.
 */
data class PluginManifest(val version: String, val baseUrl: String, val protoMin: Int, val protoMax: Int,
    val openh264: String, val targets: Map<String, Target>) {
    data class Target(val file: String, val sha256: String, val size: Long)

    fun url(target: Target) = "${baseUrl.trimEnd('/')}/${target.file}"

    companion object {
        fun parse(text: String): PluginManifest {
            val json = JSONObject(text)
            val proto = json.optJSONObject("proto")
            val targets = json.getJSONObject("targets")
            return PluginManifest(json.getString("version"), json.getString("baseUrl"), proto?.optInt("min", 1) ?: 1,
                proto?.optInt("max", 1) ?: 1, json.optString("openh264"),
                targets.keys().asSequence().associateWith { key ->
                    targets.getJSONObject(key).let { Target(it.getString("file"), it.getString("sha256"), it.optLong("size")) }
                })
        }

        /** The release target for `uname -sm` output, e.g. "Linux x86_64" → linux-x86_64. */
        fun targetFor(uname: String): String? {
            val (system, machine) = uname.trim().split(Regex("\\s+")).let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
            val arch = when (machine.lowercase()) {
                "x86_64", "amd64" -> "x86_64"
                "aarch64", "arm64" -> "aarch64"
                else -> return null
            }
            return when (system.lowercase()) {
                "linux" -> "linux-$arch"
                "darwin" -> "macos-universal"
                // The Windows probe reports "Windows AMD64" / "Windows ARM64" (PROCESSOR_ARCHITECTURE).
                "windows" -> if (arch == "x86_64") "windows-x64" else "windows-arm64"
                else -> null
            }
        }

        /** The OpenH264 file Cisco publishes for a Linux or Windows target (macOS uses VideoToolbox). */
        fun openh264File(target: String, version: String): String? = when (target) {
            "linux-x86_64" -> "libopenh264-$version-linux64.8.so.bz2"
            "linux-aarch64" -> "libopenh264-$version-linux-arm64.8.so.bz2"
            "windows-x64" -> "openh264-$version-win64.dll.bz2"
            "windows-arm64" -> "openh264-$version-win-arm64.dll.bz2"
            else -> null
        }

        const val OPENH264_BASE = "https://ciscobinary.openh264.org"
    }
}

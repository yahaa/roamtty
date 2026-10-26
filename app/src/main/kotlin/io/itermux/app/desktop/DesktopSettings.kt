package io.itermux.app.desktop

import android.content.Context
import androidx.core.content.edit
import io.itermux.app.appSettings
import io.itermux.desktop.PluginManifest
import io.itermux.desktop.PointerMode

/**
 * Remote desktop preferences in the app settings: keep-alive is global,
 * the rest is per host (keys carry the host id).
 */
internal class DesktopSettings(context: Context) {
    private val prefs = context.appSettings()

    /** How long a desktop left in the background stays connected, in minutes: 5, 10 or 30. */
    var keepAliveMinutes: Int
        get() = prefs.getInt("desktop.keepAlive", 10).takeIf { it in KEEP_ALIVE } ?: 10
        set(value) = prefs.edit { putInt("desktop.keepAlive", value) }

    /** The latency, frame rate and bit rate over the picture's top left corner; off until switched on in 操作. */
    var showStats: Boolean
        get() = prefs.getBoolean("desktop.showStats", false)
        set(value) = prefs.edit { putBoolean("desktop.showStats", value) }

    /** Debug and e2e builds only: a base URL that replaces the pinned release, for a plugin not yet published. */
    var pluginBaseUrl: String
        get() = prefs.getString("desktop.pluginBaseUrl", "").orEmpty()
        set(value) = prefs.edit { putString("desktop.pluginBaseUrl", value.trim()) }

    private val testHooks = io.itermux.app.BuildConfig.DEBUG || io.itermux.app.BuildConfig.BUILD_TYPE == "e2e"

    /** Debug and e2e builds: where UDP goes instead of the host's address (the emulator reaches its host at 10.0.2.2). */
    var udpHostOverride: String
        get() = if (testHooks) prefs.getString("desktop.udpHost", "").orEmpty() else ""
        set(value) = prefs.edit { putString("desktop.udpHost", value.trim()) }

    /** Debug and e2e builds: share of UDP datagrams dropped both ways, to exercise FEC and resends. */
    var testLoss: Double
        get() = if (testHooks) prefs.getFloat("desktop.testLoss", 0f).toDouble() else 0.0
        set(value) = prefs.edit { putFloat("desktop.testLoss", value.toFloat()) }

    fun pointerMode(hostId: String): PointerMode =
        PointerMode.entries.firstOrNull { it.name == prefs.getString("desktop.$hostId.mode", null) } ?: PointerMode.TOUCH
    fun setPointerMode(hostId: String, mode: PointerMode) = prefs.edit { putString("desktop.$hostId.mode", mode.name) }

    fun quality(hostId: String): String = prefs.getString("desktop.$hostId.quality", "auto") ?: "auto"
    fun setQuality(hostId: String, quality: String) = prefs.edit { putString("desktop.$hostId.quality", quality) }

    fun fps(hostId: String): Int = prefs.getInt("desktop.$hostId.fps", 60)
    fun setFps(hostId: String, fps: Int) = prefs.edit { putInt("desktop.$hostId.fps", fps) }

    /** The host's sound on the phone; on unless switched off for this host. */
    fun audio(hostId: String): Boolean = prefs.getBoolean("desktop.$hostId.audio", true)
    fun setAudio(hostId: String, on: Boolean) = prefs.edit { putBoolean("desktop.$hostId.audio", on) }

    fun lockOnClose(hostId: String): Boolean = prefs.getBoolean("desktop.$hostId.lock", false)
    fun setLockOnClose(hostId: String, lock: Boolean) = prefs.edit { putBoolean("desktop.$hostId.lock", lock) }

    companion object {
        val KEEP_ALIVE = listOf(5, 10, 30)
        val FPS = listOf(30, 60, 90, 120)

        /** The plugin build this APK is pinned to, from assets/desktop-plugin.json. */
        fun manifest(context: Context): PluginManifest {
            val pinned = PluginManifest.parse(context.assets.open("desktop-plugin.json").bufferedReader().use { it.readText() })
            val override = if (io.itermux.app.BuildConfig.DEBUG || io.itermux.app.BuildConfig.BUILD_TYPE == "e2e") DesktopSettings(context).pluginBaseUrl else ""
            return if (override.isNotEmpty()) pinned.copy(baseUrl = override) else pinned
        }
    }
}

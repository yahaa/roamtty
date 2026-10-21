package io.itermux.app.desktop

import android.content.Context
import androidx.core.content.edit
import io.itermux.app.appSettings
import io.itermux.desktop.PluginManifest
import io.itermux.desktop.PointerMode

/**
 * Remote desktop preferences in the app settings: the experimental switch and keep-alive are global,
 * the rest is per host (keys carry the host id).
 */
internal class DesktopSettings(context: Context) {
    private val prefs = context.appSettings()

    /** Experimental until the UDP transport ships: off by default, and session cards show no button while off. */
    var enabled: Boolean
        get() = prefs.getBoolean("desktop.enabled", false)
        set(value) = prefs.edit { putBoolean("desktop.enabled", value) }

    /** How long a desktop left in the background stays connected, in minutes: 5, 10 or 30. */
    var keepAliveMinutes: Int
        get() = prefs.getInt("desktop.keepAlive", 10).takeIf { it in KEEP_ALIVE } ?: 10
        set(value) = prefs.edit { putInt("desktop.keepAlive", value) }

    /** Debug and e2e builds only: a base URL that replaces the pinned release, for a plugin not yet published. */
    var pluginBaseUrl: String
        get() = prefs.getString("desktop.pluginBaseUrl", "").orEmpty()
        set(value) = prefs.edit { putString("desktop.pluginBaseUrl", value.trim()) }

    fun pointerMode(hostId: String): PointerMode =
        PointerMode.entries.firstOrNull { it.name == prefs.getString("desktop.$hostId.mode", null) } ?: PointerMode.TOUCH
    fun setPointerMode(hostId: String, mode: PointerMode) = prefs.edit { putString("desktop.$hostId.mode", mode.name) }

    fun quality(hostId: String): String = prefs.getString("desktop.$hostId.quality", "auto") ?: "auto"
    fun setQuality(hostId: String, quality: String) = prefs.edit { putString("desktop.$hostId.quality", quality) }

    fun fps(hostId: String): Int = prefs.getInt("desktop.$hostId.fps", 60)
    fun setFps(hostId: String, fps: Int) = prefs.edit { putInt("desktop.$hostId.fps", fps) }

    fun lockOnClose(hostId: String): Boolean = prefs.getBoolean("desktop.$hostId.lock", false)
    fun setLockOnClose(hostId: String, lock: Boolean) = prefs.edit { putBoolean("desktop.$hostId.lock", lock) }

    companion object {
        val KEEP_ALIVE = listOf(5, 10, 30)
        val QUALITIES = listOf("auto", "smooth", "clear", "original")
        val FPS = listOf(30, 60, 90, 120)

        /** The plugin build this APK is pinned to, from assets/desktop-plugin.json. */
        fun manifest(context: Context): PluginManifest {
            val pinned = PluginManifest.parse(context.assets.open("desktop-plugin.json").bufferedReader().use { it.readText() })
            val override = if (io.itermux.app.BuildConfig.DEBUG || io.itermux.app.BuildConfig.BUILD_TYPE == "e2e") DesktopSettings(context).pluginBaseUrl else ""
            return if (override.isNotEmpty()) pinned.copy(baseUrl = override) else pinned
        }
    }
}

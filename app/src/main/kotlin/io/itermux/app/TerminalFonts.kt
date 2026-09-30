package io.itermux.app

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import java.io.File

/**
 * Terminal typeface selection. Bundled fonts are OFL-licensed monospace faces under assets/fonts.
 * Preference key lives in the same SharedPreferences as font size ("terminal-settings").
 */
enum class TerminalFontId(val id: String, val label: String, val assetFile: String?) {
    SYSTEM("system", "系统等宽", null),
    JETBRAINS_MONO("jetbrains_mono", "JetBrains Mono", "fonts/JetBrainsMono-Regular.ttf"),
    FIRA_CODE("fira_code", "Fira Code", "fonts/FiraCode-Regular.ttf"),
    SOURCE_CODE_PRO("source_code_pro", "Source Code Pro", "fonts/SourceCodePro-Regular.otf"),
    IBM_PLEX_MONO("ibm_plex_mono", "IBM Plex Mono", "fonts/IBMPlexMono-Regular.ttf"),
    CASCADIA_MONO("cascadia_mono", "Cascadia Mono", "fonts/CascadiaMono-Regular.ttf"),
    CUSTOM("custom", "自定义字体", null);

    companion object {
        fun from(value: String?): TerminalFontId =
            entries.firstOrNull { it.id == value } ?: DEFAULT

        /** Bundled JetBrains Mono: system Typeface.MONOSPACE is often not truly mono on OEM ROMs. */
        val DEFAULT: TerminalFontId = JETBRAINS_MONO
    }
}

object TerminalFonts {
    private const val PREFS = "terminal-settings"
    private const val KEY_FONT = "terminalFont"
    private const val CUSTOM_DIR = "terminal-fonts"
    private const val CUSTOM_NAME = "custom-font"

    var selection by mutableStateOf(TerminalFontId.DEFAULT)
        private set

    /** Bumps when custom file bytes change so Compose/AndroidView update even if id stays custom. */
    var revision by mutableStateOf(0)
        private set

    private val assetCache = mutableMapOf<String, Typeface>()

    fun initialize(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = TerminalFontId.from(prefs.getString(KEY_FONT, null))
        selection = when {
            stored == TerminalFontId.CUSTOM && !customFontFile(context).isFile -> TerminalFontId.DEFAULT
            else -> stored
        }
        // Persist default explicitly the first time so later analysis sees an intentional choice.
        if (!prefs.contains(KEY_FONT)) {
            prefs.edit { putString(KEY_FONT, selection.id) }
        }
    }

    fun setSelection(context: Context, id: TerminalFontId) {
        if (id == TerminalFontId.CUSTOM && !customFontFile(context).isFile) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_FONT, id.id) }
        selection = id
        revision++
    }

    fun customFontFile(context: Context): File =
        File(File(context.filesDir, CUSTOM_DIR).also { it.mkdirs() }, CUSTOM_NAME)

    fun hasCustomFont(context: Context): Boolean = customFontFile(context).isFile

    fun customFontDisplayName(context: Context): String? {
        val meta = File(customFontFile(context).parentFile, "$CUSTOM_NAME.name")
        return meta.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Copy a user-picked ttf/otf into app-private storage and select it.
     * Returns false if the file cannot be read or is not a plausible font.
     */
    fun importCustomFont(context: Context, uri: Uri, displayName: String?): Boolean {
        return try {
            val dest = customFontFile(context)
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: return false
            if (dest.length() < 1024L || dest.length() > 16L * 1024L * 1024L) {
                dest.delete()
                return false
            }
            // Validate by attempting to load.
            val face = Typeface.createFromFile(dest)
            if (face == null) {
                dest.delete()
                return false
            }
            val name = displayName?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "custom-font"
            File(dest.parentFile, "$CUSTOM_NAME.name").writeText(name)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putString(KEY_FONT, TerminalFontId.CUSTOM.id)
            }
            selection = TerminalFontId.CUSTOM
            revision++
            true
        } catch (_: Exception) {
            customFontFile(context).delete()
            false
        }
    }

    fun clearCustomFont(context: Context) {
        customFontFile(context).delete()
        File(context.filesDir, "$CUSTOM_DIR/$CUSTOM_NAME.name").delete()
        if (selection == TerminalFontId.CUSTOM) {
            setSelection(context, TerminalFontId.DEFAULT)
        } else {
            revision++
        }
    }

    fun resolveTypeface(context: Context): Typeface {
        val id = selection
        return when (id) {
            TerminalFontId.SYSTEM -> Typeface.MONOSPACE
            TerminalFontId.CUSTOM -> {
                val file = customFontFile(context)
                if (file.isFile) {
                    runCatching { Typeface.createFromFile(file) }.getOrNull() ?: fallbackAsset(context)
                } else {
                    fallbackAsset(context)
                }
            }
            else -> loadAsset(context, id.assetFile!!)
        }
    }

    private fun fallbackAsset(context: Context): Typeface =
        loadAsset(context, TerminalFontId.DEFAULT.assetFile!!)

    private fun loadAsset(context: Context, path: String): Typeface {
        assetCache[path]?.let { return it }
        val face = Typeface.createFromAsset(context.assets, path)
        assetCache[path] = face
        return face
    }
}

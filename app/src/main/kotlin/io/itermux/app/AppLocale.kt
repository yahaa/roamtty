package io.itermux.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import java.util.Locale

enum class AppLanguage(val label: String) {
    ZH_CN("简体中文"), ENGLISH("English");

    companion object {
        fun from(value: String?, locale: Locale = Locale.getDefault()) =
            entries.firstOrNull { it.name == value } ?: if (locale.language == "zh") ZH_CN else ENGLISH
    }
}

/** A single observable language choice also covers text outside Compose, such as notifications. */
object AppLocale {
    var language by mutableStateOf(AppLanguage.from(null))
        private set

    fun initialize(context: Context) {
        language = AppLanguage.from(context.appSettings().getString("language", null),
            context.resources.configuration.locales[0] ?: Locale.getDefault())
    }

    fun set(context: Context, value: AppLanguage) {
        context.appSettings().edit { putString("language", value.name) }
        language = value
    }
}

fun tr(chinese: String): String = if (AppLocale.language == AppLanguage.ENGLISH) EnglishText.values[chinese] ?: chinese else chinese

fun trf(pattern: String, vararg args: Any): String = tr(pattern).format(*args)

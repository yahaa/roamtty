package io.itermux.app

import android.content.Context
import android.content.SharedPreferences

/** The app-wide settings: theme, language, launch choices and file browser options. */
internal fun Context.appSettings(): SharedPreferences = getSharedPreferences(APP_SETTINGS, Context.MODE_PRIVATE)

private const val APP_SETTINGS = "app-settings"

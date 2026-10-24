package io.itermux.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/** Puts [text] on the system clipboard as plain text. */
internal fun Context.copyText(text: String, label: String = "Roamtty") =
    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))

/** The clipboard's first item as text, empty when there is nothing on it. */
internal fun Context.clipboardText(): String = getSystemService(ClipboardManager::class.java).primaryClip
    ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()

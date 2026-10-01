package io.itermux.app

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/** Extract image URIs from system ACTION_SEND / SEND_MULTIPLE share intents. */
object ShareImageIntents {
    private const val HANDLED = "io.itermux.app.SHARE_IMAGE_HANDLED"

    fun takeImageUris(intent: Intent?): List<Uri> {
        if (intent == null || intent.getBooleanExtra(HANDLED, false)) return emptyList()
        val uris = imageUris(intent)
        if (uris.isEmpty()) return emptyList()
        intent.putExtra(HANDLED, true)
        return uris
    }

    fun imageUris(intent: Intent): List<Uri> {
        val type = intent.type.orEmpty()
        if (type.isNotEmpty() && !type.startsWith("image/") && type != "*/*") return emptyList()
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                listOfNotNull(uri)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.filterNotNull()
                    .orEmpty()
            }
            else -> emptyList()
        }
    }
}

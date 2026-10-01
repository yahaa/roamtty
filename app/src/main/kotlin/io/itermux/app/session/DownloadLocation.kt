package io.itermux.app.session

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import java.io.File

/** Describe the document actually created, including any filename chosen by the provider. */
internal fun downloadLocation(context: Context, uri: Uri): String = runCatching {
    if (uri.scheme == "file") return@runCatching uri.path ?: uri.toString()
    if (uri.authority == "com.android.externalstorage.documents") {
        val id = DocumentsContract.getDocumentId(uri)
        val volumeId = id.substringBefore(':')
        val root = if (volumeId == "primary") Environment.getExternalStorageDirectory()
        else if (Build.VERSION.SDK_INT >= 30) context.getSystemService(StorageManager::class.java)
            ?.storageVolumes?.firstOrNull { it.uuid.equals(volumeId, ignoreCase = true) }?.directory
        else null
        if (root != null && ':' in id) return@runCatching File(root, id.substringAfter(':')).path
    }
    // Some providers (including cloud drives) have no local filesystem path.
    // Keep their exact document address instead of inventing a path from the remote filename.
    uri.toString()
}.getOrDefault(uri.toString())

package io.itermux.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.edit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The default destination is shared by all hosts and survives process/device restarts. */
internal class DownloadDirectory(context: Context) {
    internal val preferences = context.applicationContext.getSharedPreferences("download-settings", Context.MODE_PRIVATE)
    private val resolver = context.applicationContext.contentResolver
    var uri by mutableStateOf(readUri())
        private set
    var label by mutableStateOf(preferences.getString("label", "").orEmpty())
        private set

    private fun readUri() = preferences.getString("tree", null)?.let(Uri::parse)

    fun refresh() {
        uri = readUri()
        label = preferences.getString("label", "").orEmpty()
    }

    /** Checking the grant alone would miss deleted or read-only directories. */
    suspend fun isAvailable(tree: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            resolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission } && writableName(tree) != null
        }.getOrDefault(false)
    }

    suspend fun save(tree: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val name = writableName(tree) ?: return@runCatching false
            resolver.takePersistableUriPermission(tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (resolver.persistedUriPermissions.none { it.uri == tree && it.isWritePermission }) return@runCatching false
            // Retain earlier grants: another session may still be downloading into that directory.
            preferences.edit(commit = true) { putString("tree", tree.toString()); putString("label", name) }
            true
        }.getOrDefault(false)
    }

    private fun writableName(tree: Uri): String? {
        if (!DocumentsContract.isTreeUri(tree)) return null
        val id = DocumentsContract.getTreeDocumentId(tree)
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        return resolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (!it.moveToFirst() || it.getString(0) != DocumentsContract.Document.MIME_TYPE_DIR ||
                it.getInt(1) and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE == 0) return@use null
            if (tree.authority == "com.android.externalstorage.documents" && id.startsWith("primary:") &&
                id.substringAfter(':').isNotEmpty()) id.substringAfter(':') else it.getString(2)
        }
    }
}

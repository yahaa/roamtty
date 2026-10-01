package io.itermux.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts

// Remote files already have their final names, including any extension. A concrete MIME type
// lets document providers append an extension (e.g. report.pdf.bin for application/octet-stream).
internal class CreateDownloadDocument(private val initialUri: Uri? = null) : ActivityResultContracts.CreateDocument("*/*") {
    override fun createIntent(context: Context, input: String): Intent = super.createIntent(context, input).apply {
        initialUri?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
    }
}

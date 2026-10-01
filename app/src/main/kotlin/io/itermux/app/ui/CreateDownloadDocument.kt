package io.itermux.app.ui

import androidx.activity.result.contract.ActivityResultContracts

// Remote files already have their final names, including any extension. A concrete MIME type
// lets document providers append an extension (e.g. report.pdf.bin for application/octet-stream).
internal class CreateDownloadDocument : ActivityResultContracts.CreateDocument("*/*")

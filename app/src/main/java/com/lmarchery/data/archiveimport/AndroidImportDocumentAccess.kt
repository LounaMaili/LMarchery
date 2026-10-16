package com.lmarchery.data.archiveimport

import android.content.ContentResolver
import android.net.Uri
import java.io.IOException

/** Application resolver only; no file path, metadata trust, or persisted URI permission. */
class AndroidImportDocumentAccess(private val resolver: ContentResolver) : ImportDocumentAccess {
    override fun openInput(destination: String) = Uri.parse(destination).let {
        require(it.scheme == "content") { "A document-provider URI is required." }
        resolver.openInputStream(it) ?: throw IOException("Document input unavailable.")
    }
}

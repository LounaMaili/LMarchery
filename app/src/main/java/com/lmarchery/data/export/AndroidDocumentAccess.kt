package com.lmarchery.data.export

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException

/** Application ContentResolver only. URI comes exclusively from the CreateDocument result. */
class AndroidDocumentAccess(private val resolver: ContentResolver) : ExportDocumentAccess {
    private fun uri(destination: String): Uri = Uri.parse(destination).also {
        require(it.scheme == "content") { "A document-provider URI is required." }
    }
    override fun openOutput(destination: String) = resolver.openOutputStream(uri(destination), "wt")
        ?: throw IOException("Document output unavailable.")
    override fun openInput(destination: String) = resolver.openInputStream(uri(destination))
        ?: throw IOException("Document readback unavailable.")
    override fun displayName(destination: String): String? =
        resolver.query(uri(destination), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
        }
}

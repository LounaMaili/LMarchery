package com.lmarchery.data.archiveimport

import com.lmarchery.core.export.JsonLimits
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.InputStream

fun interface ImportDocumentAccess { fun openInput(destination: String): InputStream }
fun interface ImportDocumentReader { suspend fun read(destination: String): ByteArray }
class ImportReadException(val code: String) : java.io.IOException(code)

/** No metadata-based allocation. Detect oversize with at most one byte beyond the accepted budget. */
class BoundedDocumentReader(private val access: ImportDocumentAccess,
    private val maxBytes: Int = JsonLimits().maxBytes) : ImportDocumentReader {
    init { require(maxBytes in 1..JsonLimits().maxBytes) }
    override suspend fun read(destination: String): ByteArray {
        val context = currentCoroutineContext()
        context.ensureActive()
        val bytes = ByteArrayOutputStream()
        access.openInput(destination).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                context.ensureActive()
                val count = input.read(buffer, 0, minOf(buffer.size, maxBytes - bytes.size() + 1))
                if (count < 0) break
                if (count == 0) {
                    val value = input.read()
                    if (value < 0) break
                    if (bytes.size() == maxBytes) throw ImportReadException("LIMIT")
                    bytes.write(value)
                } else {
                    if (count > maxBytes - bytes.size()) throw ImportReadException("LIMIT")
                    bytes.write(buffer, 0, count)
                }
            }
        } // Close must succeed before the candidate escapes.
        context.ensureActive()
        return bytes.toByteArray()
    }
}

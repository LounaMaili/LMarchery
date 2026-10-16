package com.lmarchery.data.export

import com.lmarchery.feature.export.ExportDocumentWriter
import com.lmarchery.feature.export.ExportReceipt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

interface ExportDocumentAccess {
    fun openOutput(destination: String): OutputStream
    fun openInput(destination: String): InputStream
    fun displayName(destination: String): String?
}

/** Also tested on the JVM with failing streams. Never deletes a document or touches Room. */
class VerifyingDocumentWriter(private val access: ExportDocumentAccess) : ExportDocumentWriter {
    override suspend fun writeAndVerify(destination: String, bytes: ByteArray): ExportReceipt {
        val context = currentCoroutineContext()
        context.ensureActive()
        access.openOutput(destination).use { out ->
            var offset = 0
            while (offset < bytes.size) {
                context.ensureActive()
                val size = minOf(16_384, bytes.size - offset)
                out.write(bytes, offset, size); offset += size
            }
            out.flush()
        } // A close failure prevents success.
        context.ensureActive()
        access.openInput(destination).use { input ->
            val buffer = ByteArray(16_384)
            var offset = 0
            while (true) {
                context.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) {
                    val next = input.read()
                    if (next < 0) break
                    check(offset < bytes.size && bytes[offset++] == next.toByte()) { "Readback differs." }
                } else {
                    check(count <= bytes.size - offset) { "Readback longer than prepared output." }
                    for (i in 0 until count) check(buffer[i] == bytes[offset + i]) { "Readback differs." }
                    offset += count
                }
            }
            check(offset == bytes.size) { "Readback is truncated." }
        }
        context.ensureActive()
        val name = try { access.displayName(destination) } catch (_: Exception) { null }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return ExportReceipt(name ?: destination, bytes.size, digest)
    }
}

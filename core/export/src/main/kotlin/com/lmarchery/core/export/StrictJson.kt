package com.lmarchery.core.export

import com.google.gson.*
import com.google.gson.stream.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class JsonLimits(
    val maxBytes: Int = 16 * 1024 * 1024,
    val maxDepth: Int = 32,
    val maxValues: Int = 200_000,
    val maxArrayElements: Int = 20_000,
    val maxStringChars: Int = 16_384,
) {
    init { require(maxBytes > 0 && maxDepth in 1..64 && maxValues > 0 && maxArrayElements > 0 && maxStringChars > 0) }
}

data class JsonIssue(val code: String, val path: String, val detail: String)
class ArchiveJsonException(val issue: JsonIssue) : IllegalArgumentException("${issue.code} at ${issue.path}: ${issue.detail}")
internal fun invalid(path: String, detail: String, code: String = "INVALID_JSON"): Nothing =
    throw ArchiveJsonException(JsonIssue(code, path, detail))

/** Gson owns the JSON grammar; this bounded token adapter rejects duplicate keys before building objects. */
internal fun parseStrict(bytes: ByteArray, limits: JsonLimits): JsonElement {
    if (bytes.size > limits.maxBytes) invalid("$", "File size limit exceeded.", "LIMIT")
    val text = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) { invalid("$", "Invalid UTF-8.") }
    // UTF-8 without BOM is the file contract; do not let a reader silently consume one.
    if (text.startsWith('\uFEFF')) invalid("$", "UTF-8 BOM is not supported.")
    val reader = JsonReader(StringReader(text)).apply {
        strictness = Strictness.STRICT
        setNestingLimit(limits.maxDepth)
    }
    var count = 0
    fun read(depth: Int): JsonElement {
        val path = reader.path
        if (depth > limits.maxDepth || ++count > limits.maxValues) invalid(path, "Depth/value limit exceeded.", "LIMIT")
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                val result = JsonObject(); val names = hashSetOf<String>()
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    checkText(name, limits, reader.path)
                    if (!names.add(name)) invalid(reader.path, "Duplicate object key.", "DUPLICATE_KEY")
                    if (names.size > 64) invalid(path, "Object property limit exceeded.", "LIMIT")
                    result.add(name, read(depth + 1))
                }
                reader.endObject(); result
            }
            JsonToken.BEGIN_ARRAY -> {
                val result = JsonArray(); reader.beginArray()
                while (reader.hasNext()) {
                    if (result.size() >= limits.maxArrayElements) invalid(path, "Array limit exceeded.", "LIMIT")
                    result.add(read(depth + 1))
                }
                reader.endArray(); result
            }
            JsonToken.STRING -> JsonPrimitive(reader.nextString().also { checkText(it, limits, path) })
            JsonToken.NUMBER -> {
                val raw = reader.nextString()
                if (raw.length > 128) invalid(path, "Number length limit exceeded.", "LIMIT")
                JsonPrimitive(LexicalNumber(raw))
            }
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
            else -> invalid(path, "Expected a JSON value.")
        }
    }
    return try {
        reader.use {
            val result = read(1)
            if (it.peek() != JsonToken.END_DOCUMENT) invalid(it.path, "Trailing content.")
            result
        }
    } catch (e: ArchiveJsonException) { throw e }
    catch (_: Exception) { invalid(reader.path, "Malformed, truncated or excessively nested JSON.") }
}

internal fun checkText(value: String, limits: JsonLimits, path: String) {
    if (value.length > limits.maxStringChars) invalid(path, "String limit exceeded.", "LIMIT")
    var i = 0
    while (i < value.length) {
        val c = value[i++]
        if (c.isHighSurrogate()) {
            if (i >= value.length || !value[i++].isLowSurrogate()) invalid(path, "Unpaired Unicode surrogate.")
        } else if (c.isLowSurrogate()) invalid(path, "Unpaired Unicode surrogate.")
    }
}

private class LexicalNumber(private val text: String) : Number() {
    override fun toByte() = text.toByte()
    override fun toShort() = text.toShort()
    override fun toInt() = text.toInt()
    override fun toLong() = text.toLong()
    override fun toFloat() = text.toFloat()
    override fun toDouble() = text.toDouble()
    override fun toString() = text
}

internal fun writeStrict(tree: JsonElement, limits: JsonLimits): ByteArray {
    val bytes = ByteArrayOutputStream()
    val bounded = object : OutputStream() {
        override fun write(b: Int) {
            if (bytes.size() >= limits.maxBytes) invalid("$", "File size limit exceeded.", "LIMIT")
            bytes.write(b)
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len > limits.maxBytes - bytes.size()) invalid("$", "File size limit exceeded.", "LIMIT")
            bytes.write(b, off, len)
        }
    }
    val utf8 = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    JsonWriter(OutputStreamWriter(bounded, utf8)).use { writer ->
        writer.strictness = Strictness.STRICT
        writer.serializeNulls = true
        writer.setIndent("  ")
        // JsonElement's adapter only; no reflection, unsafe allocation or domain field inference.
        GsonBuilder().serializeNulls().disableHtmlEscaping().create().toJson(tree, writer)
    }
    return bytes.toByteArray()
}

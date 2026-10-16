package com.lmarchery.core.export

import com.google.gson.*
import java.math.BigDecimal

internal fun <T> checkedObject(element: JsonElement, path: String, vararg names: String,
    read: JsonFields.() -> T): T {
    if (!element.isJsonObject) invalid(path, "Expected an object.", "TYPE")
    val obj = element.asJsonObject
    val actual = obj.keySet()
    names.firstOrNull { it !in actual }?.let { invalid("$path.$it", "Required property is absent.", "MISSING_FIELD") }
    actual.firstOrNull { it !in names }?.let { invalid("$path.$it", "Unknown property.", "UNKNOWN_FIELD") }
    return try { JsonFields(obj, path).read() }
    catch (e: ArchiveJsonException) { throw e }
    catch (_: IllegalArgumentException) { invalid(path, "Invalid typed archive value.", "INVALID_DOMAIN_VALUE") }
}

internal class JsonFields(private val obj: JsonObject, private val path: String) {
    private fun primitive(name: String): JsonPrimitive {
        val value = obj[name]
        if (!value.isJsonPrimitive) invalid("$path.$name", "Expected a scalar of the declared type.", "TYPE")
        return value.asJsonPrimitive
    }
    fun string(name: String): String {
        val value = primitive(name)
        if (!value.isString) invalid("$path.$name", "Expected a string.", "TYPE")
        return value.asString
    }
    fun boolean(name: String): Boolean {
        val value = primitive(name)
        if (!value.isBoolean) invalid("$path.$name", "Expected a boolean.", "TYPE")
        return value.asBoolean
    }
    private fun number(name: String): String {
        val value = primitive(name)
        if (!value.isNumber) invalid("$path.$name", "Expected a JSON number.", "TYPE")
        return value.asString
    }
    fun int(name: String): Int {
        val raw = number(name)
        if (!Regex("-?(0|[1-9][0-9]*)").matches(raw)) invalid("$path.$name", "Expected an integer literal.", "TYPE")
        return raw.toIntOrNull() ?: invalid("$path.$name", "Integer outside Int range.")
    }
    fun float(name: String): Float {
        val raw = number(name)
        val value = raw.toFloatOrNull() ?: invalid("$path.$name", "Invalid Float.")
        if (!value.isFinite() || (value == 0f && BigDecimal(raw).signum() != 0))
            invalid("$path.$name", "Float overflow or nonzero underflow.")
        return value
    }
    inline fun <reified T : Enum<T>> enumeration(name: String): T = try { enumValueOf<T>(string(name)) }
        catch (e: ArchiveJsonException) { throw e }
        catch (_: IllegalArgumentException) { invalid("$path.$name", "Unknown enumeration value.", "UNKNOWN_ENUM") }
    fun <T> nullable(name: String, read: () -> T): T? = if (obj[name].isJsonNull) null else read()
    fun <T> child(name: String, read: (JsonElement, String) -> T): T = read(obj[name], "$path.$name")
    fun <T> list(name: String, read: (JsonElement, String) -> T): List<T> {
        val value = obj[name]
        if (!value.isJsonArray) invalid("$path.$name", "Expected an array.", "TYPE")
        return value.asJsonArray.mapIndexed { index, element -> read(element, "$path.$name[$index]") }
    }
}

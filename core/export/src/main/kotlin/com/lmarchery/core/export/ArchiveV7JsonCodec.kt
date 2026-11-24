package com.lmarchery.core.export

import com.google.gson.*
import com.lmarchery.core.model.time.UtcTimestamps
import com.lmarchery.core.model.weather.*
import com.lmarchery.core.scoring.archive.v7.DomainArchiveV7

class ArchiveV7JsonCodec {
    private val limits = JsonLimits()
    private val v6 = ArchiveV6JsonCodec()

    fun encode(value: DomainArchiveV7): ByteArray {
        val root = JsonObject().apply {
            addProperty("schemaVersion", 7)
            add("base", parseStrict(v6.encode(value.base), limits))
            add("estimates", JsonArray().apply { value.estimates.forEach { add(encodeEstimate(it)) } })
        }
        return writeStrict(root, limits).also { check(decode(it) == value) }
    }

    fun decode(bytes: ByteArray) = checkedObject(parseStrict(bytes, limits), "$", "schemaVersion", "base", "estimates") {
        require(int("schemaVersion") == 7)
        DomainArchiveV7(child("base") { obj, _ -> v6.decode(writeStrict(obj, limits)) }, list("estimates", ::decodeEstimate))
    }
}

private fun WeatherPoint.json() = JsonObject().apply {
    addProperty("latitude", latitude)
    addProperty("longitude", longitude)
}

private fun encodeEstimate(row: SessionEstimate) = JsonObject().apply {
    addProperty("uuid", row.uuid)
    addProperty("sessionUuid", row.sessionUuid)
    with(row.estimate) {
        addProperty("provider", provider)
        addProperty("kind", kind.name)
        addProperty("modelSelection", modelSelection)
        addProperty("model", model)
        addProperty("retrievedAt", UtcTimestamps.format(retrievedAt))
        addProperty("issuedAt", issuedAt?.let(UtcTimestamps::format))
        addProperty("validAt", UtcTimestamps.format(validAt))
        addProperty("intervalSeconds", intervalSeconds)
        add("requested", requested.json())
        add("grid", grid?.json() ?: JsonNull.INSTANCE)
        addProperty("windHeightM", windHeightM)
        addProperty("temperatureHeightM", temperatureHeightM)
        addProperty("windMps", windMps)
        addProperty("gustMps", gustMps)
        addProperty("fromTrueDegrees", fromTrueDegrees)
        addProperty("temperatureC", temperatureC)
        addProperty("precipitationMm", precipitationMm)
    }
}

private fun JsonFields.point(name: String) = child(name) { item, path ->
    checkedObject(item, path, "latitude", "longitude") { WeatherPoint(double("latitude"), double("longitude")) }
}

private fun decodeEstimate(item: JsonElement, path: String) = checkedObject(item, path, "uuid", "sessionUuid",
    "provider", "kind", "modelSelection", "model", "retrievedAt", "issuedAt", "validAt", "intervalSeconds",
    "requested", "grid", "windHeightM", "temperatureHeightM", "windMps", "gustMps", "fromTrueDegrees",
    "temperatureC", "precipitationMm") {
    fun number(key: String) = nullable(key) { double(key) }
    SessionEstimate(string("uuid"), string("sessionUuid"), WeatherEstimate(
        string("provider"), enumeration("kind"), nullable("modelSelection") { string("modelSelection") },
        nullable("model") { string("model") }, UtcTimestamps.parse(string("retrievedAt")),
        nullable("issuedAt") { UtcTimestamps.parse(string("issuedAt")) }, UtcTimestamps.parse(string("validAt")),
        nullable("intervalSeconds") { int("intervalSeconds") }, point("requested"), nullable("grid") { point("grid") },
        number("windHeightM"), number("temperatureHeightM"), number("windMps"), number("gustMps"),
        number("fromTrueDegrees"), number("temperatureC"), number("precipitationMm")))
}

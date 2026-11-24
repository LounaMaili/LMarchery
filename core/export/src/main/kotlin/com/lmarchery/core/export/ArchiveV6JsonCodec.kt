package com.lmarchery.core.export

import com.google.gson.*
import com.lmarchery.core.model.time.UtcTimestamps
import com.lmarchery.core.model.weather.*
import com.lmarchery.core.scoring.archive.v6.DomainArchiveV6

class ArchiveV6JsonCodec {
    private val limits = JsonLimits()
    private val v5 = ArchiveV5JsonCodec()

    fun encode(value: DomainArchiveV6): ByteArray {
        val document = JsonObject().apply {
            addProperty("schemaVersion", 6)
            add("base", parseStrict(v5.encode(value.base), limits))
            add("observations", JsonArray().apply { value.observations.forEach { add(observation(it)) } })
        }
        return writeStrict(document, limits).also { check(decode(it) == value) }
    }

    fun decode(bytes: ByteArray): DomainArchiveV6 = checkedObject(parseStrict(bytes, limits), "$", "schemaVersion", "base", "observations") {
        require(int("schemaVersion") == 6)
        val base = child("base") { element, _ -> v5.decode(writeStrict(element, limits)) }
        DomainArchiveV6(base, list("observations", ::read))
    }

    private fun observation(o: WeatherObservation) = JsonObject().apply {
        addProperty("uuid", o.uuid)
        addProperty("sessionUuid", o.sessionUuid)
        add("endUuids", JsonArray().apply { o.scope.endUuids.forEach { add(it) } })
        addProperty("observedAt", UtcTimestamps.format(o.observedAt))
        addProperty("validFrom", UtcTimestamps.format(o.validFrom))
        addProperty("validUntil", o.validUntil?.let(UtcTimestamps::format))
        addProperty("meanMps", o.meanMps)
        addProperty("gustMps", o.gustMps)
        add("from", o.from?.let { JsonObject().apply {
            addProperty("degrees", it.degrees)
            addProperty("north", it.north.name)
            addProperty("uncertaintyDegrees", it.uncertaintyDegrees)
        } } ?: JsonNull.INSTANCE)
        addProperty("variableWind", o.variableWind)
        addProperty("temperatureC", o.temperatureC)
        addProperty("rain", o.rain?.name)
        addProperty("precipitationMm", o.precipitationMm)
        addProperty("origin", o.origin.name)
    }

    private fun read(element: JsonElement, path: String) = checkedObject(element, path, "uuid", "sessionUuid", "endUuids",
        "observedAt", "validFrom", "validUntil", "meanMps", "gustMps", "from", "variableWind", "temperatureC",
        "rain", "precipitationMm", "origin") {
        val ends = list("endUuids") { id, field ->
            if (!id.isJsonPrimitive || !id.asJsonPrimitive.isString) invalid(field, "UUID texte requis.")
            id.asString
        }
        WeatherObservation(string("uuid"), string("sessionUuid"), WeatherScope(ends),
            UtcTimestamps.parse(string("observedAt")), UtcTimestamps.parse(string("validFrom")),
            nullable("validUntil") { UtcTimestamps.parse(string("validUntil")) },
            nullable("meanMps") { double("meanMps") }, nullable("gustMps") { double("gustMps") },
            nullable("from") { child("from") { obj, field -> checkedObject(obj, field, "degrees", "north", "uncertaintyDegrees") {
                WindFrom(double("degrees"), enumeration("north"), nullable("uncertaintyDegrees") { double("uncertaintyDegrees") })
            } } }, boolean("variableWind"), nullable("temperatureC") { double("temperatureC") },
            nullable("rain") { enumeration<RainReport>("rain") }, nullable("precipitationMm") { double("precipitationMm") },
            enumeration("origin"))
    }
}

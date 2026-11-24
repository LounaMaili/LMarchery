package com.lmarchery.core.export

import com.google.gson.*
import com.lmarchery.core.model.place.*
import com.lmarchery.core.model.time.UtcTimestamps
import com.lmarchery.core.scoring.archive.v5.DomainArchiveV5

/** Common fields of the V5 and V8 documents; only V8 accepts sensor provenance. */
internal class PlaceGraphJson(private val assisted: Boolean) {
    private val limits = JsonLimits()
    private val v4 = ArchiveV4JsonCodec()

    fun write(root: JsonObject, archive: DomainArchiveV5) {
        with(root) {
            add("sporting", parseStrict(v4.encode(archive.sporting), limits))
            add("sites", array(archive.places.sites, ::site))
            add("lines", array(archive.places.lines, ::line))
            add("sessionPlaces", array(archive.places.snapshots) { capture ->
                fields("sessionUuid" to json(capture.sessionUuid), "adoptedAt" to json(UtcTimestamps.format(capture.adoptedAt)),
                    "site" to site(capture.site), "line" to capture.line?.let(::line), "orientation" to capture.orientation?.let(::orientation))
            })
        }
    }

    fun read(fields: JsonFields): DomainArchiveV5 = with(fields) {
        val sport = child("sporting") { element, path ->
            when (val result = v4.decode(writeStrict(element, limits))) {
                is ArchiveV4JsonRead.Accepted -> result.archive
                is ArchiveV4JsonRead.Rejected -> invalid(path + result.issue.path.removePrefix("$"), result.issue.detail, result.issue.code)
            }
        }
        val places = ShootingPlaces(list("sites", ::readSite), list("lines", ::readLine),
            list("sessionPlaces") { element, path ->
                checkedObject(element, path, "sessionUuid", "adoptedAt", "site", "line", "orientation") {
                    SessionPlace(string("sessionUuid"), UtcTimestamps.parse(string("adoptedAt")), child("site", ::readSite),
                        nullable("line") { child("line", ::readLine) }, nullable("orientation") { child("orientation", ::readOrientation) })
                }
            })
        DomainArchiveV5(sport, places)
    }

    private fun fields(vararg values: Pair<String, JsonElement?>) = JsonObject().apply {
        values.forEach { (key, value) -> add(key, value ?: JsonNull.INSTANCE) }
    }
    private fun <T> array(values: List<T>, write: (T) -> JsonElement) = JsonArray().apply { values.forEach { add(write(it)) } }
    private fun number(value: Double?) = value?.let(::JsonPrimitive)
    private fun site(value: ShootingSite) = fields("uuid" to json(value.uuid), "name" to json(value.name),
        "description" to json(value.description), "position" to value.position?.let { p ->
            fields("latitude" to number(p.latitude), "longitude" to number(p.longitude),
                "origin" to json(p.origin), "accuracyMetres" to number(p.accuracyMetres))
        })
    private fun line(value: ShootingLine) = fields("uuid" to json(value.uuid), "siteUuid" to json(value.siteUuid),
        "name" to json(value.name), "description" to json(value.description), "active" to json(value.active),
        "orientation" to value.orientation?.let(::orientation))
    private fun orientation(value: LineOrientation): JsonObject {
        require(assisted || value.reading == null) { "Une orientation assistée exige une archive V8." }
        return fields("degrees" to number(value.degrees),
        "reference" to json(value.reference), "origin" to json(value.origin), "uncertaintyDegrees" to number(value.uncertaintyDegrees),
        "confirmedAt" to json(value.confirmedAt?.let(UtcTimestamps::format))).apply {
            if (assisted) add("reading", value.reading?.let { r ->
                fields("magneticDegrees" to number(r.magneticDegrees), "measuredAt" to json(UtcTimestamps.format(r.measuredAt)),
                    "method" to json(r.method), "quality" to json(r.quality))
            } ?: JsonNull.INSTANCE)
        }
    }

    private fun readOrientation(element: JsonElement, path: String): LineOrientation {
        val names = listOf("degrees", "reference", "origin", "uncertaintyDegrees", "confirmedAt") +
            if (assisted) listOf("reading") else emptyList()
        return checkedObject(element, path, *names.toTypedArray()) {
            val origin = enumeration<OrientationOrigin>("origin")
            require(assisted || origin == OrientationOrigin.MANUAL)
            val reading = if (assisted) nullable("reading") {
                child("reading") { item, key -> checkedObject(item, key, "magneticDegrees", "measuredAt", "method", "quality") {
                    CompassReading(double("magneticDegrees"), UtcTimestamps.parse(string("measuredAt")), enumeration("method"), enumeration("quality"))
                } }
            } else null
            LineOrientation(double("degrees"), enumeration("reference"), origin, nullable("uncertaintyDegrees") { double("uncertaintyDegrees") },
                nullable("confirmedAt") { UtcTimestamps.parse(string("confirmedAt")) }, reading)
        }
    }
    private fun readSite(element: JsonElement, path: String) = checkedObject(element, path,
        "uuid", "name", "description", "position") {
        ShootingSite(string("uuid"), string("name"), nullable("description") { string("description") },
            nullable("position") { child("position") { point, pp -> checkedObject(point, pp, "latitude", "longitude", "origin", "accuracyMetres") {
                GeoPosition(double("latitude"), double("longitude"), nullable("origin") { enumeration<ManualOrigin>("origin") },
                    nullable("accuracyMetres") { double("accuracyMetres") })
            } } })
    }
    private fun readLine(element: JsonElement, path: String) = checkedObject(element, path,
        "uuid", "siteUuid", "name", "description", "orientation", "active") {
        ShootingLine(string("uuid"), string("siteUuid"), string("name"), nullable("description") { string("description") },
            nullable("orientation") { child("orientation", ::readOrientation) }, boolean("active"))
    }
}

package com.lmarchery.core.export

import com.google.gson.*
import com.lmarchery.core.model.session.*
import com.lmarchery.core.scoring.archive.v3.*
import com.lmarchery.core.scoring.configured.InitialConfigurationOrigin
import java.math.BigDecimal

/** Property order follows ADR 037 and the V3 DTO declarations; shared records keep their published order. */
internal fun jsonV3(value: ArchiveV3Snapshot): JsonElement = v3Record(
    "schemaVersion" to value.schemaVersion, "scoringContractVersion" to value.scoringContractVersion,
    "profiles" to value.profiles, "sessions" to value.sessions,
    "bows" to value.bows?.map(::bow), "arrowSets" to value.arrowSets?.map(::arrows),
)

private fun v3Json(value: Any?): JsonElement = when (value) {
    is JsonElement -> value
    is List<*> -> JsonArray().also { array -> value.forEach { array.add(v3Json(it)) } }
    is ConfiguredSessionSnapshot -> v3Record(
        "uuid" to value.uuid, "metadata" to value.metadata, "initialConfiguration" to value.initialConfiguration,
        "initialConfigurationOrigin" to value.initialConfigurationOrigin, "status" to value.status,
        "startedAt" to value.startedAt, "endedAt" to value.endedAt, "ends" to value.ends,
        "createdAt" to value.createdAt, "updatedAt" to value.updatedAt,
    )
    is ConfiguredMetadataSnapshot -> v3Record(
        "date" to value.date, "title" to value.title, "plannedEndCount" to value.plannedEndCount,
        "bowUuid" to value.bowUuid, "arrowSetUuid" to value.arrowSetUuid,
        "locationName" to value.locationName, "notes" to value.notes,
    )
    is EndConfigurationSnapshot -> v3Record(
        "targetTypeUuid" to value.targetTypeUuid, "regulatoryEdition" to value.regulatoryEdition,
        "distanceM" to value.distanceM, "arrowCountMode" to value.arrowCountMode,
        "arrowCountPerEnd" to value.arrowCountPerEnd, "isIndoor" to value.isIndoor, "scoringMode" to value.scoringMode,
    )
    is ConfiguredEndSnapshot -> v3Record(
        "sessionUuid" to value.sessionUuid, "end" to value.end, "configuration" to value.configuration, "origin" to value.origin,
    )
    is EndConfigurationOriginSnapshot -> v3Record("kind" to value.kind, "previousEndUuid" to value.previousEndUuid)
    else -> json(value)
}

private fun v3Record(vararg fields: Pair<String, Any?>) = JsonObject().also { obj ->
    fields.forEach { (key, value) -> obj.add(key, v3Json(value)) }
}

internal fun readArchiveV3Snapshot(element: JsonElement, path: String): ArchiveV3Snapshot = checkedObject(element, path,
    "schemaVersion", "scoringContractVersion", "profiles", "sessions", "bows", "arrowSets") {
    ArchiveV3Snapshot(nullable("schemaVersion") { int("schemaVersion") }, nullable("scoringContractVersion") { int("scoringContractVersion") },
        list("profiles", ::readTargetProfileSnapshot), list("sessions", ::readConfiguredSession),
        list("bows", ::readBowSnapshot), list("arrowSets", ::readArrowSetSnapshot))
}

private fun readConfiguredSession(element: JsonElement, path: String): ConfiguredSessionSnapshot = checkedObject(element, path,
    "uuid", "metadata", "initialConfiguration", "initialConfigurationOrigin", "status", "startedAt", "endedAt", "ends", "createdAt", "updatedAt") {
    ConfiguredSessionSnapshot(nullable("uuid") { string("uuid") }, nullable("metadata") { child("metadata", ::readConfiguredMetadata) },
        nullable("initialConfiguration") { child("initialConfiguration", ::readEndConfiguration) },
        nullable("initialConfigurationOrigin") { enumeration<InitialConfigurationOrigin>("initialConfigurationOrigin") },
        nullable("status") { enumeration<SessionStatus>("status") }, nullable("startedAt") { string("startedAt") },
        nullable("endedAt") { string("endedAt") }, list("ends", ::readConfiguredEnd),
        nullable("createdAt") { string("createdAt") }, nullable("updatedAt") { string("updatedAt") })
}

private fun readConfiguredMetadata(element: JsonElement, path: String): ConfiguredMetadataSnapshot = checkedObject(element, path,
    "date", "title", "plannedEndCount", "bowUuid", "arrowSetUuid", "locationName", "notes") {
    ConfiguredMetadataSnapshot(nullable("date") { child("date", ::readCivilDateSnapshot) }, nullable("title") { string("title") },
        nullable("plannedEndCount") { int("plannedEndCount") }, nullable("bowUuid") { string("bowUuid") },
        nullable("arrowSetUuid") { string("arrowSetUuid") }, nullable("locationName") { string("locationName") }, nullable("notes") { string("notes") })
}

private fun readEndConfiguration(element: JsonElement, path: String): EndConfigurationSnapshot = checkedObject(element, path,
    "targetTypeUuid", "regulatoryEdition", "distanceM", "arrowCountMode", "arrowCountPerEnd", "isIndoor", "scoringMode") {
    EndConfigurationSnapshot(nullable("targetTypeUuid") { string("targetTypeUuid") }, nullable("regulatoryEdition") { string("regulatoryEdition") },
        nullable("distanceM") { int("distanceM") }, nullable("arrowCountMode") { enumeration<ArrowCountMode>("arrowCountMode") },
        nullable("arrowCountPerEnd") { int("arrowCountPerEnd") }, nullable("isIndoor") { boolean("isIndoor") },
        nullable("scoringMode") { enumeration<ScoringMode>("scoringMode") })
}

private fun readConfiguredEnd(element: JsonElement, path: String): ConfiguredEndSnapshot = checkedObject(element, path,
    "sessionUuid", "end", "configuration", "origin") {
    ConfiguredEndSnapshot(nullable("sessionUuid") { string("sessionUuid") }, nullable("end") { child("end", ::readEndSnapshot) },
        nullable("configuration") { child("configuration", ::readEndConfiguration) }, nullable("origin") { child("origin", ::readEndOrigin) })
}

private fun readEndOrigin(element: JsonElement, path: String): EndConfigurationOriginSnapshot = checkedObject(element, path,
    "kind", "previousEndUuid") {
    EndConfigurationOriginSnapshot(nullable("kind") { enumeration<EndConfigurationOriginKind>("kind") },
        nullable("previousEndUuid") { string("previousEndUuid") })
}

/** V3 decimal policy: accept decimal equivalents of Float.toString(), never silently round extra precision.
 * Shared V1/V2 numeric readers and their historical acceptance rules remain untouched. */
internal fun validateV3FloatLexemes(tree: JsonElement) {
    val fields = setOf("x", "y", "innerTenRadiusNormalized", "innerRadiusNormalized", "outerRadiusNormalized",
        "radiusNormalized", "distanceFromCenter", "drawWeightLbs")
    fun visit(value: JsonElement, path: String) {
        if (value.isJsonObject) value.asJsonObject.entrySet().forEach { (key, child) ->
            val p = "$path.$key"
            if (key in fields && !child.isJsonNull) {
                // Called only after strict typed mapping, hence this is a finite Float number.
                val raw = child.asString
                val equivalent = try { BigDecimal(raw).compareTo(BigDecimal(raw.toFloat().toString())) == 0 }
                catch (_: NumberFormatException) { false }
                catch (_: ArithmeticException) { false }
                if (!equivalent)
                    invalid(p, "Decimal would lose precision when represented as a Float.", "FLOAT_PRECISION")
            }
            visit(child, p)
        } else if (value.isJsonArray) value.asJsonArray.forEachIndexed { i, child -> visit(child, "$path[$i]") }
    }
    visit(tree, "$")
}

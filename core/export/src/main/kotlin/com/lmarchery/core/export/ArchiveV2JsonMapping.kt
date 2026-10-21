package com.lmarchery.core.export

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.lmarchery.core.scoring.archive.*

/** V2 adds explicit equipment arrays; the V1 mapping and its property order remain unchanged. */
internal fun jsonV2(value: ArchiveV2Snapshot): JsonElement = record(
    "schemaVersion" to value.schemaVersion,
    "scoringContractVersion" to value.scoringContractVersion,
    "profiles" to value.profiles,
    "sessions" to value.sessions,
    "bows" to value.bows?.let { values -> JsonArray().also { array -> values.forEach { array.add(bow(it)) } } },
    "arrowSets" to value.arrowSets?.let { values -> JsonArray().also { array -> values.forEach { array.add(arrows(it)) } } },
)

internal fun bow(value: BowSnapshot) = record(
    "uuid" to value.uuid, "name" to value.name, "type" to value.type, "drawWeightLbs" to value.drawWeightLbs,
    "brand" to value.brand, "model" to value.model, "notes" to value.notes,
    "createdAt" to value.createdAt, "updatedAt" to value.updatedAt,
)
internal fun arrows(value: ArrowSetSnapshot) = record(
    "uuid" to value.uuid, "name" to value.name, "spine" to value.spine, "length" to value.length,
    "pointWeight" to value.pointWeight, "nock" to value.nock, "vanes" to value.vanes,
    "material" to value.material, "notes" to value.notes, "createdAt" to value.createdAt, "updatedAt" to value.updatedAt,
)
private fun record(vararg fields: Pair<String, Any?>): JsonObject = JsonObject().also { obj ->
    fields.forEach { (key, value) -> obj.add(key, if (value is JsonElement) value else json(value)) }
}

internal fun readArchiveV2Snapshot(element: JsonElement, path: String): ArchiveV2Snapshot = checkedObject(element, path,
    "schemaVersion", "scoringContractVersion", "profiles", "sessions", "bows", "arrowSets") {
    ArchiveV2Snapshot(
        nullable("schemaVersion") { int("schemaVersion") },
        nullable("scoringContractVersion") { int("scoringContractVersion") },
        list("profiles", ::readTargetProfileSnapshot), list("sessions", ::readSessionSnapshot),
        list("bows", ::readBowSnapshot), list("arrowSets", ::readArrowSetSnapshot),
    )
}
internal fun readBowSnapshot(element: JsonElement, path: String): BowSnapshot = checkedObject(element, path,
    "uuid", "name", "type", "drawWeightLbs", "brand", "model", "notes", "createdAt", "updatedAt") {
    BowSnapshot(nullable("uuid") { string("uuid") }, nullable("name") { string("name") },
        nullable("type") { string("type") }, nullable("drawWeightLbs") { float("drawWeightLbs") },
        nullable("brand") { string("brand") }, nullable("model") { string("model") }, nullable("notes") { string("notes") },
        nullable("createdAt") { string("createdAt") }, nullable("updatedAt") { string("updatedAt") })
}
internal fun readArrowSetSnapshot(element: JsonElement, path: String): ArrowSetSnapshot = checkedObject(element, path,
    "uuid", "name", "spine", "length", "pointWeight", "nock", "vanes", "material", "notes", "createdAt", "updatedAt") {
    ArrowSetSnapshot(nullable("uuid") { string("uuid") }, nullable("name") { string("name") },
        nullable("spine") { string("spine") }, nullable("length") { string("length") },
        nullable("pointWeight") { string("pointWeight") }, nullable("nock") { string("nock") },
        nullable("vanes") { string("vanes") }, nullable("material") { string("material") }, nullable("notes") { string("notes") },
        nullable("createdAt") { string("createdAt") }, nullable("updatedAt") { string("updatedAt") })
}

/** Version dispatch only. The selected complete object adapter still checks every key and type. */
internal fun readSchemaVersion(element: JsonElement): Int {
    if (!element.isJsonObject) invalid("$", "Expected an archive object.", "TYPE")
    val root = element.asJsonObject
    if (!root.has("schemaVersion") || root["schemaVersion"].isJsonNull)
        invalid("$.schemaVersion", "Required version is absent.", "MISSING_FIELD")
    return JsonFields(root, "$").int("schemaVersion")
}

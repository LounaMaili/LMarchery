package com.lmarchery.core.export

import com.google.gson.*
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.*
import com.lmarchery.core.model.session.*
import com.lmarchery.core.model.target.*
import com.lmarchery.core.scoring.archive.*

/** Exact property names/order of the typed V1 contract. All properties are explicit, including nulls. */
internal fun json(value: Any?): JsonElement = when (value) {
    null -> JsonNull.INSTANCE
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Int -> JsonPrimitive(value)
    is Float -> { require(value.isFinite()); JsonPrimitive(value) }
    is Enum<*> -> JsonPrimitive(value.name)
    is List<*> -> JsonArray().also { array -> value.forEach { array.add(json(it)) } }
    is ArchiveSnapshot -> obj(
        "schemaVersion" to value.schemaVersion,
        "scoringContractVersion" to value.scoringContractVersion,
        "profiles" to value.profiles,
        "sessions" to value.sessions
    )
    is TargetProfileSnapshot -> obj(
        "definition" to value.definition,
        "regulatoryEdition" to value.regulatoryEdition
    )
    is TargetType -> obj(
        "uuid" to value.uuid,
        "name" to value.name,
        "discipline" to value.discipline,
        "diameterCm" to value.diameterCm,
        "maxScore" to value.maxScore,
        "minScore" to value.minScore,
        "defaultArrowCount" to value.defaultArrowCount,
        "defaultDistanceM" to value.defaultDistanceM,
        "isBuiltIn" to value.isBuiltIn,
        "xRingEnabled" to value.xRingEnabled,
        "xRingLabel" to value.xRingLabel,
        "innerTenRadiusNormalized" to value.innerTenRadiusNormalized,
        "zones" to value.zones
    )
    is TargetZone -> obj(
        "score" to value.score,
        "innerRadiusNormalized" to value.innerRadiusNormalized,
        "outerRadiusNormalized" to value.outerRadiusNormalized,
        "colorName" to value.colorName,
        "colorHex" to value.colorHex
    )
    is CivilDateSnapshot -> obj(
        "localDateTime" to value.localDateTime,
        "zoneId" to value.zoneId,
        "offset" to value.offset
    )
    is NormalizedPoint -> obj(
        "x" to value.x,
        "y" to value.y
    )
    is BoundaryValue -> obj(
        "score" to value.score,
        "isX" to value.isX
    )
    is BoundaryReference -> obj(
        "type" to value.type,
        "radiusNormalized" to value.radiusNormalized
    )
    is ScoreAssertions -> obj(
        "distanceFromCenter" to value.distanceFromCenter,
        "scoreCalculated" to value.scoreCalculated,
        "scoreFinal" to value.scoreFinal,
        "geometricIsX" to value.geometricIsX,
        "isX" to value.isX,
        "isMiss" to value.isMiss,
        "isCordDecisionManual" to value.isCordDecisionManual
    )
    is DecisionSnapshot -> obj(
        "kind" to value.kind,
        "selected" to value.selected,
        "decidedAt" to value.decidedAt,
        "boundary" to value.boundary,
        "reason" to value.reason,
        "assertions" to value.assertions
    )
    is RepositionSnapshot -> obj(
        "impactUuid" to value.impactUuid,
        "impactCreatedAt" to value.impactCreatedAt,
        "inputMode" to value.inputMode,
        "previousPoint" to value.previousPoint,
        "previousDecision" to value.previousDecision,
        "previousUpdatedAt" to value.previousUpdatedAt,
        "newPoint" to value.newPoint,
        "changedAt" to value.changedAt,
        "reason" to value.reason
    )
    is ImpactSnapshot -> obj(
        "uuid" to value.uuid,
        "index" to value.index,
        "targetTypeUuid" to value.targetTypeUuid,
        "point" to value.point,
        "inputMode" to value.inputMode,
        "note" to value.note,
        "createdAt" to value.createdAt,
        "updatedAt" to value.updatedAt,
        "decision" to value.decision,
        "repositions" to value.repositions
    )
    is EndSnapshot -> obj(
        "uuid" to value.uuid,
        "index" to value.index,
        "status" to value.status,
        "notes" to value.notes,
        "createdAt" to value.createdAt,
        "updatedAt" to value.updatedAt,
        "validatedAt" to value.validatedAt,
        "impacts" to value.impacts
    )
    is SessionSnapshot -> obj(
        "uuid" to value.uuid,
        "date" to value.date,
        "status" to value.status,
        "startedAt" to value.startedAt,
        "endedAt" to value.endedAt,
        "plannedEndCount" to value.plannedEndCount,
        "title" to value.title,
        "targetTypeUuid" to value.targetTypeUuid,
        "distanceM" to value.distanceM,
        "arrowCountMode" to value.arrowCountMode,
        "arrowCountPerEnd" to value.arrowCountPerEnd,
        "isIndoor" to value.isIndoor,
        "scoringMode" to value.scoringMode,
        "bowUuid" to value.bowUuid,
        "arrowSetUuid" to value.arrowSetUuid,
        "locationName" to value.locationName,
        "notes" to value.notes,
        "ends" to value.ends,
        "createdAt" to value.createdAt,
        "updatedAt" to value.updatedAt
    )
    else -> error("Unsupported archive JSON value type.")
}

private fun obj(vararg fields: Pair<String, Any?>) = JsonObject().also { o -> fields.forEach { (k, v) -> o.add(k, json(v)) } }

internal fun readArchiveSnapshot(element: JsonElement, path: String): ArchiveSnapshot = checkedObject(element, path,
    "schemaVersion", "scoringContractVersion", "profiles", "sessions") {
    ArchiveSnapshot(
        schemaVersion = nullable("schemaVersion") { int("schemaVersion") },
        scoringContractVersion = nullable("scoringContractVersion") { int("scoringContractVersion") },
        profiles = list("profiles", ::readTargetProfileSnapshot),
        sessions = list("sessions", ::readSessionSnapshot),
    )
}

internal fun readTargetProfileSnapshot(element: JsonElement, path: String): TargetProfileSnapshot = checkedObject(element, path,
    "definition", "regulatoryEdition") {
    TargetProfileSnapshot(
        definition = nullable("definition") { child("definition", ::readTargetType) },
        regulatoryEdition = nullable("regulatoryEdition") { string("regulatoryEdition") },
    )
}

internal fun readTargetType(element: JsonElement, path: String): TargetType = checkedObject(element, path,
    "uuid", "name", "discipline", "diameterCm", "maxScore", "minScore", "defaultArrowCount", "defaultDistanceM", "isBuiltIn", "xRingEnabled", "xRingLabel", "innerTenRadiusNormalized", "zones") {
    TargetType(
        uuid = string("uuid"),
        name = string("name"),
        discipline = string("discipline"),
        diameterCm = int("diameterCm"),
        maxScore = int("maxScore"),
        minScore = int("minScore"),
        defaultArrowCount = int("defaultArrowCount"),
        defaultDistanceM = int("defaultDistanceM"),
        isBuiltIn = boolean("isBuiltIn"),
        xRingEnabled = boolean("xRingEnabled"),
        xRingLabel = string("xRingLabel"),
        innerTenRadiusNormalized = float("innerTenRadiusNormalized"),
        zones = list("zones", ::readTargetZone),
    )
}

internal fun readTargetZone(element: JsonElement, path: String): TargetZone = checkedObject(element, path,
    "score", "innerRadiusNormalized", "outerRadiusNormalized", "colorName", "colorHex") {
    TargetZone(
        score = int("score"),
        innerRadiusNormalized = float("innerRadiusNormalized"),
        outerRadiusNormalized = float("outerRadiusNormalized"),
        colorName = string("colorName"),
        colorHex = nullable("colorHex") { string("colorHex") },
    )
}

internal fun readCivilDateSnapshot(element: JsonElement, path: String): CivilDateSnapshot = checkedObject(element, path,
    "localDateTime", "zoneId", "offset") {
    CivilDateSnapshot(
        localDateTime = nullable("localDateTime") { string("localDateTime") },
        zoneId = nullable("zoneId") { string("zoneId") },
        offset = nullable("offset") { string("offset") },
    )
}

internal fun readNormalizedPoint(element: JsonElement, path: String): NormalizedPoint = checkedObject(element, path,
    "x", "y") {
    NormalizedPoint(
        x = float("x"),
        y = float("y"),
    )
}

internal fun readBoundaryValue(element: JsonElement, path: String): BoundaryValue = checkedObject(element, path,
    "score", "isX") {
    BoundaryValue(
        score = int("score"),
        isX = boolean("isX"),
    )
}

internal fun readBoundaryReference(element: JsonElement, path: String): BoundaryReference = checkedObject(element, path,
    "type", "radiusNormalized") {
    BoundaryReference(
        type = enumeration<BoundaryType>("type"),
        radiusNormalized = float("radiusNormalized"),
    )
}

internal fun readScoreAssertions(element: JsonElement, path: String): ScoreAssertions = checkedObject(element, path,
    "distanceFromCenter", "scoreCalculated", "scoreFinal", "geometricIsX", "isX", "isMiss", "isCordDecisionManual") {
    ScoreAssertions(
        distanceFromCenter = nullable("distanceFromCenter") { float("distanceFromCenter") },
        scoreCalculated = nullable("scoreCalculated") { int("scoreCalculated") },
        scoreFinal = nullable("scoreFinal") { int("scoreFinal") },
        geometricIsX = nullable("geometricIsX") { boolean("geometricIsX") },
        isX = nullable("isX") { boolean("isX") },
        isMiss = nullable("isMiss") { boolean("isMiss") },
        isCordDecisionManual = nullable("isCordDecisionManual") { boolean("isCordDecisionManual") },
    )
}

internal fun readDecisionSnapshot(element: JsonElement, path: String): DecisionSnapshot = checkedObject(element, path,
    "kind", "selected", "decidedAt", "boundary", "reason", "assertions") {
    DecisionSnapshot(
        kind = nullable("kind") { enumeration<ScoreDecisionKind>("kind") },
        selected = nullable("selected") { child("selected", ::readBoundaryValue) },
        decidedAt = nullable("decidedAt") { string("decidedAt") },
        boundary = nullable("boundary") { child("boundary", ::readBoundaryReference) },
        reason = nullable("reason") { string("reason") },
        assertions = nullable("assertions") { child("assertions", ::readScoreAssertions) },
    )
}

internal fun readRepositionSnapshot(element: JsonElement, path: String): RepositionSnapshot = checkedObject(element, path,
    "impactUuid", "impactCreatedAt", "inputMode", "previousPoint", "previousDecision", "previousUpdatedAt", "newPoint", "changedAt", "reason") {
    RepositionSnapshot(
        impactUuid = string("impactUuid"),
        impactCreatedAt = nullable("impactCreatedAt") { string("impactCreatedAt") },
        inputMode = enumeration<InputMode>("inputMode"),
        previousPoint = child("previousPoint", ::readNormalizedPoint),
        previousDecision = nullable("previousDecision") { child("previousDecision", ::readDecisionSnapshot) },
        previousUpdatedAt = nullable("previousUpdatedAt") { string("previousUpdatedAt") },
        newPoint = child("newPoint", ::readNormalizedPoint),
        changedAt = nullable("changedAt") { string("changedAt") },
        reason = nullable("reason") { string("reason") },
    )
}

internal fun readImpactSnapshot(element: JsonElement, path: String): ImpactSnapshot = checkedObject(element, path,
    "uuid", "index", "targetTypeUuid", "point", "inputMode", "note", "createdAt", "updatedAt", "decision", "repositions") {
    ImpactSnapshot(
        uuid = string("uuid"),
        index = int("index"),
        targetTypeUuid = string("targetTypeUuid"),
        point = child("point", ::readNormalizedPoint),
        inputMode = enumeration<InputMode>("inputMode"),
        note = nullable("note") { string("note") },
        createdAt = nullable("createdAt") { string("createdAt") },
        updatedAt = nullable("updatedAt") { string("updatedAt") },
        decision = nullable("decision") { child("decision", ::readDecisionSnapshot) },
        repositions = list("repositions", ::readRepositionSnapshot),
    )
}

internal fun readEndSnapshot(element: JsonElement, path: String): EndSnapshot = checkedObject(element, path,
    "uuid", "index", "status", "notes", "createdAt", "updatedAt", "validatedAt", "impacts") {
    EndSnapshot(
        uuid = string("uuid"),
        index = int("index"),
        status = enumeration<EndStatus>("status"),
        notes = nullable("notes") { string("notes") },
        createdAt = nullable("createdAt") { string("createdAt") },
        updatedAt = nullable("updatedAt") { string("updatedAt") },
        validatedAt = nullable("validatedAt") { string("validatedAt") },
        impacts = list("impacts", ::readImpactSnapshot),
    )
}

internal fun readSessionSnapshot(element: JsonElement, path: String): SessionSnapshot = checkedObject(element, path,
    "uuid", "date", "status", "startedAt", "endedAt", "plannedEndCount", "title", "targetTypeUuid", "distanceM", "arrowCountMode", "arrowCountPerEnd", "isIndoor", "scoringMode", "bowUuid", "arrowSetUuid", "locationName", "notes", "ends", "createdAt", "updatedAt") {
    SessionSnapshot(
        uuid = string("uuid"),
        date = nullable("date") { child("date", ::readCivilDateSnapshot) },
        status = enumeration<SessionStatus>("status"),
        startedAt = nullable("startedAt") { string("startedAt") },
        endedAt = nullable("endedAt") { string("endedAt") },
        plannedEndCount = nullable("plannedEndCount") { int("plannedEndCount") },
        title = string("title"),
        targetTypeUuid = string("targetTypeUuid"),
        distanceM = int("distanceM"),
        arrowCountMode = enumeration<ArrowCountMode>("arrowCountMode"),
        arrowCountPerEnd = nullable("arrowCountPerEnd") { int("arrowCountPerEnd") },
        isIndoor = boolean("isIndoor"),
        scoringMode = enumeration<ScoringMode>("scoringMode"),
        bowUuid = nullable("bowUuid") { string("bowUuid") },
        arrowSetUuid = nullable("arrowSetUuid") { string("arrowSetUuid") },
        locationName = nullable("locationName") { string("locationName") },
        notes = nullable("notes") { string("notes") },
        ends = list("ends", ::readEndSnapshot),
        createdAt = nullable("createdAt") { string("createdAt") },
        updatedAt = nullable("updatedAt") { string("updatedAt") },
    )
}

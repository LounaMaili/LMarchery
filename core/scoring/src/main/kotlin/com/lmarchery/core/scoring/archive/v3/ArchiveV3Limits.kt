package com.lmarchery.core.scoring.archive.v3

import com.lmarchery.core.scoring.archive.DecisionSnapshot

/** DTO budgets, not JSON byte/depth/value budgets: a future bounded codec must enforce those too. */
data class ArchiveV3Limits(
    val maxCollectionElements: Int = 20_000,
    val maxTotalElements: Long = 200_000,
    val maxTextLength: Int = 16_384,
) {
    init { require(maxCollectionElements > 0 && maxTotalElements > 0 && maxTextLength > 0) }
}

internal fun validateV3Budgets(snapshot: ArchiveV3Snapshot, limits: ArchiveV3Limits) {
    var total = 0L
    fun collection(size: Int?, path: String) {
        if (size == null) return // Required-field diagnostics belong to the structural validator.
        total = try { Math.addExact(total, size.toLong()) } catch (_: ArithmeticException) {
            v3Fail(ArchiveV3IssueCode.LIMIT, path, "Total collection budget overflow.")
        }
        if (size > limits.maxCollectionElements || total > limits.maxTotalElements)
            v3Fail(ArchiveV3IssueCode.LIMIT, path, "Collection budget exceeded; nothing was truncated.")
    }
    fun text(value: String?, path: String) {
        if (value != null && value.length > limits.maxTextLength)
            v3Fail(ArchiveV3IssueCode.LIMIT, path, "Text budget exceeded; nothing was truncated.")
        if (value == null) return
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length || !Character.isLowSurrogate(value[i + 1]))
                    v3Fail(ArchiveV3IssueCode.INVALID_DOMAIN_VALUE, path, "Unpaired UTF-16 surrogate; no replacement is allowed.")
                i++
            } else if (Character.isLowSurrogate(c))
                v3Fail(ArchiveV3IssueCode.INVALID_DOMAIN_VALUE, path, "Unpaired UTF-16 surrogate; no replacement is allowed.")
            i++
        }
    }
    fun decision(value: DecisionSnapshot?, path: String) {
        text(value?.decidedAt, "$path.decidedAt"); text(value?.reason, "$path.reason")
    }
    fun configuration(value: EndConfigurationSnapshot?, path: String) {
        text(value?.targetTypeUuid, "$path.targetTypeUuid"); text(value?.regulatoryEdition, "$path.regulatoryEdition")
    }
    collection(snapshot.profiles?.size, "profiles"); collection(snapshot.sessions?.size, "sessions")
    collection(snapshot.bows?.size, "bows"); collection(snapshot.arrowSets?.size, "arrowSets")
    snapshot.profiles?.forEachIndexed { i, p ->
        val path = "profiles[$i]"; text(p.regulatoryEdition, "$path.regulatoryEdition")
        p.definition?.let { t ->
            text(t.uuid, "$path.definition.uuid"); text(t.name, "$path.definition.name")
            text(t.discipline, "$path.definition.discipline"); text(t.xRingLabel, "$path.definition.xRingLabel")
            collection(t.zones.size, "$path.definition.zones")
            t.zones.forEachIndexed { j, z -> text(z.colorName, "$path.definition.zones[$j].colorName"); text(z.colorHex, "$path.definition.zones[$j].colorHex") }
        }
    }
    snapshot.bows?.forEachIndexed { i, b ->
        val p = "bows[$i]"
        listOf("uuid" to b.uuid, "name" to b.name, "type" to b.type, "brand" to b.brand, "model" to b.model,
            "notes" to b.notes, "createdAt" to b.createdAt, "updatedAt" to b.updatedAt).forEach { (k, v) -> text(v, "$p.$k") }
    }
    snapshot.arrowSets?.forEachIndexed { i, a ->
        val p = "arrowSets[$i]"
        listOf("uuid" to a.uuid, "name" to a.name, "spine" to a.spine, "length" to a.length, "pointWeight" to a.pointWeight,
            "nock" to a.nock, "vanes" to a.vanes, "material" to a.material, "notes" to a.notes,
            "createdAt" to a.createdAt, "updatedAt" to a.updatedAt).forEach { (k, v) -> text(v, "$p.$k") }
    }
    snapshot.sessions?.forEachIndexed { i, s ->
        val p = "sessions[$i]"
        listOf("uuid" to s.uuid, "startedAt" to s.startedAt, "endedAt" to s.endedAt,
            "createdAt" to s.createdAt, "updatedAt" to s.updatedAt).forEach { (k, v) -> text(v, "$p.$k") }
        s.metadata?.let { m ->
            listOf("title" to m.title, "bowUuid" to m.bowUuid, "arrowSetUuid" to m.arrowSetUuid,
                "locationName" to m.locationName, "notes" to m.notes).forEach { (k, v) -> text(v, "$p.metadata.$k") }
            text(m.date?.localDateTime, "$p.metadata.date.localDateTime"); text(m.date?.zoneId, "$p.metadata.date.zoneId")
            text(m.date?.offset, "$p.metadata.date.offset")
        }
        configuration(s.initialConfiguration, "$p.initialConfiguration"); collection(s.ends?.size, "$p.ends")
        s.ends?.forEachIndexed { j, captured ->
            val e = "$p.ends[$j]"
            text(captured.sessionUuid, "$e.sessionUuid"); text(captured.origin?.previousEndUuid, "$e.origin.previousEndUuid")
            configuration(captured.configuration, "$e.configuration")
            captured.end?.let { end ->
                listOf("uuid" to end.uuid, "notes" to end.notes, "createdAt" to end.createdAt, "updatedAt" to end.updatedAt,
                    "validatedAt" to end.validatedAt).forEach { (k, v) -> text(v, "$e.end.$k") }
                collection(end.impacts.size, "$e.end.impacts")
                end.impacts.forEachIndexed { k, impact ->
                    val a = "$e.end.impacts[$k]"
                    listOf("uuid" to impact.uuid, "targetTypeUuid" to impact.targetTypeUuid, "note" to impact.note,
                        "createdAt" to impact.createdAt, "updatedAt" to impact.updatedAt).forEach { (key, v) -> text(v, "$a.$key") }
                    decision(impact.decision, "$a.decision"); collection(impact.repositions.size, "$a.repositions")
                    impact.repositions.forEachIndexed { n, change ->
                        val r = "$a.repositions[$n]"
                        listOf("impactUuid" to change.impactUuid, "impactCreatedAt" to change.impactCreatedAt,
                            "previousUpdatedAt" to change.previousUpdatedAt, "changedAt" to change.changedAt,
                            "reason" to change.reason).forEach { (key, v) -> text(v, "$r.$key") }
                        decision(change.previousDecision, "$r.previousDecision")
                    }
                }
            }
        }
    }
}

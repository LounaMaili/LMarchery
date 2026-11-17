package com.lmarchery.core.export

import com.google.gson.*
import com.lmarchery.core.model.session.ImpactCancellationReason
import com.lmarchery.core.scoring.archive.v3.*
import com.lmarchery.core.scoring.archive.v4.*

sealed interface ArchiveV4JsonRead {
    data class Accepted(val snapshot: ArchiveV4Snapshot, val archive: DomainArchiveV4) : ArchiveV4JsonRead
    data class Rejected(val issue: JsonIssue) : ArchiveV4JsonRead
}

/** Strict, independently versioned participation evidence. Historical codecs retain their bytes and rules. */
class ArchiveV4JsonCodec(private val limits: JsonLimits = JsonLimits(),
    private val archiveLimits: ArchiveV3Limits = ArchiveV3Limits()) {
    init {
        val maximum = JsonLimits(); val domain = ArchiveV3Limits()
        require(limits.maxBytes <= maximum.maxBytes && limits.maxDepth <= maximum.maxDepth && limits.maxValues <= maximum.maxValues &&
            limits.maxArrayElements <= maximum.maxArrayElements &&
            limits.maxStringChars <= maximum.maxStringChars) { "Budgets can only be tightened." }
        require(archiveLimits.maxCollectionElements <= domain.maxCollectionElements && archiveLimits.maxTotalElements <= domain.maxTotalElements &&
            archiveLimits.maxTextLength <= domain.maxTextLength) { "Business budgets can only be tightened." }
    }

    fun decode(bytes: ByteArray): ArchiveV4JsonRead = try {
        val tree = parseStrict(bytes, limits)
        val source = read(tree)
        validateV3FloatLexemes(tree)
        ArchiveV4JsonRead.Accepted(source, validate(source))
    } catch (error: ArchiveJsonException) { ArchiveV4JsonRead.Rejected(error.issue) }

    fun encode(archive: DomainArchiveV4): ByteArray {
        val source = archive.toSnapshot()
        validate(source)
        val tree = jsonV3(ArchiveV3Snapshot(3, source.scoringContractVersion, source.profiles, source.sessions, source.bows, source.arrowSets)).asJsonObject
        tree.addProperty("schemaVersion", 4)
        tree.add("cancellations", JsonArray().also { array -> source.cancellations!!.forEach { p ->
            array.add(JsonObject().also { o ->
                o.add("endUuid", json(p.endUuid)); o.add("impactUuid", json(p.impactUuid)); o.add("cancelledAt", json(p.cancelledAt))
                o.add("reason", json(p.reason)); o.add("comment", json(p.comment))
            })
        } })
        val bytes = writeStrict(tree, limits)
        val result = decode(bytes)
        if (result !is ArchiveV4JsonRead.Accepted || result.snapshot != source || result.archive != archive)
            invalid("$", "V4 output failed complete round-trip equality.", "ROUND_TRIP")
        return bytes
    }

    private fun validate(source: ArchiveV4Snapshot): DomainArchiveV4 = when (val r = reconstructArchiveV4(source, limits = archiveLimits)) {
        is ArchiveV4Reconstruction.Accepted -> r.archive
        is ArchiveV4Reconstruction.NeedsIntervention -> invalid("$.${r.issue.path}", r.issue.detail, r.issue.code.name)
    }

    private fun read(tree: JsonElement): ArchiveV4Snapshot = checkedObject(tree, "$",
        "schemaVersion", "scoringContractVersion", "profiles", "sessions", "bows", "arrowSets", "cancellations") {
        val version = nullable("schemaVersion") { int("schemaVersion") }
        val proofs = list("cancellations") { element, path -> checkedObject(element, path,
            "endUuid", "impactUuid", "cancelledAt", "reason", "comment") {
            ImpactCancellationSnapshot(nullable("endUuid") { string("endUuid") }, nullable("impactUuid") { string("impactUuid") },
                nullable("cancelledAt") { string("cancelledAt") }, nullable("reason") { enumeration<ImpactCancellationReason>("reason") },
                nullable("comment") { string("comment") })
        } }
        val base = tree.deepCopy().asJsonObject.also { it.remove("cancellations"); it.addProperty("schemaVersion", 3) }
        val payload = readArchiveV3Snapshot(base, "$")
        ArchiveV4Snapshot(version, payload.scoringContractVersion, payload.profiles, payload.sessions, payload.bows, payload.arrowSets, proofs)
    }
}

package com.lmarchery.core.scoring.archive

/** Untrusted V2 transfer values. A missing root list is not an empty or omitted catalog. */
data class ArchiveV2Snapshot(
    val schemaVersion: Int?,
    val scoringContractVersion: Int?,
    val profiles: List<TargetProfileSnapshot>?,
    val sessions: List<SessionSnapshot>?,
    val bows: List<BowSnapshot>?,
    val arrowSets: List<ArrowSetSnapshot>?,
)

data class BowSnapshot(
    val uuid: String?,
    val name: String?,
    val type: String?,
    val drawWeightLbs: Float?,
    val brand: String?,
    val model: String?,
    val notes: String?,
    val createdAt: String?,
    val updatedAt: String?,
)

data class ArrowSetSnapshot(
    val uuid: String?,
    val name: String?,
    val spine: String?,
    val length: String?,
    val pointWeight: String?,
    val nock: String?,
    val vanes: String?,
    val material: String?,
    val notes: String?,
    val createdAt: String?,
    val updatedAt: String?,
)

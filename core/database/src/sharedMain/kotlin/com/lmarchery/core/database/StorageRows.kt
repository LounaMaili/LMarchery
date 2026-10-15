package com.lmarchery.core.database

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Nullable components allow a partial/corrupt timestamp to be diagnosed, never normalized. */
internal data class SqlInstant(val epochSecond: Long?, val nano: Int?)

@Entity(tableName = "entity_identities")
internal data class IdentityRow(@PrimaryKey val uuid: String, val kind: String)

@Entity(tableName = "target_profiles", foreignKeys = [ForeignKey(
    entity = IdentityRow::class, parentColumns = ["uuid"], childColumns = ["uuid"], onDelete = ForeignKey.RESTRICT)])
internal data class ProfileRow(
    @PrimaryKey val uuid: String,
    val name: String, val discipline: String, val diameterCm: Int, val maxScore: Int, val minScore: Int,
    val defaultArrowCount: Int, val defaultDistanceM: Int, val isBuiltIn: Int,
    val xRingEnabled: Int, val xRingLabel: String, val innerTenRadiusBits: Int,
    val regulatoryEdition: String?, val zoneCount: Int,
)

@Entity(tableName = "target_zones", primaryKeys = ["profileUuid", "position"],
    foreignKeys = [ForeignKey(entity = ProfileRow::class, parentColumns = ["uuid"], childColumns = ["profileUuid"],
        onDelete = ForeignKey.RESTRICT)], indices = [Index(value = ["profileUuid", "score"], unique = true)])
internal data class ZoneRow(
    val profileUuid: String, val position: Int, val score: Int,
    val innerRadiusBits: Int, val outerRadiusBits: Int, val colorName: String, val colorHex: String?,
)

@Entity(tableName = "sessions", foreignKeys = [
    ForeignKey(entity = IdentityRow::class, parentColumns = ["uuid"], childColumns = ["uuid"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = ProfileRow::class, parentColumns = ["uuid"], childColumns = ["profileUuid"], onDelete = ForeignKey.RESTRICT),
], indices = [Index("profileUuid")])
internal data class SessionRow(
    @PrimaryKey val uuid: String, val profileUuid: String,
    val archiveSchemaVersion: Int, val scoringContractVersion: Int,
    val localDateTime: String, val zoneId: String, val offset: String,
    val status: String, @Embedded(prefix = "started_") val startedAt: SqlInstant?,
    @Embedded(prefix = "ended_") val endedAt: SqlInstant?,
    val plannedEndCount: Int?, val title: String, val distanceM: Int, val arrowCountMode: String,
    val arrowCountPerEnd: Int?, val isIndoor: Int, val scoringMode: String,
    val bowUuid: String?, val arrowSetUuid: String?, val locationName: String?, val notes: String?,
    @Embedded(prefix = "created_") val createdAt: SqlInstant,
    @Embedded(prefix = "updated_") val updatedAt: SqlInstant,
    val endCount: Int,
)

@Entity(tableName = "ends", foreignKeys = [
    ForeignKey(entity = IdentityRow::class, parentColumns = ["uuid"], childColumns = ["uuid"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = SessionRow::class, parentColumns = ["uuid"], childColumns = ["sessionUuid"], onDelete = ForeignKey.RESTRICT),
], indices = [Index(value = ["sessionUuid", "position"], unique = true), Index(value = ["sessionUuid", "index"], unique = true)])
internal data class EndRow(
    @PrimaryKey val uuid: String, val sessionUuid: String, val position: Int, val index: Int,
    val status: String, val notes: String?,
    @Embedded(prefix = "created_") val createdAt: SqlInstant,
    @Embedded(prefix = "updated_") val updatedAt: SqlInstant,
    @Embedded(prefix = "validated_") val validatedAt: SqlInstant?,
    val impactCount: Int,
)

@Entity(tableName = "impacts", foreignKeys = [
    ForeignKey(entity = IdentityRow::class, parentColumns = ["uuid"], childColumns = ["uuid"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = EndRow::class, parentColumns = ["uuid"], childColumns = ["endUuid"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = ProfileRow::class, parentColumns = ["uuid"], childColumns = ["profileUuid"], onDelete = ForeignKey.RESTRICT),
], indices = [Index(value = ["endUuid", "position"], unique = true), Index(value = ["endUuid", "index"], unique = true), Index("profileUuid")])
internal data class ImpactRow(
    @PrimaryKey val uuid: String, val endUuid: String, val profileUuid: String,
    val position: Int, val index: Int, val xBits: Int, val yBits: Int, val inputMode: String, val note: String?,
    @Embedded(prefix = "created_") val createdAt: SqlInstant,
    @Embedded(prefix = "updated_") val updatedAt: SqlInstant,
    val repositionCount: Int,
)

internal data class SqlScoreAssertions(
    val distanceBits: Int, val calculated: Int, val final: Int, val geometricIsX: Int,
    val isX: Int, val isMiss: Int, val isCordDecisionManual: Int,
)

/** Revision -1 is current; 0..n-1 belong to the corresponding reposition's previous decision. */
@Entity(tableName = "score_decisions", primaryKeys = ["impactUuid", "revision"], foreignKeys = [
    ForeignKey(entity = ImpactRow::class, parentColumns = ["uuid"], childColumns = ["impactUuid"], onDelete = ForeignKey.RESTRICT),
])
internal data class DecisionRow(
    val impactUuid: String, val revision: Int, val kind: String, val selectedScore: Int, val selectedIsX: Int,
    @Embedded(prefix = "decided_") val decidedAt: SqlInstant?,
    val boundaryType: String?, val boundaryRadiusBits: Int?, val reason: String?,
    @Embedded(prefix = "assertions_") val assertions: SqlScoreAssertions,
)

@Entity(tableName = "impact_repositions", primaryKeys = ["impactUuid", "sequence"], foreignKeys = [
    ForeignKey(entity = ImpactRow::class, parentColumns = ["uuid"], childColumns = ["impactUuid"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = DecisionRow::class, parentColumns = ["impactUuid", "revision"],
        childColumns = ["impactUuid", "sequence"], onDelete = ForeignKey.RESTRICT),
])
internal data class RepositionRow(
    val impactUuid: String, val sequence: Int,
    @Embedded(prefix = "impactCreated_") val impactCreatedAt: SqlInstant,
    val inputMode: String, val previousXBits: Int, val previousYBits: Int,
    @Embedded(prefix = "previousUpdated_") val previousUpdatedAt: SqlInstant,
    val newXBits: Int, val newYBits: Int,
    @Embedded(prefix = "changed_") val changedAt: SqlInstant, val reason: String?,
)

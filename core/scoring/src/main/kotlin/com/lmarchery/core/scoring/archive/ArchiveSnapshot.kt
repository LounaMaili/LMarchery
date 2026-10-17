package com.lmarchery.core.scoring.archive

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.BoundaryReference
import com.lmarchery.core.model.scoring.BoundaryValue
import com.lmarchery.core.model.scoring.ScoreDecisionKind
import com.lmarchery.core.model.session.ArrowCountMode
import com.lmarchery.core.model.session.EndStatus
import com.lmarchery.core.model.session.InputMode
import com.lmarchery.core.model.session.ScoringMode
import com.lmarchery.core.model.session.SessionStatus
import com.lmarchery.core.model.target.TargetType

object ArchiveContractVersions {
    // Keep the operational V1 path pinned; adding V2 must not silently upgrade Room/Android.
    const val SCHEMA: Int = 1
    const val SCHEMA_V2: Int = 2
    const val SCORING: Int = 1
}

/** Untrusted transfer values, not domain proofs or a JSON reader. No implicit provenance defaults. */
data class ArchiveSnapshot(
    val schemaVersion: Int?,
    val scoringContractVersion: Int?,
    val profiles: List<TargetProfileSnapshot>,
    val sessions: List<SessionSnapshot>,
)

/** TargetType already enforces structural validity and owns its immutable complete zone list. */
data class TargetProfileSnapshot(val definition: TargetType?, val regulatoryEdition: String?)

data class CivilDateSnapshot(val localDateTime: String?, val zoneId: String?, val offset: String?)

/** Optional redundant assertions. Null means absent, never a substitute for decision provenance. */
data class ScoreAssertions(
    val distanceFromCenter: Float? = null,
    val scoreCalculated: Int? = null,
    val scoreFinal: Int? = null,
    val geometricIsX: Boolean? = null,
    val isX: Boolean? = null,
    val isMiss: Boolean? = null,
    val isCordDecisionManual: Boolean? = null,
)

data class DecisionSnapshot(
    val kind: ScoreDecisionKind?,
    val selected: BoundaryValue?,
    val decidedAt: String?,
    val boundary: BoundaryReference?,
    val reason: String?,
    val assertions: ScoreAssertions?,
)

data class RepositionSnapshot(
    val impactUuid: String,
    val impactCreatedAt: String?,
    val inputMode: InputMode,
    val previousPoint: NormalizedPoint,
    val previousDecision: DecisionSnapshot?,
    val previousUpdatedAt: String?,
    val newPoint: NormalizedPoint,
    val changedAt: String?,
    val reason: String?,
)

data class ImpactSnapshot(
    val uuid: String,
    val index: Int,
    val targetTypeUuid: String,
    val point: NormalizedPoint,
    val inputMode: InputMode,
    val note: String?,
    val createdAt: String?,
    val updatedAt: String?,
    val decision: DecisionSnapshot?,
    val repositions: List<RepositionSnapshot>,
)

data class EndSnapshot(
    val uuid: String,
    val index: Int,
    val status: EndStatus,
    val notes: String?,
    val createdAt: String?,
    val updatedAt: String?,
    val validatedAt: String?,
    val impacts: List<ImpactSnapshot>,
)

data class SessionSnapshot(
    val uuid: String,
    val date: CivilDateSnapshot?,
    val status: SessionStatus,
    val startedAt: String?,
    val endedAt: String?,
    val plannedEndCount: Int?,
    val title: String,
    val targetTypeUuid: String,
    val distanceM: Int,
    val arrowCountMode: ArrowCountMode,
    val arrowCountPerEnd: Int?,
    val isIndoor: Boolean,
    val scoringMode: ScoringMode,
    val bowUuid: String?,
    val arrowSetUuid: String?,
    val locationName: String?,
    val notes: String?,
    val ends: List<EndSnapshot>,
    val createdAt: String?,
    val updatedAt: String?,
)

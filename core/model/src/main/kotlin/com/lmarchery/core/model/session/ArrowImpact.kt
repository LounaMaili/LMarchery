package com.lmarchery.core.model.session

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.FinalScoreDecision
import com.lmarchery.core.model.scoring.ScoreDecisionRequest
import com.lmarchery.core.model.target.TargetType
import java.time.LocalDateTime
import java.util.Collections

class ArrowImpact(
    val uuid: String,
    val index: Int,
    val point: NormalizedPoint,
    distanceFromCenter: Float? = null,
    scoreCalculated: Int? = null,
    scoreFinal: Int? = null,
    isX: Boolean? = null,
    isMiss: Boolean? = null,
    val inputMode: InputMode,
    isCordDecisionManual: Boolean? = null,
    val note: String? = null,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val targetType: TargetType,
    val finalScoreDecision: FinalScoreDecision = FinalScoreDecision.resolve(targetType, point),
    repositions: List<ImpactReposition> = emptyList(),
) {
    val repositions: List<ImpactReposition> = Collections.unmodifiableList(repositions.toList())
    val distanceFromCenter: Float get() = finalScoreDecision.distanceFromCenter
    val scoreCalculated: Int get() = finalScoreDecision.scoreCalculated
    val scoreFinal: Int get() = finalScoreDecision.scoreFinal
    val isX: Boolean get() = finalScoreDecision.isX
    val isMiss: Boolean get() = finalScoreDecision.isMiss
    val isCordDecisionManual: Boolean get() = finalScoreDecision.isCordDecisionManual

    private val snapshot = Snapshot(uuid, index, point, inputMode, note, createdAt, updatedAt,
        targetType, finalScoreDecision, this.repositions)

    init {
        require(uuid.isNotBlank()) { "ArrowImpact uuid must not be blank." }
        require(index > 0) { "ArrowImpact index must be positive." }
        require(finalScoreDecision.targetType == targetType && finalScoreDecision.point == point) {
            "ArrowImpact decision must be validated for its target profile and point."
        }
        // Optional snapshot assertions are checked, never trusted as alternative sources of truth.
        require(distanceFromCenter == null || distanceFromCenter == this.distanceFromCenter) { "Inconsistent impact distance." }
        require(scoreCalculated == null || scoreCalculated == this.scoreCalculated) { "Inconsistent geometric score." }
        require(scoreFinal == null || scoreFinal == this.scoreFinal) { "Final score requires a matching typed decision." }
        require(isX == null || isX == this.isX) { "Inconsistent final X indicator." }
        require(isMiss == null || isMiss == this.isMiss) { "Inconsistent final miss indicator." }
        require(isCordDecisionManual == null || isCordDecisionManual == this.isCordDecisionManual) { "Inconsistent decision provenance." }
        require(!updatedAt.isBefore(createdAt)) { "ArrowImpact updatedAt must not be before createdAt." }
        finalScoreDecision.provenance.decidedAt?.let {
            require(!it.isBefore(createdAt) && !it.isAfter(updatedAt)) {
                "Manual decision date must be between impact createdAt and updatedAt."
            }
        }
        this.repositions.forEachIndexed { index, change ->
            require(change.impactUuid == uuid && change.impactCreatedAt == createdAt && change.inputMode == inputMode &&
                change.previousDecision.targetType == targetType) { "Reposition evidence belongs to a different impact context." }
            val preceding = this.repositions.getOrNull(index - 1)
            if (preceding != null) {
                require(change.previousPoint == preceding.newPoint && !change.previousUpdatedAt.isBefore(preceding.changedAt)) {
                    "Reposition evidence must form a continuous chronological path."
                }
                change.previousDecision.provenance.decidedAt?.let {
                    require(!it.isBefore(preceding.changedAt)) { "A decision cannot predate the position it adjudicates." }
                }
            }
        }
        this.repositions.lastOrNull()?.let { last ->
            require(point == last.newPoint && !updatedAt.isBefore(last.changedAt)) {
                "Impact snapshot must cover its last reposition and retain its point."
            }
            finalScoreDecision.provenance.decidedAt?.let {
                require(!it.isBefore(last.changedAt)) { "A decision cannot predate the position it adjudicates." }
            }
        }
    }

    @Deprecated("TargetType and a validated structured decision are required.")
    constructor(
        uuid: String, index: Int, point: NormalizedPoint, distanceFromCenter: Float,
        scoreCalculated: Int, scoreFinal: Int, isX: Boolean, isMiss: Boolean,
        inputMode: InputMode, isCordDecisionManual: Boolean, note: String? = null,
        createdAt: LocalDateTime, updatedAt: LocalDateTime,
    ) : this(uuid, index, point, distanceFromCenter, scoreCalculated, scoreFinal, isX, isMiss,
        inputMode, isCordDecisionManual, note, createdAt, updatedAt, targetType = missingTargetContext())

    fun withFinalScore(
        targetType: TargetType,
        request: ScoreDecisionRequest,
        updatedAt: LocalDateTime,
    ): ArrowImpact {
        require(targetType == this.targetType) { "Score modification requires the impact's target profile." }
        val decision = FinalScoreDecision.resolve(targetType, point, request)
        decision.provenance.decidedAt?.let {
            require(!it.isBefore(this.updatedAt)) { "Manual decision must not predate the previous impact revision." }
        }
        val revised = ArrowImpact(uuid = uuid, index = index, point = point, inputMode = inputMode,
            note = note, createdAt = createdAt, updatedAt = updatedAt,
            targetType = targetType, finalScoreDecision = decision, repositions = repositions)
        revised.requireNotOlderThan(this)
        return revised
    }

    /** Recompute geometry and invalidate the old score decision, retaining position provenance. */
    fun reposition(
        newPoint: NormalizedPoint,
        changedAt: LocalDateTime,
        reason: String? = null,
    ): ArrowImpact {
        val change = ImpactReposition.from(this, newPoint, changedAt, reason)
        val revised = ArrowImpact(uuid = uuid, index = index, point = newPoint, inputMode = inputMode,
            note = note, createdAt = createdAt, updatedAt = changedAt, targetType = targetType,
            repositions = repositions + change)
        revised.requireNotOlderThan(this)
        return revised
    }

    @Deprecated("Use withFinalScore(targetType, typedRequest, updatedAt).")
    @Suppress("UNUSED_PARAMETER")
    fun withFinalScore(scoreFinal: Int, isCordDecisionManual: Boolean, updatedAt: LocalDateTime): ArrowImpact =
        throw IllegalArgumentException("A target context and a structured score decision are required.")

    internal fun requireNotOlderThan(previous: ArrowImpact) {
        require(createdAt == previous.createdAt) { "ArrowImpact createdAt must be preserved when replacing an existing impact." }
        require(!updatedAt.isBefore(previous.updatedAt)) { "ArrowImpact replacement must not be older than the existing impact." }
        require(targetType == previous.targetType) { "An impact revision cannot silently change target profile." }
        require(inputMode == previous.inputMode) { "An impact revision must preserve its original position input mode." }
        require(repositions.size >= previous.repositions.size &&
            repositions.take(previous.repositions.size) == previous.repositions) {
            "An impact revision cannot discard or rewrite reposition evidence."
        }
        val firstNewChange = repositions.getOrNull(previous.repositions.size)
        if (firstNewChange == null) {
            require(point == previous.point) { "Changing coordinates requires explicit reposition evidence." }
        } else {
            require(firstNewChange.previousPoint == previous.point &&
                !firstNewChange.previousUpdatedAt.isBefore(previous.updatedAt)) {
                "Reposition evidence must start from the previous impact point and a current revision."
            }
        }
        // A valid score decision may precede the move without an intermediate aggregate update.
        val nextDecision = firstNewChange?.previousDecision ?: finalScoreDecision
        if (nextDecision != previous.finalScoreDecision) {
            require(!previous.finalScoreDecision.provenance.isManual || nextDecision.provenance.isManual) {
                "An existing manual decision cannot be silently discarded."
            }
            nextDecision.provenance.decidedAt?.let {
                require(!it.isBefore(previous.updatedAt)) { "Manual decision must not predate the previous impact revision." }
            }
        }
    }

    /** Metadata copies preserve the decision. Scalar scoring arguments are read-only assertions. */
    fun copy(
        uuid: String = this.uuid,
        index: Int = this.index,
        point: NormalizedPoint = this.point,
        distanceFromCenter: Float = this.distanceFromCenter,
        scoreCalculated: Int = this.scoreCalculated,
        scoreFinal: Int = this.scoreFinal,
        isX: Boolean = this.isX,
        isMiss: Boolean = this.isMiss,
        inputMode: InputMode = this.inputMode,
        isCordDecisionManual: Boolean = this.isCordDecisionManual,
        note: String? = this.note,
        createdAt: LocalDateTime = this.createdAt,
        updatedAt: LocalDateTime = this.updatedAt,
    ): ArrowImpact {
        require(distanceFromCenter == this.distanceFromCenter && scoreCalculated == this.scoreCalculated &&
            scoreFinal == this.scoreFinal && isX == this.isX && isMiss == this.isMiss &&
            isCordDecisionManual == this.isCordDecisionManual) {
            "copy cannot change scoring scalars or forge provenance; use a contextualized typed decision."
        }
        require(point == this.point) { "copy cannot change coordinates; use reposition." }
        val revised = ArrowImpact(uuid = uuid, index = index, point = point, inputMode = inputMode,
            note = note, createdAt = createdAt, updatedAt = updatedAt,
            targetType = targetType, finalScoreDecision = finalScoreDecision, repositions = repositions)
        revised.requireNotOlderThan(this)
        return revised
    }

    override fun equals(other: Any?): Boolean = other is ArrowImpact && snapshot == other.snapshot
    override fun hashCode(): Int = snapshot.hashCode()
    override fun toString(): String = snapshot.toString().replaceFirst("Snapshot(", "ArrowImpact(")

    operator fun component1(): String = uuid
    operator fun component2(): Int = index
    operator fun component3(): NormalizedPoint = point
    operator fun component4(): Float = distanceFromCenter
    operator fun component5(): Int = scoreCalculated
    operator fun component6(): Int = scoreFinal
    operator fun component7(): Boolean = isX
    operator fun component8(): Boolean = isMiss
    operator fun component9(): InputMode = inputMode
    operator fun component10(): Boolean = isCordDecisionManual
    operator fun component11(): String? = note
    operator fun component12(): LocalDateTime = createdAt
    operator fun component13(): LocalDateTime = updatedAt

    private data class Snapshot(
        val uuid: String, val index: Int, val point: NormalizedPoint, val inputMode: InputMode,
        val note: String?, val createdAt: LocalDateTime, val updatedAt: LocalDateTime,
        val targetType: TargetType, val finalScoreDecision: FinalScoreDecision,
        val repositions: List<ImpactReposition>,
    )

    companion object {
        private fun missingTargetContext(): Nothing =
            throw IllegalArgumentException("ArrowImpact reconstruction requires an explicit TargetType context.")
    }
}

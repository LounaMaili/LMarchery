package com.lmarchery.core.model.session

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.FinalScoreDecision
import com.lmarchery.core.model.scoring.ScoreDecisionRequest
import com.lmarchery.core.model.target.TargetType
import java.time.LocalDateTime

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
) {
    val distanceFromCenter: Float get() = finalScoreDecision.distanceFromCenter
    val scoreCalculated: Int get() = finalScoreDecision.scoreCalculated
    val scoreFinal: Int get() = finalScoreDecision.scoreFinal
    val isX: Boolean get() = finalScoreDecision.isX
    val isMiss: Boolean get() = finalScoreDecision.isMiss
    val isCordDecisionManual: Boolean get() = finalScoreDecision.isCordDecisionManual

    private val snapshot = Snapshot(uuid, index, point, inputMode, note, createdAt, updatedAt, targetType, finalScoreDecision)

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
            targetType = targetType, finalScoreDecision = decision)
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
        require(point == previous.point || !previous.finalScoreDecision.provenance.isManual) {
            "Moving an adjudicated impact requires a separate explicit workflow."
        }
        if (finalScoreDecision != previous.finalScoreDecision) {
            require(!previous.finalScoreDecision.provenance.isManual || finalScoreDecision.provenance.isManual) {
                "An existing manual decision cannot be silently discarded."
            }
            finalScoreDecision.provenance.decidedAt?.let {
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
        require(point == this.point || !finalScoreDecision.provenance.isManual) {
            "Moving an adjudicated impact requires a separate explicit workflow."
        }
        val decision = if (point == this.point) finalScoreDecision else FinalScoreDecision.resolve(targetType, point)
        val revised = ArrowImpact(uuid = uuid, index = index, point = point, inputMode = inputMode,
            note = note, createdAt = createdAt, updatedAt = updatedAt,
            targetType = targetType, finalScoreDecision = decision)
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
    )

    companion object {
        private fun missingTargetContext(): Nothing =
            throw IllegalArgumentException("ArrowImpact reconstruction requires an explicit TargetType context.")
    }
}

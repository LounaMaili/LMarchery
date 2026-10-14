package com.lmarchery.core.model.session

import com.lmarchery.core.model.NormalizedPoint
import java.time.LocalDateTime

class ArrowImpact(
    val uuid: String,
    val index: Int,
    val point: NormalizedPoint,
    val distanceFromCenter: Float,
    val scoreCalculated: Int,
    val scoreFinal: Int,
    val isX: Boolean,
    val isMiss: Boolean,
    val inputMode: InputMode,
    val isCordDecisionManual: Boolean,
    val note: String? = null,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
) {
    private val snapshot = Snapshot(
        uuid = this.uuid,
        index = this.index,
        point = this.point,
        distanceFromCenter = this.distanceFromCenter,
        scoreCalculated = this.scoreCalculated,
        scoreFinal = this.scoreFinal,
        isX = this.isX,
        isMiss = this.isMiss,
        inputMode = this.inputMode,
        isCordDecisionManual = this.isCordDecisionManual,
        note = this.note,
        createdAt = this.createdAt,
        updatedAt = this.updatedAt,
    )

    init {
        require(uuid.isNotBlank()) { "ArrowImpact uuid must not be blank." }
        require(index > 0) { "ArrowImpact index must be positive." }
        require(distanceFromCenter >= 0f) { "ArrowImpact distanceFromCenter must be >= 0." }
        require(scoreCalculated >= 0) { "ArrowImpact scoreCalculated must be >= 0." }
        require(scoreFinal >= 0) { "ArrowImpact scoreFinal must be >= 0." }
        require(!updatedAt.isBefore(createdAt)) {
            "ArrowImpact updatedAt must not be before createdAt."
        }
    }

    fun withFinalScore(
        scoreFinal: Int,
        isCordDecisionManual: Boolean,
        updatedAt: LocalDateTime,
    ): ArrowImpact {
        require(scoreFinal >= 0) { "scoreFinal must be >= 0." }
        require(!updatedAt.isBefore(this.updatedAt)) {
            "ArrowImpact modification must not be before its previous updatedAt."
        }

        return copy(
            scoreFinal = scoreFinal,
            isCordDecisionManual = isCordDecisionManual,
            updatedAt = updatedAt,
        )
    }

    internal fun requireNotOlderThan(previous: ArrowImpact) {
        require(createdAt == previous.createdAt) {
            "ArrowImpact createdAt must be preserved when replacing an existing impact."
        }
        require(!updatedAt.isBefore(previous.updatedAt)) {
            "ArrowImpact replacement must not be older than the existing impact."
        }
    }

    /** Returns a new value while preserving creation time and temporal history. */
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
        val revised = ArrowImpact(
            uuid = uuid,
            index = index,
            point = point,
            distanceFromCenter = distanceFromCenter,
            scoreCalculated = scoreCalculated,
            scoreFinal = scoreFinal,
            isX = isX,
            isMiss = isMiss,
            inputMode = inputMode,
            isCordDecisionManual = isCordDecisionManual,
            note = note,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
        revised.requireNotOlderThan(this)
        return revised
    }

    override fun equals(other: Any?): Boolean =
        other is ArrowImpact && snapshot == other.snapshot

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
        val uuid: String,
        val index: Int,
        val point: NormalizedPoint,
        val distanceFromCenter: Float,
        val scoreCalculated: Int,
        val scoreFinal: Int,
        val isX: Boolean,
        val isMiss: Boolean,
        val inputMode: InputMode,
        val isCordDecisionManual: Boolean,
        val note: String?,
        val createdAt: LocalDateTime,
        val updatedAt: LocalDateTime,
    )
}

package com.lmarchery.core.model.session

import com.lmarchery.core.model.target.TargetType
import java.time.LocalDateTime

data class Session(
    val uuid: String,
    val date: LocalDateTime,
    val status: SessionStatus,
    val startedAt: LocalDateTime? = null,
    val endedAt: LocalDateTime? = null,
    val plannedEndCount: Int? = null,
    val title: String,
    val targetType: TargetType,
    val distanceM: Int,
    val arrowCountMode: ArrowCountMode,
    val arrowCountPerEnd: Int? = null,
    val isIndoor: Boolean,
    val scoringMode: ScoringMode,
    val bowUuid: String? = null,
    val arrowSetUuid: String? = null,
    val locationName: String? = null,
    val notes: String? = null,
    val ends: List<End> = emptyList(),
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
) {
    init {
        require(uuid.isNotBlank()) { "Session uuid must not be blank." }
        require(title.isNotBlank()) { "Session title must not be blank." }
        require(distanceM > 0) { "Session distanceM must be positive." }
        require(plannedEndCount == null || plannedEndCount > 0) {
            "Session plannedEndCount must be null or positive."
        }
        require(!updatedAt.isBefore(createdAt)) {
            "Session updatedAt must not be before createdAt."
        }
        require(ends.map { it.uuid }.distinct().size == ends.size) {
            "Session ends must have unique uuid values."
        }
        require(ends.map { it.index }.distinct().size == ends.size) {
            "Session ends must have unique index values."
        }
        require(bowUuid == null || bowUuid.isNotBlank()) { "Session bowUuid must not be blank." }
        require(arrowSetUuid == null || arrowSetUuid.isNotBlank()) {
            "Session arrowSetUuid must not be blank."
        }

        when (arrowCountMode) {
            ArrowCountMode.FIXED -> requireNotNull(arrowCountPerEnd) {
                "Session arrowCountPerEnd is required when arrowCountMode is FIXED."
            }.also {
                require(it > 0) { "Session arrowCountPerEnd must be positive." }
            }
            ArrowCountMode.FREE -> require(arrowCountPerEnd == null) {
                "Session arrowCountPerEnd must be null when arrowCountMode is FREE."
            }
        }

        when (status) {
            SessionStatus.DRAFT -> {
                require(startedAt == null) { "Session startedAt must be null while status is DRAFT." }
                require(endedAt == null) { "Session endedAt must be null while status is DRAFT." }
            }
            SessionStatus.IN_PROGRESS -> {
                requireNotNull(startedAt) {
                    "Session startedAt is required when status is IN_PROGRESS."
                }
                require(endedAt == null) {
                    "Session endedAt must be null while status is IN_PROGRESS."
                }
            }
            SessionStatus.COMPLETED -> {
                requireNotNull(startedAt) {
                    "Session startedAt is required when status is COMPLETED."
                }
                requireNotNull(endedAt) {
                    "Session endedAt is required when status is COMPLETED."
                }
            }
        }

        startedAt?.let {
            require(!it.isBefore(createdAt)) { "Session startedAt must not be before createdAt." }
        }
        endedAt?.let {
            requireNotNull(startedAt)
            require(!it.isBefore(startedAt)) { "Session endedAt must not be before startedAt." }
        }
    }

    fun start(startedAt: LocalDateTime): Session {
        require(status == SessionStatus.DRAFT) { "Only a DRAFT session can be started." }
        require(!startedAt.isBefore(createdAt)) {
            "startedAt must not be before createdAt."
        }

        return copy(
            status = SessionStatus.IN_PROGRESS,
            startedAt = startedAt,
            updatedAt = startedAt,
        )
    }

    fun complete(endedAt: LocalDateTime): Session {
        require(status == SessionStatus.IN_PROGRESS) { "Only an IN_PROGRESS session can be completed." }
        val startedAtValue = requireNotNull(startedAt) {
            "startedAt is required before completing a session."
        }
        require(!endedAt.isBefore(startedAtValue)) {
            "endedAt must not be before startedAt."
        }

        return copy(
            status = SessionStatus.COMPLETED,
            endedAt = endedAt,
            updatedAt = endedAt,
        )
    }

    fun withEnd(
        end: End,
        updatedAt: LocalDateTime = end.updatedAt,
    ): Session {
        require(status != SessionStatus.COMPLETED) { "A COMPLETED session cannot be modified." }
        require(!updatedAt.isBefore(createdAt)) {
            "updatedAt must not be before createdAt."
        }

        val nextEnds = ends
            .filterNot { it.uuid == end.uuid }
            .plus(end)
            .sortedBy { it.index }
        val effectiveUpdatedAt = if (updatedAt.isBefore(end.updatedAt)) end.updatedAt else updatedAt

        return copy(
            ends = nextEnds,
            updatedAt = effectiveUpdatedAt,
        )
    }
}

package com.lmarchery.core.model.session

import com.lmarchery.core.model.target.TargetType
import com.lmarchery.core.model.time.SessionDateTime
import java.time.Instant
import java.util.Collections

class Session(
    val uuid: String,
    val date: SessionDateTime,
    val status: SessionStatus,
    val startedAt: Instant? = null,
    val endedAt: Instant? = null,
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
    ends: List<End> = emptyList(),
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val ends: List<End> =
        Collections.unmodifiableList(ends.toList())

    private val snapshot = Snapshot(
        uuid = this.uuid,
        date = this.date,
        status = this.status,
        startedAt = this.startedAt,
        endedAt = this.endedAt,
        plannedEndCount = this.plannedEndCount,
        title = this.title,
        targetType = this.targetType,
        distanceM = this.distanceM,
        arrowCountMode = this.arrowCountMode,
        arrowCountPerEnd = this.arrowCountPerEnd,
        isIndoor = this.isIndoor,
        scoringMode = this.scoringMode,
        bowUuid = this.bowUuid,
        arrowSetUuid = this.arrowSetUuid,
        locationName = this.locationName,
        notes = this.notes,
        ends = this.ends,
        createdAt = this.createdAt,
        updatedAt = this.updatedAt,
    )

    init {
        val ends = this.ends
        require(uuid.isNotBlank()) { "Session uuid must not be blank." }
        require(title.isNotBlank()) { "Session title must not be blank." }
        require(distanceM > 0) { "Session distanceM must be positive." }
        require(plannedEndCount == null || plannedEndCount > 0) {
            "Session plannedEndCount must be null or positive."
        }
        require(!updatedAt.isBefore(createdAt)) {
            "Session updatedAt must not be before createdAt."
        }
        require(ends.all { !it.createdAt.isBefore(createdAt) }) {
            "Session ends must not be created before their session."
        }
        require(ends.all { !it.updatedAt.isAfter(updatedAt) }) {
            "Session updatedAt must cover every end modification."
        }
        require(ends.flatMap { it.impacts }.all { it.targetType == targetType }) {
            "Session impacts must be validated for this exact target profile."
        }
        require(ends.map { it.uuid }.distinct().size == ends.size) {
            "Session ends must have unique uuid values."
        }
        val impactUuids = ends.flatMap { it.impacts }.map { it.uuid }
        require(impactUuids.distinct().size == impactUuids.size) { "Session impacts must have unique uuid values across ends." }
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
            require(!it.isAfter(updatedAt)) { "Session startedAt must not be after updatedAt." }
        }
        endedAt?.let {
            requireNotNull(startedAt)
            require(!it.isBefore(startedAt)) { "Session endedAt must not be before startedAt." }
            require(!it.isAfter(updatedAt)) { "Session endedAt must not be after updatedAt." }
            require(ends.all { end -> !end.updatedAt.isAfter(it) }) {
                "Session endedAt must cover every end modification."
            }
        }
    }

    fun start(startedAt: Instant): Session {
        require(status == SessionStatus.DRAFT) { "Only a DRAFT session can be started." }
        require(!startedAt.isBefore(updatedAt)) {
            "Session start must not be before its latest modification."
        }

        return copy(
            status = SessionStatus.IN_PROGRESS,
            startedAt = startedAt,
            updatedAt = startedAt,
        )
    }

    fun complete(endedAt: Instant): Session {
        require(status == SessionStatus.IN_PROGRESS) { "Only an IN_PROGRESS session can be completed." }
        val startedAtValue = requireNotNull(startedAt) {
            "startedAt is required before completing a session."
        }
        require(!endedAt.isBefore(startedAtValue)) {
            "endedAt must not be before startedAt."
        }
        require(!endedAt.isBefore(updatedAt)) {
            "Session completion must not be before its latest modification."
        }

        return copy(
            status = SessionStatus.COMPLETED,
            endedAt = endedAt,
            updatedAt = endedAt,
        )
    }

    fun withEnd(
        end: End,
        updatedAt: Instant = end.updatedAt,
    ): Session {
        require(status != SessionStatus.COMPLETED) { "A COMPLETED session cannot be modified." }
        require(!updatedAt.isBefore(createdAt)) {
            "updatedAt must not be before createdAt."
        }
        require(!updatedAt.isBefore(end.updatedAt)) {
            "Session integration timestamp must not be before the end updatedAt."
        }
        ends.find { it.uuid == end.uuid }?.let(end::requireNotOlderThan)

        val nextEnds = ends
            .filterNot { it.uuid == end.uuid }
            .plus(end)
            .sortedBy { it.index }
        val effectiveUpdatedAt = maxOf(this.updatedAt, updatedAt)

        return copy(
            ends = nextEnds,
            updatedAt = effectiveUpdatedAt,
        )
    }

    /** Returns a new value while preserving creation time and temporal history. */
    fun copy(
        uuid: String = this.uuid,
        date: SessionDateTime = this.date,
        status: SessionStatus = this.status,
        startedAt: Instant? = this.startedAt,
        endedAt: Instant? = this.endedAt,
        plannedEndCount: Int? = this.plannedEndCount,
        title: String = this.title,
        targetType: TargetType = this.targetType,
        distanceM: Int = this.distanceM,
        arrowCountMode: ArrowCountMode = this.arrowCountMode,
        arrowCountPerEnd: Int? = this.arrowCountPerEnd,
        isIndoor: Boolean = this.isIndoor,
        scoringMode: ScoringMode = this.scoringMode,
        bowUuid: String? = this.bowUuid,
        arrowSetUuid: String? = this.arrowSetUuid,
        locationName: String? = this.locationName,
        notes: String? = this.notes,
        ends: List<End> = this.ends,
        createdAt: Instant = this.createdAt,
        updatedAt: Instant = this.updatedAt,
    ): Session {
        val revised = Session(
            uuid = uuid,
            date = date,
            status = status,
            startedAt = startedAt,
            endedAt = endedAt,
            plannedEndCount = plannedEndCount,
            title = title,
            targetType = targetType,
            distanceM = distanceM,
            arrowCountMode = arrowCountMode,
            arrowCountPerEnd = arrowCountPerEnd,
            isIndoor = isIndoor,
            scoringMode = scoringMode,
            bowUuid = bowUuid,
            arrowSetUuid = arrowSetUuid,
            locationName = locationName,
            notes = notes,
            ends = ends,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
        revised.requireNotOlderThan(this)
        return revised
    }

    internal fun requireNotOlderThan(previous: Session) {
        if (previous.status == SessionStatus.COMPLETED) {
            require(status == SessionStatus.COMPLETED && endedAt == previous.endedAt && ends == previous.ends) {
                "A COMPLETED session cannot be reopened or have its ends replaced."
            }
        }
        require(createdAt == previous.createdAt) {
            "Session createdAt must be preserved when copying an existing session."
        }
        require(!updatedAt.isBefore(previous.updatedAt)) {
            "Session copy must not be older than the existing session."
        }
        if (startedAt != null && startedAt != previous.startedAt) {
            require(!startedAt.isBefore(previous.updatedAt)) {
                "Session copy start must not be before the previous modification."
            }
        }
        if (endedAt != null && endedAt != previous.endedAt) {
            require(!endedAt.isBefore(previous.updatedAt)) {
                "Session copy completion must not be before the previous modification."
            }
        }
        val previousEnds = previous.ends.associateBy { it.uuid }
        ends.forEach { end ->
            previousEnds[end.uuid]?.let(end::requireNotOlderThan)
        }
        // Check impacts across end identities too: a reconstructed end is not a new impact revision context.
        val previousImpacts = previous.ends.flatMap { it.impacts }.associateBy { it.uuid }
        val impacts = ends.flatMap { it.impacts }
        impacts.forEach { impact -> previousImpacts[impact.uuid]?.let(impact::requireNotOlderThan) }
        previous.ends.forEach { oldEnd ->
            if (oldEnd.status == EndStatus.VALIDATED) {
                require(ends.any { it.uuid == oldEnd.uuid && it.impacts == oldEnd.impacts }) {
                    "A session revision cannot discard or relocate a validated end's impacts."
                }
            }
        }
        require(previousImpacts.values.filter { it.repositions.isNotEmpty() }.all { old -> impacts.any { it.uuid == old.uuid } }) {
            "A session revision cannot discard reposition evidence by removing its impact."
        }
    }

    override fun equals(other: Any?): Boolean =
        other is Session && snapshot == other.snapshot

    override fun hashCode(): Int = snapshot.hashCode()

    override fun toString(): String = snapshot.toString().replaceFirst("Snapshot(", "Session(")

    operator fun component1(): String = uuid
    operator fun component2(): SessionDateTime = date
    operator fun component3(): SessionStatus = status
    operator fun component4(): Instant? = startedAt
    operator fun component5(): Instant? = endedAt
    operator fun component6(): Int? = plannedEndCount
    operator fun component7(): String = title
    operator fun component8(): TargetType = targetType
    operator fun component9(): Int = distanceM
    operator fun component10(): ArrowCountMode = arrowCountMode
    operator fun component11(): Int? = arrowCountPerEnd
    operator fun component12(): Boolean = isIndoor
    operator fun component13(): ScoringMode = scoringMode
    operator fun component14(): String? = bowUuid
    operator fun component15(): String? = arrowSetUuid
    operator fun component16(): String? = locationName
    operator fun component17(): String? = notes
    operator fun component18(): List<End> = ends
    operator fun component19(): Instant = createdAt
    operator fun component20(): Instant = updatedAt

    private data class Snapshot(
        val uuid: String,
        val date: SessionDateTime,
        val status: SessionStatus,
        val startedAt: Instant?,
        val endedAt: Instant?,
        val plannedEndCount: Int?,
        val title: String,
        val targetType: TargetType,
        val distanceM: Int,
        val arrowCountMode: ArrowCountMode,
        val arrowCountPerEnd: Int?,
        val isIndoor: Boolean,
        val scoringMode: ScoringMode,
        val bowUuid: String?,
        val arrowSetUuid: String?,
        val locationName: String?,
        val notes: String?,
        val ends: List<End>,
        val createdAt: Instant,
        val updatedAt: Instant,
    )
}

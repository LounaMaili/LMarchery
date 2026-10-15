package com.lmarchery.core.model.session

import java.time.LocalDateTime
import java.util.Collections

class End(
    val uuid: String,
    val index: Int,
    val status: EndStatus,
    impacts: List<ArrowImpact> = emptyList(),
    val notes: String? = null,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val validatedAt: LocalDateTime? = null,
) {
    val impacts: List<ArrowImpact> =
        Collections.unmodifiableList(impacts.toList())

    private val snapshot = Snapshot(
        uuid = this.uuid,
        index = this.index,
        status = this.status,
        impacts = this.impacts,
        notes = this.notes,
        createdAt = this.createdAt,
        updatedAt = this.updatedAt,
        validatedAt = this.validatedAt,
    )

    init {
        val impacts = this.impacts
        require(uuid.isNotBlank()) { "End uuid must not be blank." }
        require(index > 0) { "End index must be positive." }
        require(!updatedAt.isBefore(createdAt)) { "End updatedAt must not be before createdAt." }
        require(impacts.all { !it.createdAt.isBefore(createdAt) }) {
            "End impacts must not be created before their end."
        }
        require(impacts.all { !it.updatedAt.isAfter(updatedAt) }) {
            "End updatedAt must cover every impact modification."
        }
        require(impacts.all { it.targetType == impacts.firstOrNull()?.targetType }) {
            "End impacts must share the same target profile."
        }
        require(impacts.map { it.uuid }.distinct().size == impacts.size) {
            "End impacts must have unique uuid values."
        }
        require(impacts.map { it.index }.distinct().size == impacts.size) {
            "End impacts must have unique index values."
        }

        when (status) {
            EndStatus.EDITING -> require(validatedAt == null) {
                "End validatedAt must be null while status is EDITING."
            }
            EndStatus.VALIDATED -> requireNotNull(validatedAt) {
                "End validatedAt is required when status is VALIDATED."
            }
        }

        validatedAt?.let {
            require(!it.isBefore(createdAt)) { "End validatedAt must not be before createdAt." }
            require(!it.isAfter(updatedAt)) { "End validatedAt must not be after updatedAt." }
            require(impacts.all { impact -> !impact.updatedAt.isAfter(it) }) {
                "End validatedAt must cover every impact modification."
            }
        }
    }

    fun validate(validatedAt: LocalDateTime): End {
        require(status == EndStatus.EDITING) { "Only an EDITING end can be validated." }
        require(!validatedAt.isBefore(updatedAt)) {
            "End validation must not be before its latest modification."
        }

        return copy(
            status = EndStatus.VALIDATED,
            updatedAt = validatedAt,
            validatedAt = validatedAt,
        )
    }

    fun withImpact(
        impact: ArrowImpact,
        updatedAt: LocalDateTime = impact.updatedAt,
    ): End {
        require(status == EndStatus.EDITING) { "Only an EDITING end can be modified." }
        require(!updatedAt.isBefore(createdAt)) {
            "updatedAt must not be before createdAt."
        }
        require(!updatedAt.isBefore(impact.updatedAt)) {
            "End integration timestamp must not be before the impact updatedAt."
        }
        impacts.find { it.uuid == impact.uuid }?.let(impact::requireNotOlderThan)

        val nextImpacts = impacts
            .filterNot { it.uuid == impact.uuid }
            .plus(impact)
            .sortedBy { it.index }
        val effectiveUpdatedAt = maxOf(this.updatedAt, updatedAt)

        return copy(
            impacts = nextImpacts,
            updatedAt = effectiveUpdatedAt,
        )
    }

    internal fun requireNotOlderThan(previous: End) {
        if (previous.status == EndStatus.VALIDATED) {
            require(status == EndStatus.VALIDATED && validatedAt == previous.validatedAt && impacts == previous.impacts) {
                "A VALIDATED end cannot be reopened or have its impacts replaced."
            }
        }
        require(createdAt == previous.createdAt) {
            "End createdAt must be preserved when replacing an existing end."
        }
        require(!updatedAt.isBefore(previous.updatedAt)) {
            "End replacement must not be older than the existing end."
        }
        if (validatedAt != null && validatedAt != previous.validatedAt) {
            require(!validatedAt.isBefore(previous.updatedAt)) {
                "End replacement validation must not be before the previous modification."
            }
        }
        val previousImpacts = previous.impacts.associateBy { it.uuid }
        require(previous.impacts.filter { it.repositions.isNotEmpty() }.all { old -> impacts.any { it.uuid == old.uuid } }) {
            "An end revision cannot discard reposition evidence by removing its impact."
        }
        impacts.forEach { impact ->
            previousImpacts[impact.uuid]?.let(impact::requireNotOlderThan)
        }
    }

    /** Returns a new value while preserving creation time and temporal history. */
    fun copy(
        uuid: String = this.uuid,
        index: Int = this.index,
        status: EndStatus = this.status,
        impacts: List<ArrowImpact> = this.impacts,
        notes: String? = this.notes,
        createdAt: LocalDateTime = this.createdAt,
        updatedAt: LocalDateTime = this.updatedAt,
        validatedAt: LocalDateTime? = this.validatedAt,
    ): End {
        val revised = End(
            uuid = uuid,
            index = index,
            status = status,
            impacts = impacts,
            notes = notes,
            createdAt = createdAt,
            updatedAt = updatedAt,
            validatedAt = validatedAt,
        )
        revised.requireNotOlderThan(this)
        return revised
    }

    override fun equals(other: Any?): Boolean =
        other is End && snapshot == other.snapshot

    override fun hashCode(): Int = snapshot.hashCode()

    override fun toString(): String = snapshot.toString().replaceFirst("Snapshot(", "End(")

    operator fun component1(): String = uuid
    operator fun component2(): Int = index
    operator fun component3(): EndStatus = status
    operator fun component4(): List<ArrowImpact> = impacts
    operator fun component5(): String? = notes
    operator fun component6(): LocalDateTime = createdAt
    operator fun component7(): LocalDateTime = updatedAt
    operator fun component8(): LocalDateTime? = validatedAt

    private data class Snapshot(
        val uuid: String,
        val index: Int,
        val status: EndStatus,
        val impacts: List<ArrowImpact>,
        val notes: String?,
        val createdAt: LocalDateTime,
        val updatedAt: LocalDateTime,
        val validatedAt: LocalDateTime?,
    )
}

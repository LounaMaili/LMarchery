package com.lmarchery.core.model.session

import java.time.Instant
import java.util.Collections

class End(
    val uuid: String,
    val index: Int,
    val status: EndStatus,
    impacts: List<ArrowImpact> = emptyList(),
    val notes: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val validatedAt: Instant? = null,
    cancellations: List<ImpactCancellation> = emptyList(),
) {
    val impacts: List<ArrowImpact> =
        Collections.unmodifiableList(impacts.toList())
    val cancellations: List<ImpactCancellation> = Collections.unmodifiableList(cancellations.toList())
    private val cancelledIds = this.cancellations.map { it.impactUuid }.toSet()
    private val impactIds = this.impacts.map { it.uuid }.toSet()
    val activeImpacts: List<ArrowImpact> = Collections.unmodifiableList(this.impacts.filterNot { it.uuid in cancelledIds })
    fun isImpactActive(uuid: String): Boolean = uuid in impactIds && uuid !in cancelledIds
    val canAppendImpactIndex: Boolean get() = (impacts.maxOfOrNull { it.index } ?: 0) < Int.MAX_VALUE
    fun nextImpactIndex(): Int = Math.addExact(impacts.maxOfOrNull { it.index } ?: 0, 1)

    private val snapshot = Snapshot(
        uuid = this.uuid,
        index = this.index,
        status = this.status,
        impacts = this.impacts,
        notes = this.notes,
        createdAt = this.createdAt,
        updatedAt = this.updatedAt,
        validatedAt = this.validatedAt,
        cancellations = this.cancellations,
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
        require(this.cancellations.map { it.impactUuid }.distinct().size == this.cancellations.size) {
            "An impact can be cancelled only once."
        }
        require(this.cancellations.zipWithNext().all { (a, b) -> !b.cancelledAt.isBefore(a.cancelledAt) }) {
            "Cancellation evidence must retain chronological append order."
        }
        this.cancellations.forEach { proof ->
            require(proof.endUuid == uuid) { "Cancellation belongs to a different end." }
            val impact = requireNotNull(impacts.singleOrNull { it.uuid == proof.impactUuid }) { "Cancelled impact is missing." }
            require(!proof.cancelledAt.isBefore(impact.updatedAt) && !proof.cancelledAt.isAfter(updatedAt)) {
                "Cancellation chronology must be covered by its end and follow its impact revision."
            }
            require(validatedAt == null || !proof.cancelledAt.isAfter(validatedAt)) {
                "Cancellation cannot follow end validation."
            }
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

    fun validate(validatedAt: Instant): End {
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
        updatedAt: Instant = impact.updatedAt,
    ): End {
        require(status == EndStatus.EDITING) { "Only an EDITING end can be modified." }
        require(cancellations.none { it.impactUuid == impact.uuid } || impacts.single { it.uuid == impact.uuid } == impact) {
            "A cancelled impact is immutable."
        }
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
            require(status == EndStatus.VALIDATED && validatedAt == previous.validatedAt && impacts == previous.impacts && cancellations == previous.cancellations) {
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
        if (cancellations.isNotEmpty()) {
            impacts.forEach { impact ->
                val old = previousImpacts[impact.uuid]
                require(old == null || impact.index == old.index) { "An end with cancellation evidence cannot renumber an impact." }
                require(old != null || impact.index > (previous.impacts.maxOfOrNull { it.index } ?: 0)) {
                    "New impacts must append beyond all historical indexes, including cancelled impacts."
                }
            }
        }
        require(cancellations.take(previous.cancellations.size) == previous.cancellations) {
            "Cancellation evidence cannot disappear, change or be reordered."
        }
        cancellations.forEach { proof ->
            previousImpacts[proof.impactUuid]?.let { old ->
                require(impacts.singleOrNull { it.uuid == old.uuid } == old) { "Cancelling or revising a cancelled impact must preserve its complete original value." }
            }
            if (proof !in previous.cancellations) {
                require(previous.status == EndStatus.EDITING && !proof.cancelledAt.isBefore(previous.updatedAt)) {
                    "New cancellation requires an editable, non-backdated revision."
                }
            }
        }
        require(previous.impacts.filter { it.repositions.isNotEmpty() }.all { old -> impacts.any { it.uuid == old.uuid } }) {
            "An end revision cannot discard reposition evidence by removing its impact."
        }
        impacts.forEach { impact ->
            previousImpacts[impact.uuid]?.let(impact::requireNotOlderThan)
        }
    }

    fun cancelImpact(impactUuid: String, cancelledAt: Instant, comment: String? = null): End {
        require(status == EndStatus.EDITING) { "Only an EDITING end can cancel an impact." }
        require(isImpactActive(impactUuid)) { "Impact is missing or already cancelled." }
        require(!cancelledAt.isBefore(updatedAt)) { "Cancellation cannot predate the end revision." }
        val proof = ImpactCancellation.forImpact(uuid, impacts.single { it.uuid == impactUuid }, cancelledAt, comment = comment)
        return copy(cancellations = cancellations + proof, updatedAt = cancelledAt)
    }

    /** Returns a new value while preserving creation time and temporal history. */
    fun copy(
        uuid: String = this.uuid,
        index: Int = this.index,
        status: EndStatus = this.status,
        impacts: List<ArrowImpact> = this.impacts,
        notes: String? = this.notes,
        createdAt: Instant = this.createdAt,
        updatedAt: Instant = this.updatedAt,
        validatedAt: Instant? = this.validatedAt,
        cancellations: List<ImpactCancellation> = this.cancellations,
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
            cancellations = cancellations,
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
    operator fun component6(): Instant = createdAt
    operator fun component7(): Instant = updatedAt
    operator fun component8(): Instant? = validatedAt

    private data class Snapshot(
        val uuid: String,
        val index: Int,
        val status: EndStatus,
        val impacts: List<ArrowImpact>,
        val notes: String?,
        val createdAt: Instant,
        val updatedAt: Instant,
        val validatedAt: Instant?,
        val cancellations: List<ImpactCancellation>,
    )
}

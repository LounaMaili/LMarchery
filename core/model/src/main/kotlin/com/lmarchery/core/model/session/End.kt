package com.lmarchery.core.model.session

import java.time.LocalDateTime

data class End(
    val uuid: String,
    val index: Int,
    val status: EndStatus,
    val impacts: List<ArrowImpact> = emptyList(),
    val notes: String? = null,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val validatedAt: LocalDateTime? = null,
) {
    init {
        require(uuid.isNotBlank()) { "End uuid must not be blank." }
        require(index > 0) { "End index must be positive." }
        require(!updatedAt.isBefore(createdAt)) { "End updatedAt must not be before createdAt." }
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
        }
    }

    fun validate(validatedAt: LocalDateTime): End {
        require(status == EndStatus.EDITING) { "Only an EDITING end can be validated." }
        require(!validatedAt.isBefore(createdAt)) {
            "validatedAt must not be before createdAt."
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

        val nextImpacts = impacts
            .filterNot { it.uuid == impact.uuid }
            .plus(impact)
            .sortedBy { it.index }
        val effectiveUpdatedAt = if (updatedAt.isBefore(impact.updatedAt)) impact.updatedAt else updatedAt

        return copy(
            impacts = nextImpacts,
            updatedAt = effectiveUpdatedAt,
        )
    }
}

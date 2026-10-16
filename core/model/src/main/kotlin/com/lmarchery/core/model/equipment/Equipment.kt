package com.lmarchery.core.model.equipment

import java.time.Instant
import java.util.UUID

/** Immutable catalog values. No implicit clock, unit conversion or session-reference resolution. */
sealed interface Equipment {
    val uuid: String
    val name: String
    val createdAt: Instant
    val updatedAt: Instant
}

internal fun validateEquipment(value: Equipment) {
    val id = UUID.fromString(value.uuid)
    require(id.version() == 4 && id.variant() == 2 && id.toString() == value.uuid) {
        "Equipment requires a canonical lowercase RFC 4122 UUID v4."
    }
    require(value.name.isNotBlank()) { "Equipment name must not be blank." }
    require(!value.updatedAt.isBefore(value.createdAt)) { "Equipment updatedAt must cover createdAt." }
}

/** Also required at storage boundaries: independently reconstructed snapshots do not prove ancestry. */
fun validateEquipmentUpdate(expected: Equipment, proposed: Equipment) {
    require(expected::class == proposed::class) { "Equipment kind must be preserved." }
    require(expected.uuid == proposed.uuid) { "Equipment UUID must be preserved." }
    require(expected.createdAt == proposed.createdAt) { "Equipment createdAt must be preserved." }
    require(!proposed.updatedAt.isBefore(expected.updatedAt)) { "Equipment revision must not be backdated." }
}

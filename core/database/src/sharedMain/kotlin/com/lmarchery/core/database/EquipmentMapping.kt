package com.lmarchery.core.database

import com.lmarchery.core.model.equipment.ArrowSet
import com.lmarchery.core.model.equipment.Bow
import java.time.Instant

private fun Instant.sql() = SqlInstant(epochSecond, nano)
private fun SqlInstant.instant(): Instant {
    val seconds = requireNotNull(epochSecond) { "Missing epochSecond." }
    val nanos = requireNotNull(nano) { "Missing nano." }
    require(nanos in 0..999999999) { "Stored nano is out of range; normalization is forbidden." }
    return Instant.ofEpochSecond(seconds, nanos.toLong())
}

internal fun Bow.row() = BowRow(uuid, name, type, drawWeightLbs?.toRawBits(), brand, model, notes,
    createdAt.sql(), updatedAt.sql())
internal fun ArrowSet.row() = ArrowSetRow(uuid, name, spine, length, pointWeight, nock, vanes, material, notes,
    createdAt.sql(), updatedAt.sql())
internal fun BowRow.domain(): Bow = decode("bows[$uuid]") {
    Bow(uuid, name, type, drawWeightLbsBits?.let(Float::fromBits), brand, model, notes, createdAt.instant(), updatedAt.instant())
}
internal fun ArrowSetRow.domain(): ArrowSet = decode("arrow_sets[$uuid]") {
    ArrowSet(uuid, name, spine, length, pointWeight, nock, vanes, material, notes, createdAt.instant(), updatedAt.instant())
}

package com.lmarchery.core.model.time

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Civil session date, with original IANA zone and an explicitly resolved offset (ADR 014). */
data class SessionDateTime(
    val localDateTime: LocalDateTime,
    val zoneId: ZoneId,
    val offset: ZoneOffset,
) {
    init {
        require(zoneId.id in ZoneId.getAvailableZoneIds()) { "Session date requires a registered IANA zone ID, such as Europe/Paris or UTC." }
        val valid = zoneId.rules.getValidOffsets(localDateTime)
        require(valid.isNotEmpty()) { "Session local date-time does not exist in its original zone (clock gap)." }
        require(offset in valid) { "Session date offset is inconsistent with its local date-time and original zone." }
    }

    fun toInstant(): Instant = localDateTime.toInstant(offset)

    /** A consultation view, never a mutation of the stored civil date or original zone. */
    fun atZone(viewZone: ZoneId): ZonedDateTime = toInstant().atZone(viewZone)

    companion object {
        fun of(localDateTime: LocalDateTime, zoneId: ZoneId, offset: ZoneOffset? = null): SessionDateTime {
            val selected = offset ?: requireNotNull(zoneId.rules.getValidOffsets(localDateTime).singleOrNull()) {
                "Session local date-time is nonexistent or ambiguous; an overlap requires an explicit valid offset."
            }
            return SessionDateTime(localDateTime, zoneId, selected)
        }

        fun fromInstant(instant: Instant, zoneId: ZoneId): SessionDateTime {
            val local = instant.atZone(zoneId)
            return SessionDateTime(local.toLocalDateTime(), zoneId, local.offset)
        }
    }
}

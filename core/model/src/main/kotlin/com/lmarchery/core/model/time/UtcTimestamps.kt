package com.lmarchery.core.model.time

import java.time.DateTimeException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Temporal conversion contract only; no file import, persistence or default-zone inference. */
object UtcTimestamps {
    private val negativeZeroOffset = Regex("-00(?::?00){0,2}$")

    fun parse(text: String): Instant {
        require(!negativeZeroOffset.containsMatchIn(text)) {
            "An unknown negative-zero offset cannot identify a timestamp's origin."
        }
        return try {
            OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
        } catch (error: DateTimeException) {
            throw IllegalArgumentException("Timestamp requires a valid ISO 8601 date-time with an explicit offset or Z.", error)
        }
    }

    fun format(instant: Instant): String = try {
        // UTC with full nanosecond precision; no millisecond truncation or rounding.
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(instant.atOffset(ZoneOffset.UTC))
    } catch (error: DateTimeException) {
        throw IllegalArgumentException("Timestamp is outside the ISO offset-date-time calendar range.", error)
    }
}

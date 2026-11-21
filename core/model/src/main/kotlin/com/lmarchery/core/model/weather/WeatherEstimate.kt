package com.lmarchery.core.model.weather

import java.time.Duration
import java.time.Instant
import java.util.UUID

/** WGS84 degrees. A grid centre and a requested point have different meanings. */
data class WeatherPoint(val latitude: Double, val longitude: Double) {
    init { require(latitude in -90.0..90.0 && longitude in -180.0..180.0) }
}

enum class EstimateKind { PROVIDER_ESTIMATE, FORECAST }

/** Provider output only. Rain is a modelled quantity over the preceding interval, never observed rain. */
data class WeatherEstimate(
    val provider: String,
    val kind: EstimateKind,
    val modelSelection: String?,
    val model: String?,
    val retrievedAt: Instant,
    val issuedAt: Instant?,
    val validAt: Instant,
    val intervalSeconds: Int?,
    val requested: WeatherPoint,
    val grid: WeatherPoint?,
    val windHeightM: Double?,
    val temperatureHeightM: Double?,
    val windMps: Double?,
    val gustMps: Double?,
    val fromTrueDegrees: Double?,
    val temperatureC: Double?,
    val precipitationMm: Double?,
) {
    init {
        for (label in listOfNotNull(provider, modelSelection, model)) {
            require(label.isNotBlank() && label.length <= 128 && label.none(Char::isISOControl))
        }
        require(issuedAt == null || issuedAt <= retrievedAt && issuedAt <= validAt)
        require(kind != EstimateKind.PROVIDER_ESTIMATE || validAt <= retrievedAt)
        require(intervalSeconds == null || intervalSeconds > 0)
        require(precipitationMm == null || intervalSeconds != null)
        for (value in listOfNotNull(windHeightM, temperatureHeightM, windMps, gustMps, precipitationMm)) {
            require(value.isFinite() && value >= 0.0)
        }
        require(fromTrueDegrees == null || fromTrueDegrees >= 0 && fromTrueDegrees < 360)
        require(windMps != 0.0 || fromTrueDegrees == null)
        require(temperatureC == null || temperatureC.isFinite())
        require(listOfNotNull(windMps, gustMps, fromTrueDegrees, temperatureC, precipitationMm).isNotEmpty())
    }

    /** Admission policy for an estimate of current conditions, also checked on explicit save. */
    fun isCurrentAt(now: Instant): Boolean = retrievedAt <= now && validAt <= now &&
        Duration.between(validAt, now) <= Duration.ofMinutes(30) &&
        Duration.between(retrievedAt, now) <= Duration.ofMinutes(30)
}

data class SessionEstimate(val uuid: String, val sessionUuid: String, val estimate: WeatherEstimate) {
    init {
        require(uuid != sessionUuid)
        for (id in listOf(uuid, sessionUuid)) {
            val parsed = UUID.fromString(id)
            require(parsed.version() == 4 && parsed.variant() == 2 && parsed.toString() == id)
        }
    }
}

/** Call only after consent to this provider and exact point. No location, session identifier or scheduler API. */
interface WeatherProvider {
    val name: String
    suspend fun current(point: WeatherPoint): WeatherEstimate
}

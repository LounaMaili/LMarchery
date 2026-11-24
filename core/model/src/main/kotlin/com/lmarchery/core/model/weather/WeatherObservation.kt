package com.lmarchery.core.model.weather

import com.lmarchery.core.model.place.NorthReference
import java.time.Instant
import java.util.Collections
import java.util.UUID

private fun checkUuid(id: String) {
    val value = UUID.fromString(id)
    require(value.toString() == id && value.version() == 4 && value.variant() == 2) { "UUID v4 invalide." }
}

/** Empty means the session itself. A non-empty set designates exactly these ends. */
class WeatherScope(ids: Collection<String> = emptyList()) {
    val endUuids: List<String> = Collections.unmodifiableList(ids.sorted())
    init {
        endUuids.forEach(::checkUuid)
        require(endUuids.distinct().size == endUuids.size)
    }
    override fun equals(other: Any?) = other is WeatherScope && endUuids == other.endUuids
    override fun hashCode() = endUuids.hashCode()
}

enum class RainReport { RAIN, NO_RAIN }
enum class WeatherOrigin { MANUAL }

data class WindFrom(val degrees: Double, val north: NorthReference, val uncertaintyDegrees: Double? = null) {
    init {
        require(degrees >= 0.0 && degrees < 360.0) { "Direction attendue entre 0° inclus et 360° exclu." }
        require(uncertaintyDegrees == null || uncertaintyDegrees in 0.0..180.0)
    }
}

data class WeatherObservation(
    val uuid: String, val sessionUuid: String, val scope: WeatherScope,
    val observedAt: Instant, val validFrom: Instant, val validUntil: Instant? = null,
    val meanMps: Double? = null, val gustMps: Double? = null, val from: WindFrom? = null,
    val variableWind: Boolean = false, val temperatureC: Double? = null,
    val rain: RainReport? = null, val precipitationMm: Double? = null,
    val origin: WeatherOrigin = WeatherOrigin.MANUAL,
) {
    init {
        checkUuid(uuid); checkUuid(sessionUuid)
        require(uuid != sessionUuid && uuid !in scope.endUuids && sessionUuid !in scope.endUuids)
        require(validUntil == null || validUntil > validFrom) { "La fin doit suivre le début de validité." }
        listOf(meanMps, gustMps, precipitationMm).filterNotNull().forEach {
            require(it.isFinite() && it >= 0.0) { "Les vitesses et les quantités doivent être finies et positives ou nulles." }
        }
        require(temperatureC == null || temperatureC.isFinite())
        require(precipitationMm == null || validUntil != null) { "Une quantité en mm exige un intervalle." }
        require(!variableWind || from == null) { "Un vent variable n’a pas de direction fixe." }
        require(meanMps != 0.0 || from == null && !variableWind) { "Un vent moyen calme n’a pas de direction." }
        require(listOf(meanMps, gustMps, from, temperatureC, rain, precipitationMm).any { it != null } || variableWind) {
            "Renseignez au moins une condition."
        }
    }
}

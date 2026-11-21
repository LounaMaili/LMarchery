package com.lmarchery.core.scoring.archive.v6

import com.lmarchery.core.model.place.NorthReference
import com.lmarchery.core.model.session.SessionStatus
import com.lmarchery.core.model.weather.WeatherObservation
import com.lmarchery.core.scoring.archive.v5.DomainArchiveV5
import java.util.Collections
import kotlin.math.*

class DomainArchiveV6(val base: DomainArchiveV5, observations: Collection<WeatherObservation>) {
    val observations: List<WeatherObservation> = Collections.unmodifiableList(observations.sortedBy { it.uuid })
    init {
        val limits = com.lmarchery.core.scoring.archive.v3.ArchiveV3Limits()
        require(this.observations.size <= limits.maxCollectionElements &&
            this.observations.sumOf { it.scope.endUuids.size.toLong() } <= limits.maxTotalElements) { "Budget météo dépassé." }
        val owners = base.sporting.sessions.associate { session -> session.uuid to session.ends.map { it.uuid }.toSet() }
        val reserved = buildSet {
            addAll(owners.keys)
            owners.values.forEach(::addAll)
            base.sporting.sessions.forEach { s -> s.ends.forEach { e -> e.end.impacts.forEach { add(it.uuid) } } }
            base.sporting.profiles.forEach { add(it.definition!!.uuid) }
            base.sporting.bows.forEach { add(it.uuid) }
            base.sporting.arrowSets.forEach { add(it.uuid) }
            base.places.sites.forEach { add(it.uuid) }
            base.places.lines.forEach { add(it.uuid) }
        }
        val seen = mutableSetOf<String>()
        for (o in this.observations) {
            val ends = requireNotNull(owners[o.sessionUuid]) { "Séance météo inconnue." }
            require(ends.containsAll(o.scope.endUuids)) { "Volée étrangère à la séance du relevé." }
            require(o.uuid !in reserved && seen.add(o.uuid)) { "Identité météo en conflit." }
        }
    }
    override fun equals(other: Any?) = other is DomainArchiveV6 && base == other.base && observations == other.observations
    override fun hashCode() = 31 * base.hashCode() + observations.hashCode()
}

/** Retrospective descriptive statistics. Positive values denote wind FROM the front and FROM the right. */
data class HistoricalWind(val fromFrontMps: Double, val fromRightMps: Double)

fun DomainArchiveV6.historicalWind(observationUuid: String): HistoricalWind? {
    val observation = observations.find { it.uuid == observationUuid } ?: return null
    if (base.sporting.sessions.none { it.uuid == observation.sessionUuid && it.status == SessionStatus.COMPLETED }) return null
    val speed = observation.meanMps ?: return null
    if (speed == 0.0) return HistoricalWind(0.0, 0.0)
    val orientation = base.places.snapshots.find { it.sessionUuid == observation.sessionUuid }?.orientation ?: return null
    val wind = observation.from ?: return null
    if (orientation.reference != NorthReference.TRUE_NORTH || wind.north != NorthReference.TRUE_NORTH) return null
    // A combined stated uncertainty of a quadrant or more can reverse the dominant component.
    val lineUncertainty = orientation.uncertaintyDegrees ?: return null
    val windUncertainty = wind.uncertaintyDegrees ?: return null
    if (lineUncertainty + windUncertainty >= 90.0) return null
    val delta = Math.toRadians(wind.degrees - orientation.degrees)
    fun component(factor: Double) = if (abs(factor) < 1e-15) 0.0 else speed * factor
    return HistoricalWind(component(cos(delta)), component(sin(delta)))
}

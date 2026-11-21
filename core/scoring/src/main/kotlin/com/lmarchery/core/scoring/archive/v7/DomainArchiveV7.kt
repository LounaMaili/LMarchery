package com.lmarchery.core.scoring.archive.v7

import com.lmarchery.core.model.weather.SessionEstimate
import com.lmarchery.core.scoring.archive.v3.ArchiveV3Limits
import com.lmarchery.core.scoring.archive.v6.DomainArchiveV6
import java.util.Collections

class DomainArchiveV7(val base: DomainArchiveV6, estimates: Collection<SessionEstimate>) {
    val estimates: List<SessionEstimate> = Collections.unmodifiableList(estimates.sortedBy { it.uuid })
    val containsCoordinates get() = estimates.isNotEmpty() || base.base.places.containsCoordinates

    init {
        require(estimates.size <= ArchiveV3Limits().maxCollectionElements)
        val sporting = base.base.sporting
        val sessionIds = sporting.sessions.map { it.uuid }.toSet()
        val occupied = sessionIds.toMutableSet()
        sporting.sessions.forEach { s -> s.ends.forEach { e ->
            occupied.add(e.uuid)
            e.end.impacts.forEach { occupied.add(it.uuid) }
        } }
        sporting.profiles.forEach { occupied.add(it.definition!!.uuid) }
        sporting.bows.forEach { occupied.add(it.uuid) }
        sporting.arrowSets.forEach { occupied.add(it.uuid) }
        base.base.places.sites.forEach { occupied.add(it.uuid) }
        base.base.places.lines.forEach { occupied.add(it.uuid) }
        base.observations.forEach { occupied.add(it.uuid) }
        for (row in this.estimates) {
            require(row.sessionUuid in sessionIds && occupied.add(row.uuid)) { "Séance inconnue ou UUID d’estimation en conflit." }
        }
    }

    override fun equals(other: Any?) = other is DomainArchiveV7 && base == other.base && estimates == other.estimates
    override fun hashCode() = 31 * base.hashCode() + estimates.hashCode()
}

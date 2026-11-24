package com.lmarchery.core.scoring.archive.v5

import com.lmarchery.core.model.place.ShootingPlaces
import com.lmarchery.core.scoring.archive.v4.DomainArchiveV4

/** Complete V4 sporting graph plus the independent catalogue and immutable adoption values. */
data class DomainArchiveV5(val sporting: DomainArchiveV4, val places: ShootingPlaces) {
    init {
        val sessions = sporting.sessions.associateBy { it.uuid }
        require(places.snapshots.all { sessions[it.sessionUuid]?.createdAt == it.adoptedAt }) {
            "Instantané sans séance ou date d’adoption différente de la création."
        }
        val placeIds = (places.sites.map { it.uuid } + places.lines.map { it.uuid }).toSet()
        require(sporting.profiles.none { it.definition!!.uuid in placeIds } &&
            sporting.bows.none { it.uuid in placeIds } && sporting.arrowSets.none { it.uuid in placeIds } &&
            sessions.values.none { s -> s.uuid in placeIds || s.ends.any { e -> e.uuid in placeIds || e.end.impacts.any { it.uuid in placeIds } } }) {
            "UUID partagé entre le catalogue de lieux et les données sportives."
        }
    }
}

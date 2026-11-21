package com.lmarchery.core.database

import com.lmarchery.core.model.place.*
import com.lmarchery.core.scoring.archive.v4.DomainArchiveV4

internal data class PlaceAdditions(val sites: List<ShootingSite>, val lines: List<ShootingLine>, val snapshots: List<SessionPlace>) {
    val count get() = sites.size + lines.size + snapshots.size
    fun appendTo(old: ShootingPlaces) = ShootingPlaces(old.sites + sites, old.lines + lines, old.snapshots + snapshots)

    suspend fun insert(db: SessionDatabase) {
        sites.forEach { db.records().insertIdentity(IdentityRow(it.uuid, "SITE")); db.places().insert(it.row()) }
        lines.forEach { db.records().insertIdentity(IdentityRow(it.uuid, "LINE")); db.places().insert(it.row()) }
        snapshots.forEach { db.places().insert(it.row()) }
    }
}

/** Append-only import. A known session cannot acquire, lose or change its snapshot during restoration. */
internal fun placeAdditions(source: ShootingPlaces, old: ShootingPlaces, received: DomainArchiveV4,
    known: DomainArchiveV4): PlaceAdditions {
    val sites = old.sites.associateBy { it.uuid }
    val lines = old.lines.associateBy { it.uuid }
    val snapshots = old.snapshots.associateBy { it.sessionUuid }
    val incoming = source.snapshots.associateBy { it.sessionUuid }
    val knownSessions = known.sessions.map { it.uuid }.toSet()
    val sportingIds = known.identitiesV3().keys + received.identitiesV3().keys
    val siteIds = sites.keys + source.sites.map { it.uuid }
    val lineIds = lines.keys + source.lines.map { it.uuid }
    dataCheck(siteIds.intersect(lineIds).isEmpty() && sportingIds.intersect(siteIds + lineIds).isEmpty(),
        "places.identities", "UUID déjà attribué à un autre type.")
    for (site in source.sites) dataCheck(sites[site.uuid]?.let { it == site } != false, "sites[${site.uuid}]", "Lieu connu divergent.")
    for (line in source.lines) dataCheck(lines[line.uuid]?.let { it == line } != false, "lines[${line.uuid}]", "Ligne connue divergente.")
    for (session in received.sessions) if (session.uuid in knownSessions)
        dataCheck(snapshots[session.uuid] == incoming[session.uuid], "sessionPlaces[${session.uuid}]", "Instantané historique divergent.")
    return PlaceAdditions(source.sites.filter { it.uuid !in sites }, source.lines.filter { it.uuid !in lines },
        source.snapshots.filter { it.sessionUuid !in snapshots })
}

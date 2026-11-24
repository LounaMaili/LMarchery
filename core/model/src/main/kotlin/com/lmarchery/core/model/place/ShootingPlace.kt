package com.lmarchery.core.model.place

import java.time.Instant
import java.util.UUID

private fun uuid4(value: String) {
    val uuid = UUID.fromString(value)
    require(uuid.toString() == value && uuid.version() == 4 && uuid.variant() == 2) { "UUID v4 canonique requis." }
}

private fun label(value: String) {
    require(value.isNotBlank() && value.length <= 4096 && value.toByteArray().toString(Charsets.UTF_8) == value) {
        "Libellé vide, trop long ou invalide."
    }
}

enum class NorthReference { TRUE_NORTH, MAGNETIC_NORTH }
enum class ManualOrigin { MANUAL }

data class GeoPosition(val latitude: Double, val longitude: Double,
    val origin: ManualOrigin? = null, val accuracyMetres: Double? = null) {
    init {
        require(latitude in -90.0..90.0 && longitude >= -180.0 && longitude < 180.0)
        require(accuracyMetres == null || accuracyMetres.isFinite() && accuracyMetres >= 0)
    }
}

/** Clockwise from the specified north, from the archer towards the targets. */
data class LineOrientation(val degrees: Double, val reference: NorthReference,
    val origin: ManualOrigin, val uncertaintyDegrees: Double? = null, val confirmedAt: Instant? = null) {
    init {
        require(degrees >= 0 && degrees < 360)
        require(uncertaintyDegrees == null || uncertaintyDegrees in 0.0..180.0)
    }

    companion object {
        fun manual(angle: Double, reference: NorthReference, uncertainty: Double?, at: Instant?): LineOrientation {
            require(angle.isFinite()) { "Azimut fini requis." }
            val remainder = angle % 360.0
            val normalized = if (remainder < 0) remainder + 360.0 else remainder
            return LineOrientation(if (normalized == 360.0 || normalized == 0.0) 0.0 else normalized,
                reference, ManualOrigin.MANUAL, uncertainty, at)
        }
    }
}

data class ShootingSite(val uuid: String, val name: String, val description: String? = null, val position: GeoPosition? = null) {
    init { uuid4(uuid); label(name); description?.let(::label) }
}

data class ShootingLine(val uuid: String, val siteUuid: String, val name: String,
    val description: String? = null, val orientation: LineOrientation? = null, val active: Boolean = true) {
    init { uuid4(uuid); uuid4(siteUuid); require(uuid != siteUuid); label(name); description?.let(::label) }
}

data class SessionPlace(val sessionUuid: String, val adoptedAt: Instant, val site: ShootingSite,
    val line: ShootingLine?, val orientation: LineOrientation?) {
    init {
        uuid4(sessionUuid)
        require(line == null || line.active && line.siteUuid == site.uuid)
        require(line != null || orientation == null)
    }
}

/** Owned, unordered by business meaning. Canonical UUID order is shared by SQL and the archive. */
class ShootingPlaces(sites: List<ShootingSite>, lines: List<ShootingLine>, snapshots: List<SessionPlace>) {
    val sites = java.util.Collections.unmodifiableList(sites.sortedBy { it.uuid })
    val lines = java.util.Collections.unmodifiableList(lines.sortedBy { it.uuid })
    val snapshots = java.util.Collections.unmodifiableList(snapshots.sortedBy { it.sessionUuid })

    init {
        val siteIds = sites.map { it.uuid }.toSet()
        require(siteIds.size == sites.size && lines.all { it.siteUuid in siteIds })
        require(lines.map { it.uuid }.distinct().size == lines.size && lines.none { it.uuid in siteIds })
        require(snapshots.distinctBy { it.sessionUuid }.size == snapshots.size)
        val owners = lines.associate { it.uuid to it.siteUuid }
        require(snapshots.all { it.site.uuid in siteIds && (it.line == null || owners[it.line.uuid] == it.site.uuid) })
    }

    val containsCoordinates get() = sites.any { it.position != null } || snapshots.any { it.site.position != null }
    override fun equals(other: Any?) = other is ShootingPlaces && sites == other.sites && lines == other.lines && snapshots == other.snapshots
    override fun hashCode() = arrayOf(sites, lines, snapshots).contentHashCode()

    companion object { val EMPTY = ShootingPlaces(emptyList(), emptyList(), emptyList()) }
}

package com.lmarchery.core.database

import androidx.room.*
import com.lmarchery.core.model.place.*
import com.lmarchery.core.model.time.UtcTimestamps

// Bits preserve the exact JVM representation, including signed zero, across Android and SQLite.
internal data class PositionColumns(val latitudeBits: Long, val longitudeBits: Long,
    val origin: String?, val accuracyBits: Long?) {
    fun value() = GeoPosition(Double.fromBits(latitudeBits), Double.fromBits(longitudeBits),
        origin?.let(ManualOrigin::valueOf), accuracyBits?.let(Double::fromBits))
}

internal data class OrientationColumns(val degreesBits: Long, val north: String, val origin: String,
    val uncertaintyBits: Long?, val confirmedAt: String?) {
    fun value() = LineOrientation(Double.fromBits(degreesBits), NorthReference.valueOf(north),
        ManualOrigin.valueOf(origin), uncertaintyBits?.let(Double::fromBits), confirmedAt?.let(UtcTimestamps::parse))
}

private fun GeoPosition.columns() = PositionColumns(latitude.toBits(), longitude.toBits(), origin?.name, accuracyMetres?.toBits())
private fun LineOrientation.columns() = OrientationColumns(degrees.toBits(), reference.name, origin.name,
    uncertaintyDegrees?.toBits(), confirmedAt?.let(UtcTimestamps::format))

@Entity(tableName = "shooting_sites")
internal data class SiteRow(@PrimaryKey val uuid: String, val name: String, val description: String?,
    @Embedded(prefix = "geo_") val position: PositionColumns?)

@Entity(tableName = "shooting_lines", foreignKeys = [ForeignKey(entity = SiteRow::class,
    parentColumns = ["uuid"], childColumns = ["siteUuid"], onDelete = ForeignKey.RESTRICT)], indices = [Index("siteUuid")])
internal data class LineRow(@PrimaryKey val uuid: String, val siteUuid: String, val name: String,
    val description: String?, @Embedded(prefix = "bearing_") val orientation: OrientationColumns?, val active: Int)

@Entity(tableName = "session_places", foreignKeys = [ForeignKey(entity = SessionRow::class,
    parentColumns = ["uuid"], childColumns = ["sessionUuid"], onDelete = ForeignKey.RESTRICT)])
internal data class SessionPlaceRow(@PrimaryKey val sessionUuid: String, val adoptedAt: String,
    @Embedded(prefix = "site_") val site: SiteRow, @Embedded(prefix = "line_") val line: LineRow?,
    @Embedded(prefix = "effective_") val orientation: OrientationColumns?)

internal fun ShootingSite.row() = SiteRow(uuid, name, description, position?.columns())
internal fun ShootingLine.row() = LineRow(uuid, siteUuid, name, description, orientation?.columns(), if (active) 1 else 0)
internal fun SessionPlace.row() = SessionPlaceRow(sessionUuid, UtcTimestamps.format(adoptedAt), site.row(), line?.row(), orientation?.columns())

@Dao
internal interface PlaceDao {
    @Query("SELECT * FROM entity_identities WHERE kind IN ('SITE','LINE') ORDER BY uuid") suspend fun identities(): List<IdentityRow>
    @Query("SELECT EXISTS(SELECT 1 FROM shooting_sites) OR EXISTS(SELECT 1 FROM shooting_lines) OR EXISTS(SELECT 1 FROM session_places) OR EXISTS(SELECT 1 FROM entity_identities WHERE kind IN ('SITE','LINE'))")
    suspend fun hasPlaceData(): Boolean
    @Query("SELECT EXISTS(SELECT 1 FROM weather_estimates) OR EXISTS(SELECT 1 FROM entity_identities WHERE kind='ESTIMATE')")
    suspend fun hasEstimates(): Boolean
    @Query("SELECT EXISTS(SELECT 1 FROM weather_observations) OR EXISTS(SELECT 1 FROM weather_ends) OR EXISTS(SELECT 1 FROM entity_identities WHERE kind='WEATHER')")
    suspend fun hasWeatherData(): Boolean
    @Insert suspend fun insert(row: SiteRow)
    @Insert suspend fun insert(row: LineRow)
    @Insert suspend fun insert(row: SessionPlaceRow)
    @Update suspend fun update(row: SiteRow): Int
    @Update suspend fun update(row: LineRow): Int
}

internal suspend fun PlaceDao.readPlaces(queries: StrictStorageQueries): ShootingPlaces {
    val (sites, lines) = queries.siteCatalogue()
    val ids = sites.map { IdentityRow(it.uuid, "SITE") } + lines.map { IdentityRow(it.uuid, "LINE") }
    dataCheck(identities() == ids.sortedBy { it.uuid }, "places.identities", "Références des lieux ou lignes incohérentes.")
    return ShootingPlaces(sites, lines, queries.sessionPlaces())
}

internal suspend fun PlaceDao.requireOlderExport() {
    requireWeatherFreeExport()
    dataCheck(!hasPlaceData(), "places", "Des lieux ou lignes de tir sont enregistrés. Utilisez la sauvegarde V5.")
}

internal suspend fun PlaceDao.requireWeatherFreeExport() {
    requireEstimateFreeExport()
    dataCheck(!hasWeatherData(), "weather", "Des relevés météo sont enregistrés. Utilisez la sauvegarde V6.")
}

class RoomShootingPlaces internal constructor(private val db: SessionDatabase, private val ensureOpen: () -> Unit) {
    suspend fun catalogue(): Pair<List<ShootingSite>, List<ShootingLine>> {
        ensureOpen()
        return db.useReaderConnection { c -> c.deferredTransaction {
            StrictStorageQueries(c).siteCatalogue()
        } }
    }

    suspend fun save(expected: ShootingSite?, proposed: ShootingSite) {
        require(expected == null || expected.uuid == proposed.uuid)
        ensureOpen()
        db.useWriterConnection { c -> c.immediateTransaction {
            val queries = StrictStorageQueries(c)
            val dao = db.places()
            val stored = queries.site(proposed.uuid)
            if (stored == proposed) return@immediateTransaction
            require(stored == expected) { "Le lieu a changé. Relisez le catalogue." }
            if (expected == null) {
                db.records().insertIdentity(IdentityRow(proposed.uuid, "SITE"))
                dao.insert(proposed.row())
            } else check(dao.update(proposed.row()) == 1)
            check(queries.site(proposed.uuid) == proposed)
        } }
    }

    suspend fun save(expected: ShootingLine?, proposed: ShootingLine) {
        require(expected == null || expected.uuid == proposed.uuid && expected.siteUuid == proposed.siteUuid)
        ensureOpen()
        db.useWriterConnection { c -> c.immediateTransaction {
            val queries = StrictStorageQueries(c)
            val dao = db.places()
            val stored = queries.line(proposed.uuid)
            if (stored == proposed) return@immediateTransaction
            require(stored == expected) { "La ligne a changé. Relisez le catalogue." }
            if (expected == null) {
                db.records().insertIdentity(IdentityRow(proposed.uuid, "LINE"))
                dao.insert(proposed.row())
            } else check(dao.update(proposed.row()) == 1)
            check(queries.line(proposed.uuid) == proposed)
        } }
    }
}

internal suspend fun PlaceDao.requireEstimateFreeExport() {
    dataCheck(!hasEstimates(), "weather_estimates", "Des estimations sont enregistrées. Utilisez la sauvegarde V7.")
}

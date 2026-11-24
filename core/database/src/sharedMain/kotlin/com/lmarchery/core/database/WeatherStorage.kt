package com.lmarchery.core.database

import androidx.room.*
import com.lmarchery.core.model.place.NorthReference
import com.lmarchery.core.model.time.UtcTimestamps
import com.lmarchery.core.model.weather.*

@Entity(tableName = "weather_observations", foreignKeys = [ForeignKey(entity = SessionRow::class,
    parentColumns = ["uuid"], childColumns = ["sessionUuid"])], indices = [Index("sessionUuid")])
internal data class WeatherRow(@PrimaryKey val uuid: String, val sessionUuid: String, val endCount: Int,
    val observedAt: String, val validFrom: String, val validUntil: String?, val meanBits: Long?, val gustBits: Long?,
    val fromBits: Long?, val north: String?, val uncertaintyBits: Long?, val variable: Int,
    val temperatureBits: Long?, val rain: String?, val precipitationBits: Long?, val origin: String) {
    fun domain(ends: List<String>): WeatherObservation {
        dataCheck(ends.size == endCount && variable in 0..1 && ((fromBits == null) == (north == null)) &&
            (fromBits != null || uncertaintyBits == null), "weather[$uuid]", "Relevé ou associations incomplets.")
        return WeatherObservation(uuid, sessionUuid, WeatherScope(ends), UtcTimestamps.parse(observedAt),
            UtcTimestamps.parse(validFrom), validUntil?.let(UtcTimestamps::parse), meanBits?.let(Double::fromBits),
            gustBits?.let(Double::fromBits), fromBits?.let { WindFrom(Double.fromBits(it), NorthReference.valueOf(north!!),
                uncertaintyBits?.let(Double::fromBits)) }, variable == 1, temperatureBits?.let(Double::fromBits),
            rain?.let(RainReport::valueOf), precipitationBits?.let(Double::fromBits), WeatherOrigin.valueOf(origin))
    }
}

@Entity(tableName = "weather_ends", primaryKeys = ["observationUuid", "endUuid"], foreignKeys = [
    ForeignKey(entity = WeatherRow::class, parentColumns = ["uuid"], childColumns = ["observationUuid"]),
    ForeignKey(entity = EndRow::class, parentColumns = ["uuid"], childColumns = ["endUuid"])], indices = [Index("endUuid")])
internal data class WeatherEndRow(val observationUuid: String, val endUuid: String)

@Dao
internal interface WeatherDao {
    @Query("SELECT count(*) FROM weather_observations") suspend fun countObservations(): Long
    @Query("SELECT count(*) FROM weather_ends") suspend fun countLinks(): Long
    @Insert suspend fun insert(row: WeatherRow)
    @Insert suspend fun insert(row: WeatherEndRow)
    @Query("SELECT uuid FROM entity_identities WHERE kind='WEATHER' ORDER BY uuid")
    suspend fun identities(): List<String>
}

internal suspend fun SessionDatabase.insertWeather(o: WeatherObservation) {
    records().insertIdentity(IdentityRow(o.uuid, "WEATHER"))
    weather().insert(WeatherRow(o.uuid, o.sessionUuid, o.scope.endUuids.size, UtcTimestamps.format(o.observedAt),
        UtcTimestamps.format(o.validFrom), o.validUntil?.let(UtcTimestamps::format), o.meanMps?.toBits(), o.gustMps?.toBits(),
        o.from?.degrees?.toBits(), o.from?.north?.name, o.from?.uncertaintyDegrees?.toBits(), if (o.variableWind) 1 else 0,
        o.temperatureC?.toBits(), o.rain?.name, o.precipitationMm?.toBits(), o.origin.name))
    for (end in o.scope.endUuids) weather().insert(WeatherEndRow(o.uuid, end))
}

internal suspend fun SessionDatabase.allWeather(q: StrictStorageQueries): List<WeatherObservation> {
    val values = q.weatherRecords()
    dataCheck(values.map { it.uuid } == weather().identities(), "weather.identities", "Identités météo incohérentes.")
    return values
}

data class WeatherDetails(val observations: List<WeatherObservation>, val place: com.lmarchery.core.model.place.SessionPlace?)

class RoomWeather internal constructor(private val db: SessionDatabase, private val ensureOpen: () -> Unit) {
    suspend fun list(sessionUuid: String) = details(sessionUuid).observations

    suspend fun details(sessionUuid: String): WeatherDetails {
        ensureOpen()
        return db.useReaderConnection { c -> c.deferredTransaction {
            val queries = StrictStorageQueries(c)
            val owners = queries.ends(sessionUuid).map { it.uuid }.toSet()
            val observations = queries.weatherRecords(sessionUuid).also { records ->
                dataCheck(records.all { owners.containsAll(it.scope.endUuids) }, "weather.ends", "Volée étrangère à la séance du relevé.")
            }
            WeatherDetails(observations, queries.sessionPlace(sessionUuid))
        } }
    }

    /** Append one exact manual observation. Reconciliation is a read of the original UUID. */
    suspend fun record(o: WeatherObservation) {
        ensureOpen()
        db.useWriterConnection { c -> c.immediateTransaction {
            val q = StrictStorageQueries(c)
            val known = q.weatherRecords(o.sessionUuid).find { it.uuid == o.uuid }
            if (known == o) return@immediateTransaction
            require(known == null) { "UUID de relevé occupé." }
            require(q.session(o.sessionUuid)?.status == "IN_PROGRESS") { "La séance doit être en cours." }
            require(q.ends(o.sessionUuid).map { it.uuid }.containsAll(o.scope.endUuids)) { "Volée étrangère à la séance." }
            val limits = com.lmarchery.core.scoring.archive.v3.ArchiveV3Limits()
            require(db.weather().countObservations() < limits.maxCollectionElements &&
                db.weather().countLinks() + o.scope.endUuids.size <= limits.maxTotalElements) { "Budget météo dépassé." }
            db.insertWeather(o)
            check(q.weatherRecords(o.sessionUuid).single { it.uuid == o.uuid } == o)
        } }
    }
}

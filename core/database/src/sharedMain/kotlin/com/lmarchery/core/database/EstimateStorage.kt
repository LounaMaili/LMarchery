package com.lmarchery.core.database

import androidx.room.*
import com.lmarchery.core.model.time.UtcTimestamps
import com.lmarchery.core.model.weather.*

@Entity(tableName = "weather_estimates", foreignKeys = [
    ForeignKey(entity = IdentityRow::class, parentColumns = ["uuid"], childColumns = ["uuid"]),
    ForeignKey(entity = SessionRow::class, parentColumns = ["uuid"], childColumns = ["sessionUuid"]),
], indices = [Index("sessionUuid")])
internal data class EstimateRow(
    @PrimaryKey val uuid: String, val sessionUuid: String,
    val provider: String, val kind: String, val modelSelection: String?, val model: String?,
    val retrievedAt: String, val issuedAt: String?, val validAt: String, val intervalSeconds: Int?,
    val latitudeBits: Long, val longitudeBits: Long, val gridLatBits: Long?, val gridLonBits: Long?,
    val windHeightBits: Long?, val temperatureHeightBits: Long?, val windBits: Long?, val gustBits: Long?,
    val fromBits: Long?, val temperatureBits: Long?, val precipitationBits: Long?,
) {
    fun domain(): SessionEstimate {
        dataCheck((gridLatBits == null) == (gridLonBits == null), "weather_estimates[$uuid]", "Maille incomplète.")
        return SessionEstimate(uuid, sessionUuid, WeatherEstimate(provider, EstimateKind.valueOf(kind), modelSelection, model,
            UtcTimestamps.parse(retrievedAt), issuedAt?.let(UtcTimestamps::parse), UtcTimestamps.parse(validAt), intervalSeconds,
            WeatherPoint(Double.fromBits(latitudeBits), Double.fromBits(longitudeBits)),
            gridLatBits?.let { WeatherPoint(Double.fromBits(it), Double.fromBits(requireNotNull(gridLonBits))) },
            windHeightBits?.let(Double::fromBits), temperatureHeightBits?.let(Double::fromBits), windBits?.let(Double::fromBits),
            gustBits?.let(Double::fromBits), fromBits?.let(Double::fromBits), temperatureBits?.let(Double::fromBits),
            precipitationBits?.let(Double::fromBits)))
    }
}

internal suspend fun SessionDatabase.insertEstimate(record: SessionEstimate) {
    records().insertIdentity(IdentityRow(record.uuid, "ESTIMATE"))
    with(record.estimate) {
        weather().insert(EstimateRow(record.uuid, record.sessionUuid, provider, kind.name, modelSelection, model,
            UtcTimestamps.format(retrievedAt), issuedAt?.let(UtcTimestamps::format), UtcTimestamps.format(validAt), intervalSeconds,
            requested.latitude.toBits(), requested.longitude.toBits(), grid?.latitude?.toBits(), grid?.longitude?.toBits(),
            windHeightM?.toBits(), temperatureHeightM?.toBits(), windMps?.toBits(), gustMps?.toBits(),
            fromTrueDegrees?.toBits(), temperatureC?.toBits(), precipitationMm?.toBits()))
    }
}

internal suspend fun SessionDatabase.allEstimates(q: StrictStorageQueries) = q.estimates().also { rows ->
    dataCheck(rows.map { it.uuid } == weather().estimateIdentities(), "estimates.identities", "Identités d’estimation incohérentes.")
}

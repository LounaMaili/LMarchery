package com.lmarchery.core.database

import androidx.room.*

internal const val LEGACY_HOMOGENEOUS = "LEGACY_HOMOGENEOUS"
internal const val CONFIGURED = "CONFIGURED"

/** No configuration identity or event timestamp. The edition is captured from the actual stored profile. */
internal data class StoredEndConfiguration(
    val profileUuid: String, val regulatoryEdition: String?, val distanceM: Int,
    val arrowCountMode: String, val arrowCountPerEnd: Int?, val isIndoor: Int, val scoringMode: String,
)

@Entity(tableName = "session_configurations", foreignKeys = [
    ForeignKey(entity = SessionRow::class, parentColumns = ["uuid"], childColumns = ["sessionUuid"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = ProfileRow::class, parentColumns = ["uuid"], childColumns = ["profileUuid"], onDelete = ForeignKey.RESTRICT),
], indices = [Index("profileUuid")])
internal data class SessionConfigurationRow(
    @PrimaryKey val sessionUuid: String, val representation: String, val origin: String,
    @Embedded val configuration: StoredEndConfiguration,
)

@Entity(tableName = "end_configurations", foreignKeys = [
    ForeignKey(entity = EndRow::class, parentColumns = ["uuid"], childColumns = ["endUuid"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = SessionConfigurationRow::class, parentColumns = ["sessionUuid"], childColumns = ["sessionUuid"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = ProfileRow::class, parentColumns = ["uuid"], childColumns = ["profileUuid"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = EndRow::class, parentColumns = ["uuid"], childColumns = ["previousEndUuid"], onDelete = ForeignKey.RESTRICT),
], indices = [Index("sessionUuid"), Index("profileUuid"), Index("previousEndUuid")])
internal data class EndConfigurationRow(
    @PrimaryKey val endUuid: String, val sessionUuid: String, val origin: String, val previousEndUuid: String?,
    @Embedded val configuration: StoredEndConfiguration,
)

@Dao
internal interface ConfigurationDao : ConfigurationReadQueries {
    @Query("SELECT * FROM session_configurations WHERE sessionUuid = :uuid")
    override suspend fun sessionConfiguration(uuid: String): SessionConfigurationRow?
    @Query("SELECT * FROM end_configurations WHERE endUuid = :uuid")
    override suspend fun endConfiguration(uuid: String): EndConfigurationRow?
    @Query("SELECT 'session_configurations[' || sessionUuid || ']' FROM session_configurations WHERE NOT EXISTS (SELECT 1 FROM sessions WHERE uuid = sessionUuid) UNION ALL SELECT 'end_configurations[' || endUuid || ']' FROM end_configurations WHERE NOT EXISTS (SELECT 1 FROM ends WHERE uuid = endUuid) OR NOT EXISTS (SELECT 1 FROM session_configurations s WHERE s.sessionUuid = end_configurations.sessionUuid) OR (previousEndUuid IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ends WHERE uuid = previousEndUuid)) ORDER BY 1")
    override suspend fun orphanConfigurationPaths(): List<String>
    @Query("SELECT sessionUuid FROM session_configurations WHERE representation != 'LEGACY_HOMOGENEOUS' ORDER BY sessionUuid")
    override suspend fun nonLegacyConfigurationUuids(): List<String>
    // Internal row operations: legacy projections may be synchronized; configured captures are insert-only.
    @Insert suspend fun insertSessionConfiguration(row: SessionConfigurationRow)
    @Insert suspend fun insertEndConfiguration(row: EndConfigurationRow)
    @Update suspend fun updateSessionConfiguration(row: SessionConfigurationRow): Int
    @Update suspend fun updateEndConfiguration(row: EndConfigurationRow): Int
}

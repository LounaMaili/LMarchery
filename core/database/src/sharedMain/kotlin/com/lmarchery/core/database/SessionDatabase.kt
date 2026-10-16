package com.lmarchery.core.database

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update

@Dao
internal interface SessionDao {
    @Insert suspend fun insertIdentity(row: IdentityRow)
    @Insert suspend fun insertProfile(row: ProfileRow)
    @Insert suspend fun insertZones(rows: List<ZoneRow>)
    @Insert suspend fun insertSession(row: SessionRow)
    @Insert suspend fun insertEnd(row: EndRow)
    @Insert suspend fun insertImpact(row: ImpactRow)
    @Insert suspend fun insertDecisions(rows: List<DecisionRow>)
    @Insert suspend fun insertRepositions(rows: List<RepositionRow>)
    @Update suspend fun updateSession(row: SessionRow): Int
    @Update suspend fun updateEnd(row: EndRow): Int
    @Update suspend fun updateImpact(row: ImpactRow): Int
    @Update suspend fun updateDecision(row: DecisionRow): Int
    // Temporary disjoint keys avoid immediate UNIQUE conflicts during permutations.
    // Only visible inside the writer transaction, never committed in this form.
    @Query("UPDATE ends SET position = -position - 1, `index` = -`index` WHERE sessionUuid = :uuid")
    suspend fun stageEndOrder(uuid: String)
    @Query("UPDATE impacts SET position = -position - 1, `index` = -`index` WHERE endUuid = :uuid")
    suspend fun stageImpactOrder(uuid: String)
    @Query("SELECT * FROM entity_identities WHERE uuid IN (:uuids)")
    suspend fun identities(uuids: List<String>): List<IdentityRow>
    // Session-archive scope only: JSON V1 intentionally excludes equipment rows.
    @Query("SELECT uuid, 'PROFILE' AS kind FROM target_profiles UNION ALL SELECT uuid, 'SESSION' AS kind FROM sessions UNION ALL SELECT uuid, 'END' AS kind FROM ends UNION ALL SELECT uuid, 'IMPACT' AS kind FROM impacts")
    suspend fun rowIdentities(): List<IdentityRow>
    // Include physical claims when a damaged registry omitted or misclassified an identity.
    @Query("SELECT uuid, kind FROM entity_identities WHERE uuid IN (:uuids) UNION SELECT uuid, 'PROFILE' AS kind FROM target_profiles WHERE uuid IN (:uuids) UNION SELECT uuid, 'SESSION' AS kind FROM sessions WHERE uuid IN (:uuids) UNION SELECT uuid, 'END' AS kind FROM ends WHERE uuid IN (:uuids) UNION SELECT uuid, 'IMPACT' AS kind FROM impacts WHERE uuid IN (:uuids) UNION SELECT uuid, 'BOW' AS kind FROM bows WHERE uuid IN (:uuids) UNION SELECT uuid, 'ARROW_SET' AS kind FROM arrow_sets WHERE uuid IN (:uuids)")
    suspend fun claimedIdentities(uuids: List<String>): List<IdentityRow>
    @Query("SELECT uuid FROM target_profiles ORDER BY uuid") suspend fun profileUuids(): List<String>
    @Query("SELECT * FROM target_profiles WHERE uuid = :uuid") suspend fun profile(uuid: String): ProfileRow?
    @Query("SELECT * FROM target_zones WHERE profileUuid = :uuid ORDER BY position") suspend fun zones(uuid: String): List<ZoneRow>
    @Query("SELECT * FROM sessions WHERE uuid = :uuid") suspend fun session(uuid: String): SessionRow?
    // Include orphan session identities so a damaged root is discoverable and diagnosed by read().
    @Query("SELECT uuid FROM sessions UNION SELECT uuid FROM entity_identities WHERE kind = 'SESSION' ORDER BY uuid")
    suspend fun sessionUuids(): List<String>
    @Query("SELECT * FROM ends WHERE sessionUuid = :uuid ORDER BY position") suspend fun ends(uuid: String): List<EndRow>
    @Query("SELECT * FROM impacts WHERE endUuid = :uuid ORDER BY position") suspend fun impacts(uuid: String): List<ImpactRow>
    @Query("SELECT * FROM score_decisions WHERE impactUuid = :uuid ORDER BY revision") suspend fun decisions(uuid: String): List<DecisionRow>
    @Query("SELECT * FROM impact_repositions WHERE impactUuid = :uuid ORDER BY sequence") suspend fun repositions(uuid: String): List<RepositionRow>
}

@Database(entities = [IdentityRow::class, ProfileRow::class, ZoneRow::class, SessionRow::class,
    EndRow::class, ImpactRow::class, DecisionRow::class, RepositionRow::class, BowRow::class, ArrowSetRow::class], version = 2, exportSchema = true)
internal abstract class SessionDatabase : RoomDatabase() {
    abstract fun records(): SessionDao
    abstract fun equipment(): EquipmentDao
}

package com.lmarchery.core.database

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update

@Entity(tableName = "bows", foreignKeys = [ForeignKey(
    entity = IdentityRow::class, parentColumns = ["uuid"], childColumns = ["uuid"], onDelete = ForeignKey.RESTRICT)])
internal data class BowRow(
    @PrimaryKey val uuid: String, val name: String, val type: String?, val drawWeightLbsBits: Int?,
    val brand: String?, val model: String?, val notes: String?,
    @Embedded(prefix = "created_") val createdAt: SqlInstant,
    @Embedded(prefix = "updated_") val updatedAt: SqlInstant,
)

@Entity(tableName = "arrow_sets", foreignKeys = [ForeignKey(
    entity = IdentityRow::class, parentColumns = ["uuid"], childColumns = ["uuid"], onDelete = ForeignKey.RESTRICT)])
internal data class ArrowSetRow(
    @PrimaryKey val uuid: String, val name: String, val spine: String?, val length: String?, val pointWeight: String?,
    val nock: String?, val vanes: String?, val material: String?, val notes: String?,
    @Embedded(prefix = "created_") val createdAt: SqlInstant,
    @Embedded(prefix = "updated_") val updatedAt: SqlInstant,
)

@Dao
internal interface EquipmentDao : EquipmentReadQueries {
    @Insert suspend fun insertBow(row: BowRow)
    @Insert suspend fun insertArrowSet(row: ArrowSetRow)
    @Update suspend fun updateBow(row: BowRow): Int
    @Update suspend fun updateArrowSet(row: ArrowSetRow): Int
    @Query("SELECT * FROM bows WHERE uuid = :uuid") override suspend fun bow(uuid: String): BowRow?
    @Query("SELECT * FROM arrow_sets WHERE uuid = :uuid") override suspend fun arrowSet(uuid: String): ArrowSetRow?
    @Query("SELECT uuid FROM bows UNION SELECT uuid FROM entity_identities WHERE kind = 'BOW' ORDER BY uuid")
    override suspend fun bowUuids(): List<String>
    @Query("SELECT uuid FROM arrow_sets UNION SELECT uuid FROM entity_identities WHERE kind = 'ARROW_SET' ORDER BY uuid")
    override suspend fun arrowSetUuids(): List<String>
    // Independent of the registry: detect rows with missing or incorrect ownership too.
    @Query("SELECT uuid, 'PROFILE' AS kind FROM target_profiles WHERE uuid = :uuid UNION ALL SELECT uuid, 'SESSION' AS kind FROM sessions WHERE uuid = :uuid UNION ALL SELECT uuid, 'END' AS kind FROM ends WHERE uuid = :uuid UNION ALL SELECT uuid, 'IMPACT' AS kind FROM impacts WHERE uuid = :uuid UNION ALL SELECT uuid, 'BOW' AS kind FROM bows WHERE uuid = :uuid UNION ALL SELECT uuid, 'ARROW_SET' AS kind FROM arrow_sets WHERE uuid = :uuid")
    suspend fun rowIdentities(uuid: String): List<IdentityRow>
}

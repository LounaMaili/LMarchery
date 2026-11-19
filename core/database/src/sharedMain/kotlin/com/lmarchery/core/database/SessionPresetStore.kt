package com.lmarchery.core.database

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import com.lmarchery.core.model.session.ArrowCountMode
import com.lmarchery.core.model.session.EndConfiguration
import com.lmarchery.core.model.session.ScoringMode
import com.lmarchery.core.scoring.configured.SessionPreset
import com.lmarchery.core.scoring.profiles.BuiltInTargetRegistry
import java.time.Instant

interface SessionPresetRepository {
    suspend fun list(): List<SessionPreset>

    /** False means the name or UUID already exists. Existing presets remain intact. */
    suspend fun add(preset: SessionPreset): Boolean
}

class RoomSessionPresetStore internal constructor(private val database: SessionPresetDatabase) :
    SessionPresetRepository, AutoCloseable {
    override suspend fun list(): List<SessionPreset> = database.presets().list().map(SessionPresetRow::domain)

    override suspend fun add(preset: SessionPreset): Boolean = database.presets().insert(preset.row()) != -1L

    override fun close() = database.close()
}

@Entity(tableName = "session_presets", indices = [Index(value = ["name"], unique = true)])
internal data class SessionPresetRow(
    @PrimaryKey val uuid: String,
    val name: String,
    val profileUuid: String,
    val regulatoryEdition: String?,
    val distanceM: Int,
    val arrowCountMode: String,
    val arrowCountPerEnd: Int?,
    val isIndoor: Boolean,
    val scoringMode: String,
    val plannedEndCount: Int?,
    val bowUuid: String?,
    val arrowSetUuid: String?,
    val createdEpochSecond: Long,
    val createdNano: Int,
) {
    fun domain(): SessionPreset {
        val published = requireNotNull(BuiltInTargetRegistry.find(profileUuid)) { "Profil de preset inconnu." }
        require(createdNano in 0..999_999_999)
        return SessionPreset(uuid, name,
            EndConfiguration(published.definition, regulatoryEdition, distanceM, ArrowCountMode.valueOf(arrowCountMode),
                arrowCountPerEnd, isIndoor, ScoringMode.valueOf(scoringMode)),
            plannedEndCount, bowUuid, arrowSetUuid, Instant.ofEpochSecond(createdEpochSecond, createdNano.toLong()))
    }
}

private fun SessionPreset.row(): SessionPresetRow = SessionPresetRow(uuid, name, configuration.targetType.uuid,
    configuration.regulatoryEdition, configuration.distanceM, configuration.arrowCountMode.name,
    configuration.arrowCountPerEnd, configuration.isIndoor, configuration.scoringMode.name, plannedEndCount,
    bowUuid, arrowSetUuid, createdAt.epochSecond, createdAt.nano)

@Dao
internal interface SessionPresetDao {
    @Query("SELECT * FROM session_presets ORDER BY name")
    suspend fun list(): List<SessionPresetRow>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: SessionPresetRow): Long
}

@Database(entities = [SessionPresetRow::class], version = 1, exportSchema = true)
internal abstract class SessionPresetDatabase : RoomDatabase() {
    abstract fun presets(): SessionPresetDao
}

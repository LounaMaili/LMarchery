package com.lmarchery.core.database

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
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

    suspend fun update(expected: SessionPreset, proposed: SessionPreset): SessionPresetChange
    suspend fun delete(expected: SessionPreset): SessionPresetChange
}

enum class SessionPresetChange { APPLIED, REVISION_CONFLICT, NAME_TAKEN }

class RoomSessionPresetStore internal constructor(private val database: SessionPresetDatabase) :
    SessionPresetRepository, AutoCloseable {
    override suspend fun list(): List<SessionPreset> = database.presets().list().map(SessionPresetRow::domain)

    override suspend fun add(preset: SessionPreset): Boolean = database.presets().insert(preset.row()) != -1L

    override suspend fun update(expected: SessionPreset, proposed: SessionPreset): SessionPresetChange {
        require(proposed.uuid == expected.uuid && proposed.createdAt == expected.createdAt)
        return change(expected) { dao, current ->
            val owner = dao.ownerOfName(proposed.name)
            if (owner != null && owner != current.uuid) return@change SessionPresetChange.NAME_TAKEN
            val replacement = proposed.row()
            if (replacement != current) check(dao.update(replacement) == 1)
            SessionPresetChange.APPLIED
        }
    }

    override suspend fun delete(expected: SessionPreset): SessionPresetChange = change(expected) { dao, current ->
        check(dao.delete(current) == 1)
        SessionPresetChange.APPLIED
    }

    private suspend fun change(expected: SessionPreset, write: suspend (SessionPresetDao, SessionPresetRow) -> SessionPresetChange) =
        database.useWriterConnection { connection ->
            connection.immediateTransaction {
                val dao = database.presets()
                val current = dao.find(expected.uuid)
                if (current == null || current != expected.row()) SessionPresetChange.REVISION_CONFLICT
                else write(dao, current)
            }
        }

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

    @Query("SELECT * FROM session_presets WHERE uuid = :uuid")
    suspend fun find(uuid: String): SessionPresetRow?

    @Query("SELECT uuid FROM session_presets WHERE name = :name")
    suspend fun ownerOfName(name: String): String?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: SessionPresetRow): Long

    @Update
    suspend fun update(row: SessionPresetRow): Int

    @Delete
    suspend fun delete(row: SessionPresetRow): Int
}

@Database(entities = [SessionPresetRow::class], version = 1, exportSchema = true)
internal abstract class SessionPresetDatabase : RoomDatabase() {
    abstract fun presets(): SessionPresetDao
}

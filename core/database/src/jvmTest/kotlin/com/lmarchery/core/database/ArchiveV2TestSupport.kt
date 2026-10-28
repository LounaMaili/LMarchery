package com.lmarchery.core.database

import androidx.room.Room
import androidx.sqlite.*
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.lmarchery.core.model.equipment.*
import com.lmarchery.core.model.session.Session
import com.lmarchery.core.scoring.archive.*
import java.nio.file.Path
import kotlin.test.assertIs

internal fun v2(
    sessions: List<Session> = emptyList(), bows: List<Bow> = emptyList(), arrows: List<ArrowSet> = emptyList(),
    extraProfiles: List<TargetProfileSnapshot> = emptyList(),
): DomainArchiveV2 = reconstructV2(snapshotArchiveV2(
    (sessions.map { TargetProfileSnapshot(it.targetType, null) } + extraProfiles).distinctBy { it.definition!!.uuid },
    sessions, bows, arrows))
internal fun reconstructV2(snapshot: ArchiveV2Snapshot): DomainArchiveV2 =
    assertIs<ArchiveV2Reconstruction.Accepted>(reconstructArchiveV2(snapshot)).archive
internal suspend fun RoomSessionStore.fullV2() = assertIs<ArchiveV2ReadResult.Found>(readArchiveV2()).archive
internal suspend fun RoomSessionStore.previewV2(archive: DomainArchiveV2) =
    assertIs<ArchiveV2PreviewResult.Ready>(previewImportV2(archive)).preview
internal suspend fun RoomSessionStore.restoreV2(archive: DomainArchiveV2): ArchiveV2ImportResult = importArchiveV2(previewV2(archive))

/** Real native disk driver. Hooks run AFTER successful native step, including real INSERT/COMMIT. */
internal fun hookedStore(path: Path, hook: (String, Boolean) -> Unit): RoomSessionStore {
    val native = BundledSQLiteDriver()
    val driver = object : SQLiteDriver by native {
        override fun open(fileName: String): SQLiteConnection {
            val c = native.open(fileName)
            return object : SQLiteConnection by c {
                override fun prepare(sql: String): SQLiteStatement {
                    val s = c.prepare(sql)
                    return object : SQLiteStatement by s {
                        override fun step(): Boolean = s.step().also { hook(sql, it) }
                    }
                }
            }
        }
    }
    return RoomSessionStore(Room.databaseBuilder<SessionDatabase>(path.toString()).addMigrations(MIGRATION_1_2, MIGRATION_2_3).setDriver(driver).build())
}

package com.lmarchery.core.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.core.scoring.archive.v3.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking

/** Room wraps migrate() and schema validation in its transaction. No nested store, connection or commit. */
internal val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        // The native reader executes synchronously on this migration connection. No DAO/Room connection is acquired.
        runBlocking {
            val queries = StrictStorageQueries(connection)
            val source = migrationSourceV2(queries)
            val expected = when (val conversion = convertArchiveV2ToV3(source)) {
                is ArchiveV3Reconstruction.Accepted -> conversion.archive
                is ArchiveV3Reconstruction.NeedsIntervention -> throw CorruptV3Storage(ArchiveV3StorageDiagnostic(
                    ArchiveV3StorageIssueKind.DOMAIN, conversion.issue.path, conversion.issue.detail, conversion.issue))
            }
            fun exec(sql: String) { connection.prepare(sql).use { it.step() } }
            exec("""CREATE TABLE `session_configurations` (`sessionUuid` TEXT NOT NULL, `representation` TEXT NOT NULL, `origin` TEXT NOT NULL, `profileUuid` TEXT NOT NULL, `regulatoryEdition` TEXT, `distanceM` INTEGER NOT NULL, `arrowCountMode` TEXT NOT NULL, `arrowCountPerEnd` INTEGER, `isIndoor` INTEGER NOT NULL, `scoringMode` TEXT NOT NULL, PRIMARY KEY(`sessionUuid`), FOREIGN KEY(`sessionUuid`) REFERENCES `sessions`(`uuid`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`profileUuid`) REFERENCES `target_profiles`(`uuid`) ON UPDATE NO ACTION ON DELETE RESTRICT )""")
            exec("""CREATE INDEX `index_session_configurations_profileUuid` ON `session_configurations` (`profileUuid`)""")
            exec("""CREATE TABLE `end_configurations` (`endUuid` TEXT NOT NULL, `sessionUuid` TEXT NOT NULL, `origin` TEXT NOT NULL, `previousEndUuid` TEXT, `profileUuid` TEXT NOT NULL, `regulatoryEdition` TEXT, `distanceM` INTEGER NOT NULL, `arrowCountMode` TEXT NOT NULL, `arrowCountPerEnd` INTEGER, `isIndoor` INTEGER NOT NULL, `scoringMode` TEXT NOT NULL, PRIMARY KEY(`endUuid`), FOREIGN KEY(`endUuid`) REFERENCES `ends`(`uuid`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`sessionUuid`) REFERENCES `session_configurations`(`sessionUuid`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`profileUuid`) REFERENCES `target_profiles`(`uuid`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`previousEndUuid`) REFERENCES `ends`(`uuid`) ON UPDATE NO ACTION ON DELETE RESTRICT )""")
            exec("""CREATE INDEX `index_end_configurations_sessionUuid` ON `end_configurations` (`sessionUuid`)""")
            exec("""CREATE INDEX `index_end_configurations_profileUuid` ON `end_configurations` (`profileUuid`)""")
            exec("""CREATE INDEX `index_end_configurations_previousEndUuid` ON `end_configurations` (`previousEndUuid`)""")
            exec("""INSERT INTO session_configurations(sessionUuid,representation,origin,profileUuid,regulatoryEdition,distanceM,arrowCountMode,arrowCountPerEnd,isIndoor,scoringMode)
                SELECT s.uuid,'LEGACY_HOMOGENEOUS','LEGACY_DERIVED',s.profileUuid,p.regulatoryEdition,s.distanceM,s.arrowCountMode,s.arrowCountPerEnd,s.isIndoor,s.scoringMode
                FROM sessions s JOIN target_profiles p ON p.uuid = s.profileUuid""")
            exec("""INSERT INTO end_configurations(endUuid,sessionUuid,origin,previousEndUuid,profileUuid,regulatoryEdition,distanceM,arrowCountMode,arrowCountPerEnd,isIndoor,scoringMode)
                SELECT e.uuid,e.sessionUuid,'LEGACY_DERIVED',NULL,c.profileUuid,c.regulatoryEdition,c.distanceM,c.arrowCountMode,c.arrowCountPerEnd,c.isIndoor,c.scoringMode
                FROM ends e JOIN session_configurations c ON c.sessionUuid = e.sessionUuid""")
            val actual = collectArchiveV3(queries, currentCoroutineContext(), ArchiveV3Limits())
            dataCheck(actual == expected, "migration.2_3", "Migrated archive differs from the complete validated V2 source.")
            connection.prepare("PRAGMA foreign_key_check").use {
                dataCheck(!it.step(), "migration.2_3.foreign_keys", "Migration left an invalid foreign key.")
            }
        }
    }
}

/** Source V2 is validated in full BEFORE any DDL; a damaged source is never repaired or partially accepted. */
private suspend fun migrationSourceV2(queries: StrictStorageQueries): DomainArchiveV2 {
    queries.checkBudgets(ArchiveV3Limits(),configured = false)
    val physical = auditStoredIdentities(queries)
    val profiles = queries.profileUuids().map { queries.profileSnapshot(it) }
    val sessions = queries.sessionUuids().map { id ->
        val row = queries.session(id) ?: throw CorruptStorage(StorageDiagnostic("sessions[$id]", "Session row is missing."))
        when (val result = reconstructArchive(queries.legacySessionSnapshot(row))) {
            is ArchiveReconstruction.Accepted -> result.archive.sessions.single()
            is ArchiveReconstruction.NeedsIntervention -> throw CorruptStorage(StorageDiagnostic(
                "sessions[$id].${result.issue.path}", result.issue.detail, result.issue))
        }
    }
    val bows = queries.bowUuids().map { id -> requireNotNull(queries.bow(id)).domain() }
    val arrows = queries.arrowSetUuids().map { id -> requireNotNull(queries.arrowSet(id)).domain() }
    val source = when (val result = reconstructArchiveV2(snapshotArchiveV2(profiles,sessions,bows,arrows))) {
        is ArchiveV2Reconstruction.Accepted -> result.archive
        is ArchiveV2Reconstruction.NeedsIntervention -> throw CorruptStorage(StorageDiagnostic(result.issue.path,result.issue.detail,result.issue))
    }
    val reachable = source.identities().associateBy { it.uuid }
    dataCheck(physical.size == reachable.size && physical.all { reachable[it.uuid] == it }, "identities",
        "Migration source contains unreachable or duplicate physical entities.")
    return source
}

package com.lmarchery.core.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.lmarchery.core.model.session.*
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.core.scoring.archive.v3.*
import com.lmarchery.core.scoring.fixtures.ClassicTargetFixtures
import com.lmarchery.core.scoring.profiles.BuiltInTargetRegistry
import com.lmarchery.core.scoring.profiles.WorldArcheryIndoorTargetProfiles as WA
import java.nio.file.Path
import kotlin.test.*

internal object ArchiveV3DiskData {
    private val f = EquipmentTestData
    val tables = f.tables + listOf("session_configurations", "end_configurations")
    fun source(catalog: Boolean = true): DomainArchiveV2 {
        val custom = ClassicTargetFixtures.classic60.copy(uuid = f.id(900), isBuiltIn = false)
        val declared = custom.copy(uuid = f.id(901), name = " Édition club 🏹 ")
        fun empty(seed: Int, target: com.lmarchery.core.model.target.TargetType, draft: Boolean = false) =
            Session(f.id(seed), f.emptySession().date, if (draft) SessionStatus.DRAFT else SessionStatus.IN_PROGRESS,
                startedAt = if (draft) null else f.now, title = "Synthetic $seed", targetType = target,
                distanceM = if (seed % 2 == 0) 18 else 70, arrowCountMode = ArrowCountMode.FIXED,
                arrowCountPerEnd = if (seed % 2 == 0) 3 else 6, isIndoor = true, scoringMode = ScoringMode.TRAINING,
                bowUuid = f.id(9999), arrowSetUuid = null, createdAt = f.now, updatedAt = f.now.plusSeconds(9),
                ends = listOf(End(f.id(seed + 1), 3, EndStatus.EDITING, createdAt = f.now, updatedAt = f.now),
                    End(f.id(seed + 2), 8, EndStatus.VALIDATED, createdAt = f.now, updatedAt = f.now, validatedAt = f.now)))
        val sessions = listOf(f.session(), f.session(100,true), empty(200,WA.recurve40,true),
            empty(301,WA.compound40), empty(400,custom), empty(501,declared))
        val profiles = (sessions.map { s -> TargetProfileSnapshot(s.targetType,
            if (s.targetType.uuid == declared.uuid) " source club 2026 " else BuiltInTargetRegistry.find(s.targetType.uuid)?.regulatoryEdition) } +
            TargetProfileSnapshot(WA.compound60, WA.RULEBOOK_VERSION)).distinctBy { it.definition!!.uuid }.sortedBy { it.definition!!.uuid }
        return reconstructV2(snapshotArchiveV2(profiles,sessions.sortedBy { it.uuid },
            if (catalog) listOf(f.bow()) else emptyList(),if (catalog) listOf(f.arrows()) else emptyList()))
    }
    fun configured(source: DomainArchiveV2): DomainArchiveV3 = assertIs<ArchiveV3Reconstruction.Accepted>(convertArchiveV2ToV3(source)).archive

    /** Construct genuine historical files from frozen Room DDL/hash and copy ONLY the old table data.
     * The seed runs current Room; it supplies values, never a relabelled schema or migrated file. */
    suspend fun historical(path: Path, version: Int, source: DomainArchiveV2 = source(catalog = version == 2)) {
        require(version == 1 || version == 2)
        require(version == 2 || source.bows.isEmpty() && source.arrowSets.isEmpty())
        val seed = path.resolveSibling(path.fileName.toString() + ".seed")
        openSessionStore(seed).use { assertIs<ArchiveV2ImportResult.Imported>(it.restoreV2(source)) }
        val ddl = requireNotNull(javaClass.getResourceAsStream("/room-schema-v$version.sql")).bufferedReader().use { it.readLines() }
        BundledSQLiteDriver().open(path.toString()).use { connection ->
            fun sql(s: String) { connection.prepare(s).use { it.step() } }
            sql("PRAGMA foreign_keys=ON")
            sql("ATTACH DATABASE '${seed.toString().replace("'", "''")}' AS seed")
            sql("BEGIN IMMEDIATE")
            ddl.filter { it.isNotBlank() && !it.startsWith("--") }.forEach(::sql)
            (if (version == 1) f.oldTables else f.tables).forEach { sql("INSERT INTO `$it` SELECT * FROM seed.`$it`") }
            sql("COMMIT"); sql("DETACH DATABASE seed")
        }
        assertEquals(listOf(version.toString()), f.rows(path,"PRAGMA user_version"))
        assertTrue(f.rows(path,"SELECT name FROM sqlite_master WHERE name='session_configurations'").isEmpty())
        assertTrue(f.rows(path,"PRAGMA foreign_key_check").isEmpty())
    }
    fun schema(path: Path, tableNames: List<String>) = f.rows(path,
        "SELECT type,name,tbl_name,sql FROM sqlite_master WHERE tbl_name IN (${tableNames.joinToString(",") { "'$it'" }}) ORDER BY name")
    fun contents(path: Path) = f.contents(path,tables)
    /** Future format fixture built ONLY with synthetic SQL. No production ConfiguredSession writer exists.
     * The initial compound profile gives 9 at .075; the second end's recurve profile gives 10. */
    fun futureMixed(path: Path) {
        val at=f.now.epochSecond; val nano=f.now.nano; val bits=.075f.toRawBits()
        raw(path,"PRAGMA foreign_keys=ON","BEGIN IMMEDIATE",
            "UPDATE sessions SET archiveSchemaVersion=3 WHERE uuid='${f.id(301)}'",
            "UPDATE session_configurations SET representation='CONFIGURED',origin='CREATION' WHERE sessionUuid='${f.id(301)}'",
            "UPDATE end_configurations SET origin='INITIAL' WHERE endUuid='${f.id(302)}'",
            "UPDATE end_configurations SET origin='EXPLICIT',profileUuid='${WA.recurve40.uuid}',distanceM=18,arrowCountPerEnd=3 WHERE endUuid='${f.id(303)}'",
            "INSERT INTO entity_identities(uuid,kind) VALUES('${f.id(910)}','IMPACT')",
            """INSERT INTO impacts(uuid,endUuid,profileUuid,position,`index`,xBits,yBits,inputMode,note,repositionCount,created_epochSecond,created_nano,updated_epochSecond,updated_nano)
                VALUES('${f.id(910)}','${f.id(303)}','${WA.recurve40.uuid}',0,1,$bits,0,'MANUAL',NULL,0,$at,$nano,$at,$nano)""",
            """INSERT INTO score_decisions(impactUuid,revision,kind,selectedScore,selectedIsX,boundaryType,boundaryRadiusBits,reason,decided_epochSecond,decided_nano,
                assertions_distanceBits,assertions_calculated,assertions_final,assertions_geometricIsX,assertions_isX,assertions_isMiss,assertions_isCordDecisionManual)
                VALUES('${f.id(910)}',-1,'GEOMETRY',10,0,NULL,NULL,NULL,NULL,NULL,$bits,10,10,0,0,0,0)""",
            "UPDATE ends SET impactCount=1 WHERE uuid='${f.id(303)}'","COMMIT")
    }
    fun raw(path: Path, vararg sql: String) {
        BundledSQLiteDriver().open(path.toString()).use { connection ->
            connection.prepare("PRAGMA busy_timeout=5000").use { it.step() }
            sql.forEach { query -> connection.prepare(query).use { it.step() } }
        }
    }
}
internal suspend fun RoomSessionStore.fullV3(): DomainArchiveV3 {
    val result=readArchiveV3()
    return assertIs<ArchiveV3ReadResult.Found>(result,result.toString()).archive
}

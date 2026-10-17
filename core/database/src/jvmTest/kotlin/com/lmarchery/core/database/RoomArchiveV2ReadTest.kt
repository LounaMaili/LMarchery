package com.lmarchery.core.database

import com.lmarchery.core.database.EquipmentTestData.archive
import com.lmarchery.core.database.EquipmentTestData.arrows
import com.lmarchery.core.database.EquipmentTestData.bow
import com.lmarchery.core.database.EquipmentTestData.contents
import com.lmarchery.core.database.EquipmentTestData.denyWrites
import com.lmarchery.core.database.EquipmentTestData.id
import com.lmarchery.core.database.EquipmentTestData.now
import com.lmarchery.core.database.EquipmentTestData.rows
import com.lmarchery.core.database.EquipmentTestData.session
import com.lmarchery.core.model.equipment.*
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.core.scoring.fixtures.ClassicTargetFixtures
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class RoomArchiveV2ReadTest {
    @TempDir lateinit var directory: Path
    private val path get() = directory.resolve("read-v2.db")
    private fun rich() = v2(listOf(session(), session(100, true)), listOf(bow()), listOf(arrows()))

    @Test fun `empty database returns explicit four empty lists`() = runBlocking<Unit> {
        openSessionStore(path).use { assertEquals(v2(), it.fullV2()) }
    }
    @Test fun `catalog only is complete and survives disk close reopen`() = runBlocking<Unit> {
        val expected = v2(bows = listOf(bow()), arrows = listOf(arrows()))
        openSessionStore(path).use { store ->
            store.equipment.create(arrows()); store.equipment.create(bow())
            assertEquals(expected, store.fullV2())
        }
        openSessionStore(path).use { assertEquals(expected, it.fullV2()) }
    }
    @Test fun `sessions only retain opaque missing equipment references and every decision trace`() = runBlocking<Unit> {
        val expected = v2(listOf(session(), session(100, true)))
        openSessionStore(path).use { store ->
            expected.sessions.forEach { assertIs<SessionCreateResult.Created>(store.create(archive(it))) }
            assertEquals(expected, store.fullV2())
            assertTrue(store.fullV2().bows.isEmpty())
        }
        openSessionStore(path).use { assertEquals(expected, it.fullV2()) }
    }
    @Test fun `mixed archive is read only under rejecting triggers on all ten tables`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            val expected = rich()
            assertIs<ArchiveV2ImportResult.Imported>(store.restoreV2(expected))
            denyWrites(path)
            val before = contents(path)
            assertEquals(expected, store.fullV2())
            assertEquals(before, contents(path))
        }
    }
    @Test fun `unreferenced local profile is returned without inventing a session`() = runBlocking<Unit> {
        val profile = TargetProfileSnapshot(ClassicTargetFixtures.classic60.copy(uuid = id(900), isBuiltIn = false), "Club édition 🏹")
        val expected = v2(extraProfiles = listOf(profile))
        openSessionStore(path).use { store ->
            assertIs<ArchiveV2ImportResult.Imported>(store.restoreV2(expected))
            assertEquals(expected, store.fullV2())
            // Legacy V1 export intentionally keeps its referenced-profile-only policy.
            assertTrue(assertIs<ArchiveReadResult.Found>(store.readArchive()).archive.profiles.isEmpty())
        }
    }
    @Test fun `root discovery order is deterministic regardless of insertion order`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            store.equipment.create(bow(810)); store.equipment.create(bow(805)); store.equipment.create(arrows(830)); store.equipment.create(arrows(820))
            assertEquals(listOf(id(805), id(810)), store.fullV2().bows.map { it.uuid })
            assertEquals(listOf(id(820), id(830)), store.fullV2().arrowSets.map { it.uuid })
        }
    }
    @Test fun `corrupt bow blocks entire archive rather than yielding a session only success`() = corrupt("UPDATE bows SET drawWeightLbsBits=2139095040", "bows[")
    @Test fun `corrupt arrow set chronology blocks entire archive`() = corrupt("UPDATE arrow_sets SET updated_nano=-1", "arrow_sets[")
    @Test fun `corrupt session blocks catalog backup too`() = corrupt("UPDATE sessions SET status='UNKNOWN'", "sessions[")
    @Test fun `registry kind mismatch is diagnosed with precise UUID`() = corrupt("UPDATE entity_identities SET kind='END' WHERE uuid='${id(800)}'", "identities[${id(800)}]")
    @Test fun `registry only missing profile is not silently skipped`() = corrupt("INSERT INTO entity_identities VALUES('${id(999)}','PROFILE')", "identities[${id(999)}]")
    @Test fun `registry only missing bow is diagnosed`() = corrupt("INSERT INTO entity_identities VALUES('${id(999)}','BOW')", "identities[${id(999)}]")
    @Test fun `unknown registry type is refused`() = corrupt("INSERT INTO entity_identities VALUES('${id(999)}','FUTURE')", "identities[${id(999)}]")
    @Test fun `physical cross type ownership is diagnosed even if registry names one type`() = corrupt(
        "INSERT INTO arrow_sets SELECT uuid,name,NULL,NULL,NULL,NULL,NULL,NULL,NULL,created_epochSecond,created_nano,updated_epochSecond,updated_nano FROM bows", "identities[${id(800)}]")
    @Test fun `unreachable descendant is diagnosed after bypassed foreign key insertion`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            store.restoreV2(rich())
            // Disable FK only on an independent corruption connection; normal store enforcement remains on.
            raw("PRAGMA foreign_keys=OFF", "UPDATE ends SET sessionUuid='${id(999)}' WHERE uuid='${id(2)}'")
            val before = contents(path)
            assertIs<ArchiveV2ReadResult.NeedsIntervention>(store.readArchiveV2())
            assertEquals(before, contents(path))
        }
    }
    @Test fun `physical bow with missing registry is never omitted`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            store.restoreV2(rich()); raw("PRAGMA foreign_keys=OFF", "DELETE FROM entity_identities WHERE uuid='${id(800)}'")
            val before = contents(path)
            assertEquals("identities[${id(800)}]", assertIs<ArchiveV2ReadResult.NeedsIntervention>(store.readArchiveV2()).diagnostic.path)
            assertEquals(before, contents(path))
        }
    }
    @Test fun `orphan zones decisions and reposition rows are each refused with child path`() = runBlocking<Unit> {
        for (table in listOf("target_zones", "score_decisions", "impact_repositions")) {
            val file = directory.resolve("$table.db")
            openSessionStore(file).use { store ->
                store.restoreV2(rich())
                val column = if (table == "target_zones") "profileUuid" else "impactUuid"
                raw("PRAGMA foreign_keys=OFF", "UPDATE $table SET $column='${id(999)}'" + if (column == "impactUuid") " WHERE impactUuid='${id(11)}'" else "", file = file)
                val before = contents(file)
                val error = assertIs<ArchiveV2ReadResult.NeedsIntervention>(store.readArchiveV2())
                assertTrue(error.diagnostic.path.startsWith("$table[${id(999)}]"), error.diagnostic.toString())
                assertEquals(before, contents(file))
            }
        }
    }
    @Test fun `missing trace or zone blocks archive without repairs`() = runBlocking<Unit> {
        for (table in listOf("impact_repositions", "target_zones")) {
            val file = directory.resolve("missing-$table.db")
            openSessionStore(file).use { store ->
                store.restoreV2(rich()); rows(file, "DELETE FROM $table")
                val before = contents(file)
                assertIs<ArchiveV2ReadResult.NeedsIntervention>(store.readArchiveV2())
                assertEquals(before, contents(file))
            }
        }
    }
    @Test fun `closed store and catalog share lifetime and cannot reopen`() = runBlocking<Unit> {
        val store = openSessionStore(path); store.fullV2(); store.close()
        assertIs<ArchiveV2ReadResult.TechnicalFailure>(store.readArchiveV2())
        assertIs<ArchiveV2PreviewResult.TechnicalFailure>(store.previewImportV2(v2()))
    }
    @Test fun `one snapshot spans catalog sessions and registry despite concurrent writer changes`() = runBlocking<Unit> {
        val old = rich()
        openSessionStore(path).use { it.restoreV2(old) }
        val started = CountDownLatch(1); val resume = CountDownLatch(1); val once = AtomicBoolean(false)
        hookedStore(path) { sql, result ->
            if (sql == "SELECT * FROM entity_identities ORDER BY uuid" && result && once.compareAndSet(false, true)) {
                started.countDown(); check(resume.await(20, TimeUnit.SECONDS))
            }
        }.use { reader ->
            val pending = async(Dispatchers.IO) { reader.fullV2() }
            try {
                assertTrue(withContext(Dispatchers.IO) { started.await(20, TimeUnit.SECONDS) })
                openSessionStore(path).use { writer ->
                    assertIs<EquipmentUpdateResult.Updated>(writer.equipment.update(bow(), bow().copy(name = "New bow", updatedAt = now.plusSeconds(30))))
                    assertIs<EquipmentUpdateResult.Updated>(writer.equipment.update(arrows(), arrows().copy(name = "New arrows", updatedAt = now.plusSeconds(30))))
                    val s = session(); assertIs<SessionUpdateResult.Updated>(writer.update(archive(s), archive(s.copy(title = "New session", updatedAt = now.plusSeconds(30)))))
                    assertIs<EquipmentCreateResult.Created>(writer.equipment.create(bow(802)))
                }
            } finally { resume.countDown() }
            assertEquals(old, pending.await())
            val new = reader.fullV2()
            assertEquals(2, new.bows.size); assertEquals("New arrows", new.arrowSets.single().name)
            assertEquals("New session", new.sessions.first().title)
        }
    }
    @Test fun `caller cancellation during collection is propagated and never returned as an archive`() = runBlocking<Unit> {
        val cancel = AtomicReference<(() -> Unit)?>(null)
        hookedStore(path) { sql, result -> if (sql.startsWith("SELECT * FROM bows") && result) cancel.get()?.invoke() }.use { store ->
            store.restoreV2(rich()); val before = contents(path); var propagated = false
            val job = launch {
                val caller = currentCoroutineContext().job; cancel.set { caller.cancel() }
                try { store.readArchiveV2() } catch (_: CancellationException) { propagated = true }
            }
            job.join(); cancel.set(null)
            assertTrue(propagated); assertEquals(before, contents(path))
        }
    }
    private fun corrupt(sql: String, expectedPath: String) = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            store.restoreV2(rich()); rows(path, sql)
            val before = contents(path)
            val result = assertIs<ArchiveV2ReadResult.NeedsIntervention>(store.readArchiveV2())
            assertTrue(result.diagnostic.path.contains(expectedPath), result.diagnostic.toString())
            assertIs<ArchiveV2PreviewResult.NeedsIntervention>(store.previewImportV2(v2()))
            assertEquals(before, contents(path))
        }
    }
    private fun raw(vararg sql: String, file: Path = path) {
        androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(file.toString()).use { c -> sql.forEach { query -> c.prepare(query).use { it.step() } } }
    }
}

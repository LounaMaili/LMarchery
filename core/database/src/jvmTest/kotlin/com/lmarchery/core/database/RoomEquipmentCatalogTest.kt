package com.lmarchery.core.database

import com.lmarchery.core.database.EquipmentTestData.now
import com.lmarchery.core.database.EquipmentTestData.id
import com.lmarchery.core.database.EquipmentTestData.bow
import com.lmarchery.core.database.EquipmentTestData.arrows
import com.lmarchery.core.database.EquipmentTestData.session
import com.lmarchery.core.database.EquipmentTestData.archive
import com.lmarchery.core.database.EquipmentTestData.rows
import com.lmarchery.core.database.EquipmentTestData.contents
import com.lmarchery.core.database.EquipmentTestData.denyWrites
import com.lmarchery.core.model.equipment.Bow
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class RoomEquipmentCatalogTest {
    @TempDir lateinit var directory: Path
    private val path get() = directory.resolve("catalog.db")

    @Test fun `both kinds round trip every field after disk close and reopen`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            assertIs<EquipmentCreateResult.Created>(store.equipment.create(bow()))
            assertIs<EquipmentCreateResult.Created>(store.equipment.create(arrows()))
        }
        openSessionStore(path).use { store ->
            assertEquals(bow(), assertIs<EquipmentReadResult.Found<Bow>>(store.equipment.readBow(bow().uuid)).value)
            assertEquals(arrows(), assertIs<EquipmentReadResult.Found<*>>(store.equipment.readArrowSet(arrows().uuid)).value)
            assertEquals(Float.MIN_VALUE.toRawBits(), assertIs<EquipmentReadResult.Found<Bow>>(store.equipment.readBow(bow().uuid)).value.drawWeightLbs!!.toRawBits())
        }
        assertEquals(listOf("3"), rows(path, "PRAGMA user_version"))
        assertEquals(emptyList(), rows(path, "PRAGMA foreign_key_check"))
    }
    @Test fun `discovery is deterministic includes orphan entries and read never repairs them`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            listOf(bow(805), bow(800)).forEach { assertIs<EquipmentCreateResult.Created>(store.equipment.create(it)) }
            assertIs<EquipmentCreateResult.Created>(store.equipment.create(arrows()))
        }
        rows(path, "DELETE FROM bows WHERE uuid = '${id(805)}'")
        val before = contents(path)
        denyWrites(path)
        openSessionStore(path).use { store ->
            assertEquals(listOf(id(800), id(805)), assertIs<EquipmentDiscoveryResult.Found>(store.equipment.discoverBows()).uuids)
            assertEquals(listOf(id(801)), assertIs<EquipmentDiscoveryResult.Found>(store.equipment.discoverArrowSets()).uuids)
            assertIs<EquipmentReadResult.NeedsIntervention>(store.equipment.readBow(id(805)))
            assertIs<EquipmentReadResult.NotFound>(store.equipment.readBow(id(999)))
            assertIs<EquipmentReadResult.Found<*>>(store.equipment.readBow(id(800)))
        }
        assertEquals(before, contents(path))
    }
    @Test fun `creation equality and unchanged update are idempotent with no SQL writes`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            assertIs<EquipmentCreateResult.Created>(store.equipment.create(bow()))
            assertIs<EquipmentCreateResult.Created>(store.equipment.create(arrows()))
            val before = contents(path)
            denyWrites(path)
            assertIs<EquipmentCreateResult.AlreadyExists>(store.equipment.create(bow()))
            assertIs<EquipmentCreateResult.AlreadyExists>(store.equipment.create(arrows()))
            assertIs<EquipmentUpdateResult.Unchanged>(store.equipment.update(bow(), bow()))
            assertIs<EquipmentUpdateResult.Unchanged>(store.equipment.update(arrows(), arrows()))
            assertIs<EquipmentCreateResult.Conflict>(store.equipment.create(bow().copy(name = "Divergent")))
            assertEquals(before, contents(path))
        }
    }
    @Test fun `revisions update both kinds preserving identity creation and raw optional values`() = runBlocking<Unit> {
        val nextBow = bow().copy(name = "New", drawWeightLbs = null, updatedAt = now.plusNanos(1))
        val nextArrows = arrows().copy(length = "700 mm", spine = null, updatedAt = now.plusNanos(1))
        openSessionStore(path).use { store ->
            store.equipment.create(bow()); store.equipment.create(arrows())
            assertIs<EquipmentUpdateResult.Updated>(store.equipment.update(bow(), nextBow))
            assertIs<EquipmentUpdateResult.Updated>(store.equipment.update(arrows(), nextArrows))
        }
        openSessionStore(path).use { store ->
            assertEquals(nextBow, assertIs<EquipmentReadResult.Found<*>>(store.equipment.readBow(bow().uuid)).value)
            assertEquals(nextArrows, assertIs<EquipmentReadResult.Found<*>>(store.equipment.readArrowSet(arrows().uuid)).value)
        }
    }
    @Test fun `complete expected state detects conflicts including equal timestamps and stale idempotent retries`() = runBlocking<Unit> {
        val next = bow().copy(notes = "First revision")
        openSessionStore(path).use { store ->
            store.equipment.create(bow())
            assertIs<EquipmentUpdateResult.Updated>(store.equipment.update(bow(), next))
            val before = contents(path)
            assertIs<EquipmentUpdateResult.Conflict>(store.equipment.update(bow(), bow().copy(notes = "Second revision")))
            assertIs<EquipmentUpdateResult.Conflict>(store.equipment.update(bow(), next))
            assertIs<EquipmentUpdateResult.Unchanged>(store.equipment.update(next, next))
            assertEquals(before, contents(path))
        }
    }
    @Test fun `two independent stores serialize competing revisions with exactly one winner`() = runBlocking<Unit> {
        openSessionStore(path).use { it.equipment.create(bow()) }
        openSessionStore(path).use { first -> openSessionStore(path).use { second ->
            val results = listOf(first, second).mapIndexed { index, store -> async(Dispatchers.IO) {
                store.equipment.update(bow(), bow().copy(notes = "Revision $index", updatedAt = now.plusSeconds(1)))
            } }.awaitAll()
            assertEquals(1, results.count { it is EquipmentUpdateResult.Updated })
            assertEquals(1, results.count { it is EquipmentUpdateResult.Conflict })
        } }
    }
    @Test fun `reconstructed values cannot change UUID creation or backdate persisted revision`() = runBlocking<Unit> {
        val current = bow().copy(updatedAt = now.plusSeconds(600))
        openSessionStore(path).use { store ->
            store.equipment.create(current)
            val before = contents(path)
            val illegal = listOf(bow(), Bow(id(999), "Other", createdAt = now, updatedAt = current.updatedAt),
                Bow(current.uuid, current.name, createdAt = now.minusNanos(1), updatedAt = current.updatedAt))
            illegal.forEach { assertIs<EquipmentUpdateResult.NeedsIntervention>(store.equipment.update(current, it)) }
            assertEquals(before, contents(path))
            assertIs<EquipmentUpdateResult.NotFound>(store.equipment.update(bow(999), bow(999)))
        }
    }
    @Test fun `cross kind and published profile identities cannot be claimed`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            store.equipment.create(bow())
            assertIs<EquipmentCreateResult.Conflict>(store.equipment.create(arrows(800)))
            assertIs<EquipmentCreateResult.Created>(store.equipment.create(arrows()))
            assertIs<EquipmentCreateResult.Conflict>(store.equipment.create(bow(801)))
            val session = session()
            val profileId = session.targetType.uuid
            // Reserved even before any profile row is stored.
            assertIs<EquipmentCreateResult.Conflict>(store.equipment.create(Bow(profileId, "Reserved", createdAt = now, updatedAt = now)))
            assertIs<SessionCreateResult.Created>(store.create(archive(session)))
            listOf(session.uuid, session.ends.single().uuid, session.ends.single().impacts.first().uuid, profileId).forEach { uuid ->
                assertIs<EquipmentCreateResult.Conflict>(store.equipment.create(Bow(uuid, "Collision", createdAt = now, updatedAt = now)))
            }
        }
    }
    @Test fun `session profile and new descendant creation respect equipment ownership`() = runBlocking<Unit> {
        val source = session()
        openSessionStore(path).use { store ->
            store.equipment.create(Bow(source.uuid, "Session collision", createdAt = now, updatedAt = now))
            assertIs<SessionCreateResult.Conflict>(store.create(archive(source)))
        }
        val other = directory.resolve("profile.db")
        val custom = source.targetType.copy(uuid = bow().uuid, isBuiltIn = false)
        val customSession = EquipmentTestData.emptySession(custom)
        openSessionStore(other).use { store ->
            store.equipment.create(bow())
            assertIs<SessionCreateResult.Conflict>(store.create(archive(customSession)))
        }
        val descendants = directory.resolve("descendants.db")
        val draft = EquipmentTestData.emptySession(source.targetType)
        openSessionStore(descendants).use { store ->
            store.create(archive(draft))
            store.equipment.create(Bow(source.ends.single().impacts.first().uuid, "Impact collision", createdAt = now, updatedAt = now))
            assertIs<SessionUpdateResult.Conflict>(store.update(archive(draft), archive(source)))
        }
    }
    @Test fun `late insert failure rolls back catalog row and newly registered identity`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            store.equipment.discoverBows()
            val before = contents(path)
            rows(path, "CREATE TRIGGER fail_arrow AFTER INSERT ON arrow_sets BEGIN SELECT RAISE(ABORT, 'late failure'); END")
            assertIs<EquipmentCreateResult.TechnicalFailure>(store.equipment.create(arrows()))
            assertEquals(before, contents(path))
        }
    }
    @Test fun `post insert verification corruption throws and rolls back all writes`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            store.equipment.discoverBows()
            val before = contents(path)
            rows(path, "CREATE TRIGGER corrupt_bow AFTER INSERT ON bows BEGIN UPDATE bows SET name = 'tampered' WHERE uuid = NEW.uuid; END")
            assertIs<EquipmentCreateResult.NeedsIntervention>(store.equipment.create(bow()))
            assertEquals(before, contents(path))
        }
    }
    @Test fun `post update failure restores exact previous record`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            store.equipment.create(bow())
            val before = contents(path)
            rows(path, "CREATE TRIGGER corrupt_update AFTER UPDATE OF notes ON bows BEGIN UPDATE bows SET updated_nano = NULL WHERE uuid = NEW.uuid; END")
            assertIs<EquipmentUpdateResult.NeedsIntervention>(store.equipment.update(bow(), bow().copy(notes = "New")))
            assertEquals(before, contents(path))
        }
    }
    @Test fun `invalid names weights timestamps and identity ownership require explicit intervention`() = runBlocking<Unit> {
        val mutations = listOf("UPDATE bows SET name = ' '", "UPDATE bows SET drawWeightLbsBits = ${Float.NaN.toRawBits()}",
            "UPDATE bows SET drawWeightLbsBits = ${(-1f).toRawBits()}", "UPDATE bows SET created_nano = 1000000000",
            "UPDATE bows SET updated_epochSecond = NULL", "UPDATE bows SET updated_epochSecond = created_epochSecond - 1",
            "UPDATE entity_identities SET kind = 'ARROW_SET' WHERE kind = 'BOW'")
        mutations.forEachIndexed { index, sql ->
            val file = directory.resolve("corrupt-$index.db")
            openSessionStore(file).use { it.equipment.create(bow()) }
            rows(file, sql)
            val before = contents(file)
            openSessionStore(file).use { store ->
                val result = assertIs<EquipmentReadResult.NeedsIntervention>(store.equipment.readBow(bow().uuid))
                assertTrue(result.diagnostic.path.isNotBlank()); assertTrue(result.diagnostic.detail.isNotBlank())
                assertIs<EquipmentCreateResult.NeedsIntervention>(store.equipment.create(bow()))
                assertIs<EquipmentUpdateResult.NeedsIntervention>(store.equipment.update(bow(), bow()))
            }
            assertEquals(before, contents(file))
        }
    }
    @Test fun `arrow corruption and missing registry are discovered rather than silently normalized`() = runBlocking<Unit> {
        openSessionStore(path).use { it.equipment.create(arrows()) }
        rows(path, "UPDATE arrow_sets SET created_nano = NULL")
        openSessionStore(path).use { store ->
            assertEquals(listOf(arrows().uuid), assertIs<EquipmentDiscoveryResult.Found>(store.equipment.discoverArrowSets()).uuids)
            assertIs<EquipmentReadResult.NeedsIntervention>(store.equipment.readArrowSet(arrows().uuid))
        }
        val file = directory.resolve("registry.db")
        openSessionStore(file).use { it.equipment.create(bow()) }
        // Deliberately corrupt a file outside normal FK-enforced application operations.
        androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(file.toString()).use { c ->
            c.prepare("DELETE FROM entity_identities").use { it.step() }
        }
        openSessionStore(file).use { assertIs<EquipmentReadResult.NeedsIntervention>(it.equipment.readBow(bow().uuid)) }
    }
    @Test fun `orphan physical catalog rows cannot be reclaimed by session creation or JSON import`() = runBlocking<Unit> {
        openSessionStore(path).use { it.equipment.create(bow()) }
        androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(path.toString()).use { c ->
            c.prepare("DELETE FROM entity_identities").use { it.step() }
        }
        val source = EquipmentTestData.emptySession().copy(uuid = bow().uuid)
        val before = contents(path)
        openSessionStore(path).use { store ->
            assertIs<SessionCreateResult.Conflict>(store.create(archive(source)))
            val preview = assertIs<ArchiveImportPreviewResult.Ready>(store.previewImport(archive(source))).preview
            assertTrue(preview.plan.conflicts.isNotEmpty())
            assertIs<ArchiveImportResult.Conflict>(store.importArchive(preview))
        }
        assertEquals(before, contents(path))
    }
    @Test fun `same UUID in two physical entity tables is diagnosed without choosing an owner`() = runBlocking<Unit> {
        openSessionStore(path).use { it.equipment.create(bow()) }
        rows(path, "INSERT INTO arrow_sets (uuid,name) VALUES ('${bow().uuid}', 'Corrupt duplicate')")
        val before = contents(path)
        openSessionStore(path).use { store ->
            assertIs<EquipmentReadResult.NeedsIntervention>(store.equipment.readBow(bow().uuid))
            assertIs<EquipmentCreateResult.NeedsIntervention>(store.equipment.create(bow()))
        }
        assertEquals(before, contents(path))
    }
    @Test fun `closed store and IO failures remain distinct from absent catalog entries`() = runBlocking<Unit> {
        val store = openSessionStore(path)
        assertIs<EquipmentReadResult.NotFound>(store.equipment.readBow(id(999)))
        store.close()
        assertIs<EquipmentReadResult.TechnicalFailure>(store.equipment.readBow(id(999)))
        assertIs<EquipmentDiscoveryResult.TechnicalFailure>(store.equipment.discoverArrowSets())
        assertIs<EquipmentCreateResult.TechnicalFailure>(store.equipment.create(bow()))
        assertIs<EquipmentUpdateResult.TechnicalFailure>(store.equipment.update(arrows(), arrows()))
        val blocked = directory.resolve("not-a-directory")
        java.nio.file.Files.writeString(blocked, "Synthetic blocking file")
        openSessionStore(blocked.resolve("catalog.db")).use {
            assertIs<EquipmentReadResult.TechnicalFailure>(it.equipment.readBow(id(999)))
        }
    }
    @Test fun `cancellation after an actual catalog INSERT escapes and rolls back identity and row`() = runBlocking<Unit> {
        val cancel = java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null)
        val native = androidx.sqlite.driver.bundled.BundledSQLiteDriver()
        val driver = object : androidx.sqlite.SQLiteDriver by native {
            override fun open(fileName: String): androidx.sqlite.SQLiteConnection {
                val connection = native.open(fileName)
                return object : androidx.sqlite.SQLiteConnection by connection {
                    override fun prepare(sql: String): androidx.sqlite.SQLiteStatement {
                        val statement = connection.prepare(sql)
                        return object : androidx.sqlite.SQLiteStatement by statement {
                            override fun step(): Boolean {
                                val result = statement.step()
                                if (sql.startsWith("INSERT") && sql.contains("arrow_sets")) cancel.get()?.invoke()
                                return result
                            }
                        }
                    }
                }
            }
        }
        val database = androidx.room.Room.databaseBuilder<SessionDatabase>(path.toString()).setDriver(driver).build()
        RoomSessionStore(database).use { store ->
            store.equipment.discoverArrowSets()
            val before = contents(path)
            var propagated = false
            val task = launch {
                val job = currentCoroutineContext().job
                cancel.set { job.cancel() }
                try { store.equipment.create(arrows()) }
                catch (error: CancellationException) { propagated = true }
            }
            task.join()
            cancel.set(null)
            assertTrue(propagated)
            assertEquals(before, contents(path))
        }
    }
}

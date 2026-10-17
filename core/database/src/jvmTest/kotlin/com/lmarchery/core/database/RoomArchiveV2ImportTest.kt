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
import com.lmarchery.core.database.EquipmentTestData.tables
import com.lmarchery.core.export.*
import com.lmarchery.core.model.equipment.*
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.core.scoring.fixtures.ClassicTargetFixtures
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class RoomArchiveV2ImportTest {
    @TempDir lateinit var directory: Path
    private val path get() = directory.resolve("import-v2.db")
    private fun rich() = v2(listOf(session(), session(100, true)), listOf(bow()), listOf(arrows()))
    private val zero = ArchiveV2Counts(0, 0, 0, 0)

    @Test fun `whole archive imports all ten tables and reopens with exact content`() = runBlocking<Unit> {
        val source = rich()
        openSessionStore(path).use { store ->
            val result = assertIs<ArchiveV2ImportResult.Imported>(store.restoreV2(source))
            assertEquals(ArchiveV2Counts(2, 1, 1, 1), result.added); assertEquals(zero, result.identical)
            tables.forEach { assertTrue(rows(path, "SELECT COUNT(*) FROM $it").single().toInt() > 0, it) }
            assertEquals(source, store.fullV2())
            assertEquals(emptyList(), rows(path, "PRAGMA foreign_key_check"))
            assertEquals(listOf("ok"), rows(path, "PRAGMA quick_check"))
        }
        openSessionStore(path).use { assertEquals(source, it.fullV2()) }
    }
    @Test fun `equipment only import works without sessions or profiles`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            val source = v2(bows = listOf(bow()), arrows = listOf(arrows()))
            assertEquals(ArchiveV2Counts(0, 0, 1, 1), assertIs<ArchiveV2ImportResult.Imported>(store.restoreV2(source)).added)
            assertEquals(source, store.fullV2())
        }
    }
    @Test fun `empty archive is explicit no operation with no SQL writes or deletions`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            store.restoreV2(rich()); denyWrites(path); val before = contents(path)
            val p = store.previewV2(v2()); assertTrue(p.plan.isEmptyArchive)
            assertEquals(ArchiveV2ImportResult.AlreadyPresent(zero, true), store.importArchiveV2(p))
            assertEquals(before, contents(path))
        }
    }
    @Test fun `second identical import does no INSERT UPDATE or DELETE under rejecting triggers`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            val source = rich(); store.restoreV2(source); denyWrites(path); val before = contents(path)
            val result = assertIs<ArchiveV2ImportResult.AlreadyPresent>(store.restoreV2(source))
            assertEquals(ArchiveV2Counts(2, 1, 1, 1), result.identical); assertFalse(result.emptyArchive)
            assertEquals(before, contents(path))
        }
    }
    @Test fun `mixed import never touches existing records and preserves unrelated catalog entries`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            store.restoreV2(v2(listOf(session()), listOf(bow()), listOf(arrows(802))))
            val before = contents(path)
            tables.forEach { table -> listOf("UPDATE", "DELETE").forEach { operation ->
                rows(path, "CREATE TRIGGER no_${table}_$operation BEFORE $operation ON $table BEGIN SELECT RAISE(ABORT,'no rewrites'); END")
            } }
            rows(path, "CREATE TRIGGER no_old_bow BEFORE INSERT ON bows WHEN NEW.uuid='${id(800)}' BEGIN SELECT RAISE(ABORT,'no reinsertion'); END")
            val result = assertIs<ArchiveV2ImportResult.Imported>(store.restoreV2(rich()))
            assertEquals(ArchiveV2Counts(1, 0, 0, 1), result.added)
            assertEquals(ArchiveV2Counts(1, 1, 1, 0), result.identical)
            tables.forEach { assertTrue(contents(path).getValue(it).containsAll(before.getValue(it)), it) }
            assertEquals(listOf(id(801), id(802)), store.fullV2().arrowSets.map { it.uuid })
        }
    }
    @Test fun `empty material lists do not clear an existing catalog`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            store.equipment.create(bow()); store.equipment.create(arrows()); val before = contents(path, listOf("bows", "arrow_sets"))
            assertIs<ArchiveV2ImportResult.Imported>(store.restoreV2(v2(listOf(session()))))
            assertEquals(before, contents(path, listOf("bows", "arrow_sets")))
            assertEquals(id(800), store.fullV2().sessions.single().bowUuid)
        }
    }
    @Test fun `opaque references without any catalog row remain unresolved and unchanged`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            val source = v2(listOf(session())); store.restoreV2(source)
            assertEquals(source, store.fullV2()); assertTrue(store.fullV2().bows.isEmpty())
        }
    }
    @Test fun `bow full state conflict blocks every addition even at identical timestamps`() = conflict(
        v2(bows = listOf(bow())), v2(listOf(session()), listOf(bow().copy(notes = "Different")), listOf(arrows())), id(800))
    @Test fun `arrow set conflict is never resolved by a more recent timestamp`() = conflict(
        v2(arrows = listOf(arrows())), v2(bows = listOf(bow()), arrows = listOf(arrows().copy(spine = "different", updatedAt = now.plusSeconds(5)))), id(801))
    @Test fun `session metadata divergence blocks material additions`() = conflict(
        v2(listOf(session())), v2(listOf(session().copy(title = "Other")), listOf(bow())), id(1))
    @Test fun `impact provenance divergence at same UUID and times blocks entire import`() = runBlocking<Unit> {
        val original = rich(); val snapshot = original.toSnapshot(); val s = snapshot.sessions!!.first(); val end = s.ends.first()
        val altered = reconstructV2(snapshot.copy(sessions = listOf(s.copy(ends = listOf(end.copy(impacts = end.impacts.mapIndexed { i, impact ->
            if (i == 0) impact.copy(note = "Other impact note") else impact
        }))))))
        checkConflict(original, altered, id(11))
    }
    @Test fun `profile definition or edition conflict never substitutes a known profile`() = runBlocking<Unit> {
        val target = ClassicTargetFixtures.classic40.copy(uuid = id(900), isBuiltIn = false)
        val p = TargetProfileSnapshot(target, "edition 1")
        val original = v2(extraProfiles = listOf(p))
        for (changed in listOf(p.copy(regulatoryEdition = "edition 2"), p.copy(definition = target.copy(name = "Different")))) {
            val file = directory.resolve("profile-${changed.hashCode()}.db")
            openSessionStore(file).use { store ->
                store.restoreV2(original); val before = contents(file)
                val preview = store.previewV2(v2(bows = listOf(bow()), extraProfiles = listOf(changed)))
                assertEquals(listOf(id(900)), preview.plan.profiles.conflictingUuids)
                assertIs<ArchiveV2ImportResult.Conflict>(store.importArchiveV2(preview)); assertEquals(before, contents(file))
            }
        }
    }
    @Test fun `global UUID collisions cover material session end impact and custom profile kinds`() = runBlocking<Unit> {
        val source = rich()
        for (reserved in listOf(id(1), id(2), id(11))) {
            val file = directory.resolve("collision-$reserved.db")
            openSessionStore(file).use { store ->
                store.equipment.create(Bow(reserved, "Reserved", createdAt = now, updatedAt = now))
                val before = contents(file); val p = store.previewV2(source)
                assertTrue(p.plan.conflicts.any { it.uuid == reserved }); assertTrue(p.plan.sessions.conflictingUuids.isNotEmpty())
                assertIs<ArchiveV2ImportResult.Conflict>(store.importArchiveV2(p)); assertEquals(before, contents(file))
            }
        }
        val target = ClassicTargetFixtures.classic40.copy(uuid = id(900), isBuiltIn = false)
        checkConflict(v2(extraProfiles = listOf(TargetProfileSnapshot(target, null))), v2(arrows = listOf(arrows(900))), id(900))
    }
    @Test fun `bow versus arrow set ownership cannot be reassigned`() = conflict(v2(bows = listOf(bow())), v2(arrows = listOf(arrows(800))), id(800))
    @Test fun `impact versus incoming bow collision is found globally`() = conflict(v2(listOf(session())), v2(bows = listOf(bow(11))), id(11))
    @Test fun `corrupt local ownership blocks preview without claiming missing identities`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            store.fullV2(); rows(path, "INSERT INTO entity_identities VALUES('${id(800)}','END')")
            val before = contents(path)
            assertIs<ArchiveV2PreviewResult.NeedsIntervention>(store.previewImportV2(rich())); assertEquals(before, contents(path))
        }
    }
    @Test fun `preview is read only and its lists cannot be mutated`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            store.fullV2(); denyWrites(path); val before = contents(path); val p = store.previewV2(rich())
            assertFailsWith<UnsupportedOperationException> { (p.plan.bows.newUuids as MutableList).clear() }
            assertFailsWith<UnsupportedOperationException> { (p.plan.conflicts as MutableList).clear() }
            assertEquals(before, contents(path))
        }
    }
    @Test fun `stale preview after identical insertion by second store is rejected`() = runBlocking<Unit> {
        openSessionStore(path).use { one -> openSessionStore(path).use { two ->
            val p = one.previewV2(rich()); two.restoreV2(rich()); val before = contents(path)
            assertIs<ArchiveV2ImportResult.Conflict>(one.importArchiveV2(p)); assertEquals(before, contents(path))
            assertIs<ArchiveV2ImportResult.AlreadyPresent>(one.restoreV2(rich()))
        } }
    }
    @Test fun `unrelated catalog revision invalidates exact baseline even with unchanged plan`() = runBlocking<Unit> {
        openSessionStore(path).use { one -> openSessionStore(path).use { two ->
            one.equipment.create(bow(850)); val p = one.previewV2(rich())
            two.equipment.update(bow(850), bow(850).copy(name = "Other writer", updatedAt = now.plusSeconds(50)))
            val before = contents(path)
            assertIs<ArchiveV2ImportResult.Conflict>(one.importArchiveV2(p)); assertEquals(before, contents(path))
        } }
    }
    @Test fun `two simultaneous native writers produce one whole commit and one stale conflict`() = runBlocking<Unit> {
        openSessionStore(path).use { one -> openSessionStore(path).use { two ->
            val p1 = one.previewV2(rich()); val p2 = two.previewV2(rich()); val start = CompletableDeferred<Unit>()
            val jobs = listOf(async(Dispatchers.IO) { start.await(); one.importArchiveV2(p1) }, async(Dispatchers.IO) { start.await(); two.importArchiveV2(p2) })
            start.complete(Unit); val results = jobs.awaitAll()
            assertEquals(1, results.count { it is ArchiveV2ImportResult.Imported }); assertEquals(1, results.count { it is ArchiveV2ImportResult.Conflict })
            assertEquals(rich(), one.fullV2())
        } }
    }
    @Test fun `SQL failure after native bow insert rolls back sessions catalog profiles and identities`() = runBlocking<Unit> {
        val inserted = AtomicBoolean(false)
        hookedStore(path) { sql, _ -> if (sql.startsWith("INSERT") && sql.contains("`bows`")) inserted.set(true) }.use { store ->
            store.equipment.create(bow(850)); inserted.set(false)
            val p = store.previewV2(rich()); val before = contents(path)
            rows(path, "CREATE TRIGGER fail_arrows BEFORE INSERT ON arrow_sets BEGIN SELECT RAISE(ABORT,'late failure'); END")
            val result = assertIs<ArchiveV2ImportResult.TechnicalFailure>(store.importArchiveV2(p))
            assertEquals(ArchiveV2ImportResult.CommitOutcome.UNCONFIRMED, result.commitOutcome)
            assertTrue(inserted.get(), "A real native bow INSERT must have completed before failure")
            assertEquals(before, contents(path))
        }
        openSessionStore(path).use { assertEquals(v2(bows = listOf(bow(850))), it.fullV2()) }
    }
    @Test fun `late readback alteration of new catalog value causes full rollback`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            store.equipment.create(bow(850)); val p = store.previewV2(rich()); val before = contents(path)
            rows(path, "CREATE TRIGGER alter_new AFTER INSERT ON bows BEGIN UPDATE bows SET name='Unexpected' WHERE uuid=NEW.uuid; END")
            assertIs<ArchiveV2ImportResult.NeedsIntervention>(store.importArchiveV2(p)); assertEquals(before, contents(path))
        }
    }
    @Test fun `late trigger alteration of preexisting equipment causes full rollback`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            store.equipment.create(bow(850)); val p = store.previewV2(rich()); val before = contents(path)
            rows(path, "CREATE TRIGGER alter_old AFTER INSERT ON arrow_sets BEGIN UPDATE bows SET name='Unexpected' WHERE uuid='${id(850)}'; END")
            assertIs<ArchiveV2ImportResult.NeedsIntervention>(store.importArchiveV2(p)); assertEquals(before, contents(path))
        }
    }
    @Test fun `late trigger alteration of historical session causes full rollback`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            store.create(archive(session(100, true))); val p = store.previewV2(v2(listOf(session()), listOf(bow()), listOf(arrows()))); val before = contents(path)
            rows(path, "CREATE TRIGGER alter_session AFTER INSERT ON arrow_sets BEGIN UPDATE sessions SET title='Unexpected' WHERE uuid='${id(101)}'; END")
            assertIs<ArchiveV2ImportResult.NeedsIntervention>(store.importArchiveV2(p)); assertEquals(before, contents(path))
        }
    }
    @Test fun `late verification orphan introduced by trigger rolls back entire source`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            val p = store.previewV2(rich()); val before = contents(path)
            rows(path, "CREATE TRIGGER orphan AFTER INSERT ON arrow_sets BEGIN INSERT INTO entity_identities VALUES('${id(999)}','BOW'); END")
            assertIs<ArchiveV2ImportResult.NeedsIntervention>(store.importArchiveV2(p)); assertEquals(before, contents(path))
        }
    }
    @Test fun `caller cancellation after actual catalog insertion escapes and rolls back everything`() = runBlocking<Unit> {
        val cancel = AtomicReference<(() -> Unit)?>(null); val inserted = AtomicBoolean(false)
        hookedStore(path) { sql, _ -> if (sql.startsWith("INSERT") && sql.contains("`bows`")) {
            inserted.set(true); cancel.get()?.invoke()
        } }.use { store ->
            val p = store.previewV2(rich()); val before = contents(path); var propagated = false
            val job = launch {
                val caller = currentCoroutineContext().job; cancel.set { caller.cancel() }
                try { store.importArchiveV2(p) } catch (_: CancellationException) { propagated = true }
            }
            job.join(); cancel.set(null)
            assertTrue(inserted.get()); assertTrue(propagated); assertEquals(before, contents(path))
        }
        openSessionStore(path).use { assertEquals(v2(), it.fullV2()) }
    }
    @Test fun `lost acknowledgement after actual COMMIT is explicitly uncertain and recoverable by reread`() = runBlocking<Unit> {
        val fail = AtomicBoolean(false); val committed = AtomicBoolean(false)
        hookedStore(path) { sql, _ -> if (sql == "END TRANSACTION" && fail.compareAndSet(true, false)) {
            committed.set(true); throw IllegalStateException("Synthetic acknowledgement loss after COMMIT")
        } }.use { store ->
            val p = store.previewV2(rich()); fail.set(true)
            val result = assertIs<ArchiveV2ImportResult.TechnicalFailure>(store.importArchiveV2(p))
            assertTrue(committed.get()); assertEquals(ArchiveV2ImportResult.CommitOutcome.UNCONFIRMED, result.commitOutcome)
        }
        openSessionStore(path).use { store ->
            assertEquals(rich(), store.fullV2())
            assertIs<ArchiveV2ImportResult.AlreadyPresent>(store.restoreV2(rich()))
        }
    }
    @Test fun `legacy V1 codec import and exports remain exact and never touch existing catalog`() = runBlocking<Unit> {
        val source = archive(session()); val codec = ArchiveJsonCodec(); val bytes = codec.encode(source)
        openSessionStore(path).use { store ->
            store.equipment.create(bow()); store.equipment.create(arrows())
            val before = contents(path, listOf("bows", "arrow_sets"))
            val decoded = assertIs<ArchiveJsonRead.Accepted>(codec.decode(bytes)).archive
            val preview = assertIs<ArchiveImportPreviewResult.Ready>(store.previewImport(decoded)).preview
            assertIs<ArchiveImportResult.Imported>(store.importArchive(preview))
            assertEquals(before, contents(path, listOf("bows", "arrow_sets")))
            assertContentEquals(bytes, codec.encode(assertIs<ArchiveReadResult.Found>(store.readArchive()).archive))
        }
    }
    @Test fun `end to end two disk stores through versioned JSON preserve all content bits nanos and Unicode`() = runBlocking<Unit> {
        val source = rich(); val codec = ArchiveJsonCodec()
        val bytes = openSessionStore(path).use { store ->
            store.restoreV2(source); codec.encode(store.fullV2())
        }
        val decoded = assertIs<VersionedArchiveJsonRead.V2>(codec.decodeVersioned(bytes)).archive
        assertEquals(source, decoded)
        val target = directory.resolve("second.db")
        openSessionStore(target).use { assertIs<ArchiveV2ImportResult.Imported>(it.restoreV2(decoded)) }
        openSessionStore(target).use { store ->
            val restored = store.fullV2(); assertEquals(source, restored); assertContentEquals(bytes, codec.encode(restored))
            assertEquals(bow().drawWeightLbs!!.toRawBits(), restored.bows.single().drawWeightLbs!!.toRawBits())
            assertEquals(123456789, restored.bows.single().createdAt.nano)
            assertEquals(arrows().length, restored.arrowSets.single().length)
            assertEquals(2, restored.sessions.first().ends.first().impacts.first().repositions.size)
            assertEquals(session().ends.first().impacts.first(), restored.sessions.first().ends.first().impacts.first())
        }
    }
    @Test fun `closed store rejects an old candidate without recreating database`() = runBlocking<Unit> {
        val store = openSessionStore(path); val p = store.previewV2(rich()); val before = contents(path); store.close()
        assertIs<ArchiveV2ImportResult.TechnicalFailure>(store.importArchiveV2(p)); assertEquals(before, contents(path))
    }
    private fun conflict(old: DomainArchiveV2, incoming: DomainArchiveV2, id: String) = runBlocking<Unit> { checkConflict(old, incoming, id) }
    private suspend fun checkConflict(old: DomainArchiveV2, incoming: DomainArchiveV2, id: String) {
        openSessionStore(path).use { store ->
            store.restoreV2(old); denyWrites(path); val before = contents(path); val p = store.previewV2(incoming)
            assertTrue(p.plan.conflicts.any { it.uuid == id }, p.plan.conflicts.toString())
            assertIs<ArchiveV2ImportResult.Conflict>(store.importArchiveV2(p)); assertEquals(before, contents(path))
        }
    }
}

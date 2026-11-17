package com.lmarchery.core.database

import com.lmarchery.core.database.ArchiveV3DiskData as d
import com.lmarchery.core.database.ConfiguredWriteData as c
import com.lmarchery.core.database.EquipmentTestData as f
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.core.scoring.archive.v4.*
import com.lmarchery.feature.sessions.*
import com.lmarchery.feature.configured.*
import kotlinx.coroutines.runBlocking
import com.lmarchery.core.export.ArchiveV4JsonCodec
import com.lmarchery.core.export.ArchiveV4JsonRead
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Independent negative cases: real production controllers, writer and native disk files. */
class ImpactCancellationCounterreviewTest {
    @TempDir lateinit var directory: Path
    private val tables = d.tables + "impact_cancellations"

    private suspend fun prepare(h: AppIntegrationHarness, configured: Boolean) {
        if (configured) { h.editing(); h.tap(.105f) }
        else { h.onUi { h.legacy.configure(); h.legacy.createSession() }; h.ready()
            h.onUi { h.legacy.addImpact(NormalizedPoint(.105f, 0f)) }; h.ready() }
    }
    private suspend fun select(h: AppIntegrationHarness, configured: Boolean): Long = h.onUi {
        if (configured) {
            h.configured.selectImpact(h.context, h.state.selectedEnd!!.end.impacts.single().uuid)
            h.configured.beginCancellation(h.context); h.state.cancellation!!.token
        } else {
            val end = h.legacy.state.value.displayedEnd!!
            h.legacy.selectImpact(end.uuid, end.impacts.single().uuid); h.legacy.beginCancellation()
            h.legacy.state.value.cancellation!!.token
        }
    }

    @Test fun `invalid cancellation comment stays local without DML and can be corrected at the exact limit`() = runBlocking<Unit> {
        for (configured in listOf(false, true)) {
            val path = directory.resolve("comment-$configured.db")
            val watch = AtomicBoolean(false); val dml = AtomicInteger()
            val store = hookedStore(path) { sql, _ -> if (watch.get() && Regex("^(INSERT|UPDATE|DELETE)\\b", RegexOption.IGNORE_CASE).containsMatchIn(sql.trimStart())) dml.incrementAndGet() }
            AppIntegrationHarness(path, store).use { h ->
                prepare(h, configured); val token = select(h, configured)
                val before = f.contents(path, tables); watch.set(true)
                for (invalid in listOf("x".repeat(16385), "\uD800")) {
                    h.onUi {
                        if (configured) { h.configured.cancellationComment(token, invalid); h.configured.confirmCancellation(token) }
                        else { h.legacy.cancellationComment(token, invalid); h.legacy.confirmCancellation(token) }
                    }
                    if (configured) h.idle() else h.ready()
                    val dialog = if (configured) h.state.cancellation else h.legacy.state.value.cancellation
                    val pending = if (configured) h.state.pending != null else h.legacy.state.value.pending != null
                    println("invalid-comment configured=$configured phase=${dialog?.phase} pending=$pending DML=${dml.get()}")
                    assertFalse(pending, "An invalid local comment must not become an unrecoverable submitted intention")
                    assertEquals(CancellationPhase.LOCAL, dialog?.phase)
                    assertNotNull(if (configured) h.state.problem else h.legacy.state.value.problem)
                    assertEquals(0, dml.get()); assertEquals(before, f.contents(path, tables))
                }
                watch.set(false)
                val valid = "x".repeat(16380) + " 🏹 "
                assertEquals(16384, valid.length)
                h.onUi {
                    if (configured) { h.configured.cancellationComment(token, valid); h.configured.confirmCancellation(token) }
                    else { h.legacy.cancellationComment(token, valid); h.legacy.confirmCancellation(token) }
                }
                if (configured) h.idle() else h.ready()
                val saved = h.archiveV4()
                assertEquals(valid, saved.sessions.single().ends.single().end.cancellations.single().comment)
                assertEquals(saved, assertIs<ArchiveV4JsonRead.Accepted>(ArchiveV4JsonCodec().decode(ArchiveV4JsonCodec().encode(saved))).archive)
                assertEquals(before["impacts"], f.contents(path, tables)["impacts"])
            }
        }
    }

    private suspend fun conflictThenAbandon(configured: Boolean, reload: Boolean = false) {
        val path = directory.resolve("conflict-$configured-$reload.db")
        AppIntegrationHarness(path).use { h ->
            prepare(h, configured); select(h, configured)
            if (configured) {
                val old = h.state.saved!!
                val other = old.withMetadata(old.metadata.copy(notes = "separate writer"), old.updatedAt.plusSeconds(10))
                assertIs<ConfiguredWriteResult.Updated>(h.store.updateConfiguredSession(old, other)); h.synchronizeClock(other.updatedAt)
            } else {
                val old = h.legacy.state.value.saved!!; val session = old.sessions.single()
                val other = session.copy(notes = "separate writer", updatedAt = session.updatedAt.plusSeconds(10))
                assertIs<ConfiguredWriteResult.Updated>(h.store.updateSessionContent(old, validatedSessionContent(old.profiles.single(), other)))
                h.synchronizeClock(other.updatedAt)
            }
            val before = f.contents(path, tables)
            h.onUi { if (configured) h.configured.confirmCancellation(h.state.cancellation!!.token)
                else h.legacy.confirmCancellation(h.legacy.state.value.cancellation!!.token) }
            if (configured) h.idle() else h.ready()
            assertEquals(CancellationPhase.FAILED, (if (configured) h.state.cancellation else h.legacy.state.value.cancellation)?.phase)
            h.onUi { if (configured) h.configured.reconcile() else h.legacy.previewStored() }
            if (configured) h.idle() else h.ready()
            if (configured) { assertEquals(ConfiguredReconciliation.THIRD_STATE, h.state.reconciliation); assertFalse(h.state.canRetryExact) }
            else assertEquals(ProblemKind.CONFLICT, h.legacy.state.value.problem?.kind)
            assertEquals(before, f.contents(path, tables))
            val ticket = (if (configured) h.state.cancellation else h.legacy.state.value.cancellation)!!.token
            h.onUi {
                assertFalse(if (configured) h.configured.canAbandonCancellationDisplay(ticket - 1) else h.legacy.canAbandonCancellationDisplay(ticket - 1))
                assertTrue(if (configured) h.configured.canAbandonCancellationDisplay(ticket) else h.legacy.canAbandonCancellationDisplay(ticket))
            }
            f.denyWrites(path, tables)
            if (reload) {
                h.onUi { h.legacy.reloadAndDiscard() }; h.ready()
                assertNull(h.legacy.state.value.pending); assertNull(h.legacy.state.value.cancellation)
                assertEquals("separate writer", h.legacy.state.value.session!!.notes)
                h.onUi { h.legacy.returnToList() }
            } else h.onUi { if (configured) h.configured.abandonSubmittedAndClose() else h.legacy.discardAndReturnToList() }
            h.ready()
            println("abandon configured=$configured open=${h.state.open} screen=${h.legacy.state.value.screen} gate=${h.legacy.state.value.archiveOperationLocked}")
            assertFalse(h.state.open, "Explicit display abandonment must release the configured editor")
            assertEquals(SessionScreen.LIST, h.legacy.state.value.screen, "Explicit abandonment must return to the list")
            assertFalse(h.legacy.state.value.archiveOperationLocked)
            assertEquals(before, f.contents(path, tables)); assertTrue(f.rows(path, "PRAGMA foreign_key_check").isEmpty())
        }
    }
    @Test fun `historical cancellation conflict can be explicitly left without DML or reactivation`() = runBlocking<Unit> { conflictThenAbandon(false); conflictThenAbandon(false, reload = true) }
    @Test fun `configured cancellation conflict can be explicitly left and releases the shared gate`() = runBlocking<Unit> { conflictThenAbandon(true) }

    @Test fun `explicit abandonment of an uncertain commit never undoes or duplicates the stored cancellation`() = runBlocking<Unit> {
        for (configured in listOf(false, true)) {
            val path = directory.resolve("uncertain-display-$configured.db")
            val armed = AtomicBoolean(false); val writer = AtomicInteger()
            val store = hookedConnectionStore(path) { connection, sql, _ ->
                if (armed.get() && sql.startsWith("INSERT") && sql.contains("`impact_cancellations`")) writer.set(connection)
                if (sql == "END TRANSACTION" && writer.compareAndSet(connection, 0)) {
                    armed.set(false); throw IllegalStateException("lost native COMMIT acknowledgement")
                }
            }
            AppIntegrationHarness(path, store).use { h ->
                prepare(h, configured); val ticket = select(h, configured); armed.set(true)
                h.onUi { if (configured) h.configured.confirmCancellation(ticket) else h.legacy.confirmCancellation(ticket) }
                if (configured) h.idle() else h.ready()
                assertNotNull(if (configured) h.state.pending else h.legacy.state.value.pending)
                assertEquals(CancellationPhase.FAILED, (if (configured) h.state.cancellation else h.legacy.state.value.cancellation)?.phase)
                val saved = h.archiveV4(); assertEquals(1, saved.sessions.single().ends.single().end.cancellations.size)
                val rows = f.contents(path, tables); f.denyWrites(path, tables)
                h.onUi { if (configured) h.configured.abandonSubmittedAndClose() else h.legacy.discardAndReturnToList() }; h.ready()
                assertFalse(h.state.open); assertEquals(SessionScreen.LIST, h.legacy.state.value.screen)
                assertFalse(h.legacy.state.value.archiveOperationLocked)
                assertEquals(rows, f.contents(path, tables)); assertEquals(saved, h.archiveV4())
                h.onUi { if (configured) h.configured.open(saved.sessions.single().uuid) else h.legacy.openSession(saved.sessions.single().uuid) }
                if (configured) h.idle() else h.ready()
                val restored = if (configured) h.state.saved!!.ends.single().end else h.legacy.state.value.session!!.ends.single()
                assertEquals(1, restored.impacts.size); assertEquals(1, restored.cancellations.size); assertTrue(restored.activeImpacts.isEmpty())
                assertEquals(rows, f.contents(path, tables))
            }
        }
    }

    @Test fun `complete historical revision preserves the preexisting global configuration contract with and without cancellations`() = runBlocking<Unit> {
        val original = f.session()
        val profile = TargetProfileSnapshot(original.targetType, null)
        val revision = original.copy(distanceM = 70, arrowCountMode = com.lmarchery.core.model.session.ArrowCountMode.FIXED,
            arrowCountPerEnd = 5, scoringMode = com.lmarchery.core.model.session.ScoringMode.MANUAL_CORDON,
            isIndoor = false, updatedAt = f.now.plusSeconds(40))
        openSessionStore(directory.resolve("legacy-control.db")).use { store ->
            store.create(f.archive(original))
            assertIs<SessionUpdateResult.Updated>(store.update(f.archive(original), f.archive(revision)))
        }
        for (cancelled in listOf(false, true)) {
            val path = directory.resolve("legacy-projection-$cancelled.db")
            openSessionStore(path).use { store ->
                val initial = f.archive(original); store.create(initial)
                val s = if (cancelled) original.cancelImpact(original.ends.single().uuid, original.ends.single().impacts.first().uuid,
                    f.now.plusSeconds(20)) else original
                val expected: SessionContent = if (cancelled) validatedSessionContent(profile, s) else initial
                if (cancelled) assertIs<ConfiguredWriteResult.Updated>(store.updateSessionContent(initial, expected))
                val next = s.copy(distanceM = 70, arrowCountMode = com.lmarchery.core.model.session.ArrowCountMode.FIXED, arrowCountPerEnd = 5,
                    scoringMode = com.lmarchery.core.model.session.ScoringMode.MANUAL_CORDON,
                    isIndoor = false, updatedAt = f.now.plusSeconds(40))
                val preservedTables = tables - listOf("sessions", "session_configurations", "end_configurations")
                val before = f.contents(path, preservedTables); f.denyWrites(path, preservedTables)
                val result = com.lmarchery.data.RoomSessionRepository(store).updateContent(expected, validatedSessionContent(profile, next))
                println("legacy projection cancelled=$cancelled result=${result::class.simpleName}")
                assertIs<ConfiguredWriteResult.Updated>(result)
                val found = assertIs<ArchiveV4ReadResult.Found>(store.readArchiveV4()).archive.sessions.single()
                assertEquals(70, found.initialConfiguration.distanceM); assertEquals(5, found.initialConfiguration.arrowCountPerEnd)
                assertTrue(found.ends.all { it.configuration == found.initialConfiguration })
                assertEquals("LEGACY_HOMOGENEOUS", f.rows(path, "SELECT representation FROM session_configurations").single())
                assertEquals(s.ends, found.ends.map { it.end }); assertEquals(before, f.contents(path, preservedTables))
                assertEquals(next, assertIs<SessionReadResult.Found>(store.read(next.uuid)).session)
                val captureTables = listOf("session_configurations", "end_configurations")
                val captures = f.contents(path, captureTables); f.denyWrites(path, captureTables)
                val metadataOnly = next.withEquipmentReferences(f.id(5555), null, f.now.plusSeconds(50))
                assertIs<ConfiguredWriteResult.Updated>(store.updateSessionContent(validatedSessionContent(profile, next), validatedSessionContent(profile, metadataOnly)))
                assertEquals(captures, f.contents(path, captureTables))
                val complete = assertIs<ArchiveV4ReadResult.Found>(store.readArchiveV4()).archive
                val importedPath = directory.resolve("imported-configured-$cancelled.db")
                openSessionStore(importedPath).use { imported ->
                    val preview = assertIs<ArchiveV4PreviewResult.Ready>(imported.previewImportV4(complete)).preview
                    assertIs<ArchiveV4ImportResult.Imported>(imported.importArchiveV4(preview))
                    assertEquals("CONFIGURED", f.rows(importedPath, "SELECT representation FROM session_configurations").single())
                    val importedBefore = f.contents(importedPath, tables); f.denyWrites(importedPath, tables)
                    val illegal = metadataOnly.copy(distanceM = 90, updatedAt = f.now.plusSeconds(60))
                    val rejected = assertIs<ConfiguredWriteResult.Conflict>(imported.updateSessionContent(validatedSessionContent(profile, metadataOnly), validatedSessionContent(profile, illegal)))
                    assertEquals(ConfiguredWriteConflict.NOT_CONFIGURED, rejected.kind)
                    assertEquals(importedBefore, f.contents(importedPath, tables))
                }
            }
        }
    }

    @Test fun `V4 restoration failure after a real proof INSERT rolls back every added root`() = runBlocking<Unit> {
        val path = directory.resolve("restore.db"); val armed = AtomicBoolean(false); val inserted = AtomicBoolean(false)
        lateinit var baseline: DomainArchiveV4
        val s = c.rich(); val profiles = c.snapshot(s).profiles!!
        val cancelled = s.cancelImpact(s.ends.single().uuid, s.ends.single().end.impacts.first().uuid, f.now.plusSeconds(20))
        val incoming = assertIs<ArchiveV4Reconstruction.Accepted>(reconstructArchiveV4(snapshotArchiveV4(profiles, listOf(cancelled), emptyList(), emptyList()))).archive
        hookedStore(path) { sql, _ -> if (armed.get() && sql.startsWith("INSERT") && sql.contains("`impact_cancellations`")) { inserted.set(true); throw IllegalStateException("after actual V4 proof INSERT") } }.use { store ->
            store.restoreV2(d.source()); val before = f.contents(path, tables)
            baseline = assertIs<ArchiveV4ReadResult.Found>(store.readArchiveV4()).archive
            val preview = assertIs<ArchiveV4PreviewResult.Ready>(store.previewImportV4(incoming)).preview; assertTrue(preview.plan.canImport)
            armed.set(true); assertIs<ArchiveV4ImportResult.CommitUnconfirmed>(store.importArchiveV4(preview)); armed.set(false)
            assertTrue(inserted.get()); assertEquals(before, f.contents(path, tables))
        }
        openSessionStore(path).use { assertEquals(baseline, assertIs<ArchiveV4ReadResult.Found>(it.readArchiveV4()).archive) }
        assertEquals(listOf("ok"), f.rows(path, "PRAGMA integrity_check")); assertTrue(f.rows(path, "PRAGMA foreign_key_check").isEmpty())
    }
}

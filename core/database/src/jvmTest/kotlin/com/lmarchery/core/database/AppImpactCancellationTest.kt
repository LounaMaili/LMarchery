package com.lmarchery.core.database

import com.lmarchery.core.database.ArchiveV3DiskData as d
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.session.*
import com.lmarchery.core.export.*
import com.lmarchery.feature.sessions.*
import com.lmarchery.feature.archiveimport.ImportState
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Real production controllers/repository + native Room files; no Compose or finger claim. */
class AppImpactCancellationTest {
    @TempDir lateinit var directory: Path
    private val tables = d.tables + "impact_cancellations"

    @Test fun `both editors cancel a dialog without DML and confirm once preserving original arrows and freeing capacity`() = runBlocking<Unit> {
        for (configured in listOf(false, true)) {
            val path = directory.resolve("editor-$configured.db")
            val watching = AtomicBoolean(false)
            val dml = AtomicInteger(0)
            val store = hookedStore(path) { sql, _ ->
                if (watching.get() && Regex("^(INSERT|UPDATE|DELETE)\\b", RegexOption.IGNORE_CASE).containsMatchIn(sql.trimStart()))
                    dml.incrementAndGet()
            }
            lateinit var expected: com.lmarchery.core.scoring.archive.v4.DomainArchiveV4
            AppIntegrationHarness(path, store).use { h ->
                h.store.restoreV2(d.source())
                if (configured) { h.editing(); repeat(3) { h.tap(.105f) } }
                else {
                    h.onUi { h.legacy.configure(); h.legacy.createSession() }; h.ready()
                    repeat(3) { h.onUi { h.legacy.addImpact(NormalizedPoint(.105f, 0f)) }; h.ready() }
                }
                val before = h.archiveV4(); val rows = EquipmentTestData.contents(path, tables)
                val current = if (configured) h.state.selectedEnd!!.end else h.legacy.state.value.displayedEnd!!
                val chosen = current.impacts[1]
                watching.set(true)
                val firstToken = h.onUi {
                    if (configured) {
                        h.configured.selectImpact(h.context, chosen.uuid); h.configured.beginCancellation(h.context)
                        val token = h.state.cancellation!!.token
                        h.configured.cancellationComment(token, "abandoned"); h.configured.closeCancellation(token); token
                    } else {
                        h.legacy.selectImpact(current.uuid, chosen.uuid); h.legacy.beginCancellation()
                        val token = h.legacy.state.value.cancellation!!.token
                        h.legacy.cancellationComment(token, "abandoned"); h.legacy.closeCancellation(token); token
                    }
                }
                if (configured) h.idle() else h.ready()
                assertEquals(0, dml.get(), "Selection, comment and local cancellation must execute no native DML")
                assertEquals(rows, EquipmentTestData.contents(path, tables)); assertEquals(before, h.archiveV4())
                h.onUi {
                    if (configured) {
                        h.configured.selectImpact(h.context, chosen.uuid); h.configured.beginCancellation(h.context)
                        h.configured.confirmCancellation(firstToken); assertNull(h.state.pending)
                        h.configured.cancellationComment(h.state.cancellation!!.token, "double placed 🏹")
                    } else {
                        h.legacy.selectImpact(current.uuid, chosen.uuid); h.legacy.beginCancellation()
                        h.legacy.confirmCancellation(firstToken); assertNull(h.legacy.state.value.pending)
                        h.legacy.cancellationComment(h.legacy.state.value.cancellation!!.token, "double placed 🏹")
                    }
                    assertFalse(h.legacy.acquireExport()); h.equipment.open(); assertFalse(h.equipment.state.value.open)
                }
                if (configured) h.idle() else h.ready()
                assertEquals(0, dml.get(), "A stale confirmation ticket must execute no native DML")
                watching.set(false)
                assertEquals(rows, EquipmentTestData.contents(path, tables))
                val token = if (configured) h.state.cancellation!!.token else h.legacy.state.value.cancellation!!.token
                h.onUi { if (configured) {
                    h.configured.confirmCancellation(token)
                    assertEquals(current, h.state.saved!!.ends.single { it.uuid == current.uuid }.end)
                    h.configured.confirmCancellation(token)
                } else {
                    h.legacy.confirmCancellation(token)
                    assertEquals(current, h.legacy.state.value.displayedEnd)
                    h.legacy.confirmCancellation(token)
                } }
                if (configured) h.idle() else h.ready()
                val state = if (configured) h.state.cancellation else h.legacy.state.value.cancellation
                assertEquals(CancellationPhase.CONFIRMED, state?.phase)
                val after = h.archiveV4(); val owner = after.sessions.single { it.ends.any { e -> e.uuid == current.uuid } }
                val end = owner.ends.single { it.uuid == current.uuid }.end
                assertEquals(current.impacts, end.impacts); assertEquals(chosen.uuid, end.cancellations.single().impactUuid)
                assertEquals(listOf(1, 3), end.activeImpacts.map { it.index })
                assertEquals(before.sessions.filter { it.uuid != owner.uuid }, after.sessions.filter { it.uuid != owner.uuid })
                val committedRows = EquipmentTestData.contents(path, tables)
                h.onUi { if (configured) h.configured.confirmCancellation(token) else h.legacy.confirmCancellation(token) }
                assertEquals(committedRows, EquipmentTestData.contents(path, tables))
                h.onUi { if (configured) h.configured.closeCancellation(token) else h.legacy.closeCancellation(token) }
                if (configured) h.tap(.3f) else { h.onUi { h.legacy.addImpact(NormalizedPoint(.3f, 0f)) }; h.ready() }
                expected = h.archiveV4()
                val filled = expected.sessions.single { it.uuid == owner.uuid }.ends.single { it.uuid == current.uuid }.end
                assertEquals(listOf(1, 2, 3, 4), filled.impacts.map { it.index }); assertEquals(3, filled.activeImpacts.size)
                if (configured) assertFalse(h.state.canAdd) else assertFalse(h.legacy.state.value.canAddImpact)
                assertRecipeIntegrity(path)
            }
            openSessionStore(path).use { store -> assertEquals(expected, assertIs<ArchiveV4ReadResult.Found>(store.readArchiveV4()).archive) }
        }
    }

    @Test fun `real SAF coordinators export and restore cancellation proof then report idempotent reimport`() = runBlocking<Unit> {
        lateinit var bytes: ByteArray; lateinit var expected: com.lmarchery.core.scoring.archive.v4.DomainArchiveV4
        AppIntegrationHarness(directory.resolve("source.db")).use { h ->
            h.editing(); h.tap(.105f)
            h.onUi { h.configured.selectImpact(h.context, h.state.selectedEnd!!.end.impacts.single().uuid); h.configured.beginCancellation(h.context) }
            h.change { h.configured.confirmCancellation(h.state.cancellation!!.token) }
            h.onUi { h.configured.closeCancellation(h.state.cancellation!!.token) }; h.closeEditor()
            expected = h.archiveV4(); bytes = h.export(4, "cancelled.json")
            assertEquals(expected, assertIs<ArchiveV4JsonRead.Accepted>(ArchiveV4JsonCodec().decode(bytes)).archive)
        }
        AppIntegrationHarness(directory.resolve("target.db")).use { h ->
            assertIs<ImportState.PreviewV4>(h.preview("restore.json", bytes))
            assertIs<ImportState.SucceededV4>(h.confirm()); assertEquals(expected, h.archiveV4())
            EquipmentTestData.denyWrites(h.path, tables)
            assertIs<ImportState.PreviewV4>(h.preview("again.json", bytes))
            assertTrue(assertIs<ImportState.SucceededV4>(h.confirm()).alreadyPresent); assertEquals(expected, h.archiveV4())
            assertRecipeIntegrity(h.path)
        }
    }

    @Test fun `both editors retain uncertain cancellation and reconcile without repeating a native write`() = runBlocking<Unit> {
        for (configured in listOf(false, true)) {
            val path = directory.resolve("uncertain-$configured.db")
            val armed = AtomicBoolean(false); val writer = AtomicInteger(0)
            val store = hookedConnectionStore(path) { connection, sql, _ ->
                if (armed.get() && sql.startsWith("INSERT") && sql.contains("`impact_cancellations`")) writer.set(connection)
                if (sql == "END TRANSACTION" && writer.compareAndSet(connection, 0)) {
                    armed.set(false); throw IllegalStateException("Lost native cancellation acknowledgement")
                }
            }
            AppIntegrationHarness(path, store).use { h ->
                if (configured) { h.editing(); h.tap(.15f) }
                else { h.onUi { h.legacy.configure(); h.legacy.createSession() }; h.ready()
                    h.onUi { h.legacy.addImpact(NormalizedPoint(.15f, 0f)) }; h.ready() }
                h.onUi {
                    if (configured) { h.configured.selectImpact(h.context, h.state.selectedEnd!!.end.impacts.single().uuid); h.configured.beginCancellation(h.context) }
                    else { h.legacy.selectImpact(h.legacy.state.value.displayedEnd!!.uuid, h.legacy.state.value.displayedEnd!!.impacts.single().uuid); h.legacy.beginCancellation() }
                }
                val token = if (configured) h.state.cancellation!!.token else h.legacy.state.value.cancellation!!.token
                armed.set(true)
                h.onUi { if (configured) h.configured.confirmCancellation(token) else h.legacy.confirmCancellation(token) }
                if (configured) h.idle() else h.ready()
                assertNotNull(if (configured) h.state.pending else h.legacy.state.value.pending)
                assertEquals(CancellationPhase.FAILED, (if (configured) h.state.cancellation else h.legacy.state.value.cancellation)?.phase)
                val exact = h.archiveV4(); val rows = EquipmentTestData.contents(path, tables)
                EquipmentTestData.denyWrites(path, tables)
                h.onUi {
                    if (configured) { h.configured.confirmCancellation(token); h.configured.closeCancellation(token); assertNotNull(h.state.pending); h.configured.reconcile() }
                    else { h.legacy.confirmCancellation(token); h.legacy.closeCancellation(token); assertNotNull(h.legacy.state.value.pending); h.legacy.previewStored() }
                }
                if (configured) h.idle() else h.ready()
                assertNull(if (configured) h.state.pending else h.legacy.state.value.pending)
                assertEquals(CancellationPhase.CONFIRMED, (if (configured) h.state.cancellation else h.legacy.state.value.cancellation)?.phase)
                assertEquals(rows, EquipmentTestData.contents(path, tables)); assertEquals(exact, h.archiveV4())
            }
        }
    }
}

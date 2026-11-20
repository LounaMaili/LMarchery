package com.lmarchery.core.database

import com.lmarchery.core.database.ArchiveV3DiskData as d
import com.lmarchery.core.database.ConfiguredWriteData as c
import com.lmarchery.core.database.EquipmentTestData as f
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.BoundaryValue
import com.lmarchery.core.model.session.SessionStatus
import com.lmarchery.data.EquipmentKind
import com.lmarchery.feature.sessions.configuredHistoryLabels
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Production controllers/repository, native Room and real disk files; no Android UI is inferred. */
class ConfiguredHistoryReadOnlyIntegrationTest {
    @TempDir lateinit var directory: Path

    @Test fun `all configured statuses can be discovered consulted and reopened without any DML`() = runBlocking<Unit> {
        val sessions = listOf(c.draft(), c.rich(), c.rich().complete(f.now.plusSeconds(20)))
        for (session in sessions) {
            val path = directory.resolve("consult-${session.status}.db")
            val writes = AtomicInteger()
            val store = hookedStore(path) { sql, _ ->
                if (listOf("INSERT", "UPDATE", "DELETE").any { sql.trimStart().startsWith(it) }) writes.incrementAndGet()
            }
            val expected = AppIntegrationHarness(path, store).use { h ->
                h.store.restoreV2(d.source())
                assertIs<ConfiguredWriteResult.Created>(h.store.createConfiguredSession(session))
                val before = d.contents(path)
                val archive = h.archive()
                val count = writes.get()
                f.denyWrites(path, d.tables)
                h.refresh()
                assertEquals(session, h.legacy.state.value.entries.single { it.uuid == session.uuid }.configured)
                h.onUi { h.configured.open(session.uuid) }; h.idle()
                assertEquals(session, h.state.saved)
                assertEquals(session.status == SessionStatus.COMPLETED,
                    configuredHistoryLabels(session.status).openActionLabel == "Consulter — lecture seule")
                for (end in session.ends) h.onUi { h.configured.selectEnd(end.uuid) }
                h.closeEditor()
                h.onUi { h.configured.open(session.uuid) }; h.idle()
                assertNull(h.state.problem); assertNull(h.state.pending)
                assertEquals(count, writes.get())
                assertEquals(before, d.contents(path)); assertEquals(archive, h.archive())
                assertRecipeIntegrity(path)
                archive
            }
            openSessionStore(path).use { reopened -> assertEquals(expected, reopened.fullV3()) }
            assertRecipeIntegrity(path)
        }
    }

    @Test fun `completion invalidates previous metadata and target callbacks without any SQL write`() = runBlocking<Unit> {
        val path = directory.resolve("completed-callbacks.db")
        val writes = AtomicInteger()
        val store = hookedStore(path) { sql, _ ->
            if (listOf("INSERT", "UPDATE", "DELETE").any { sql.trimStart().startsWith(it) }) writes.incrementAndGet()
        }
        val expected = AppIntegrationHarness(path, store).use { h ->
            h.store.restoreV2(d.source())
            val initial = c.rich()
            assertIs<ConfiguredWriteResult.Created>(h.store.createConfiguredSession(initial))
            h.onUi { h.configured.open(initial.uuid) }; h.idle()
            val oldContext = h.context
            h.onUi { h.configured.editMetadata() }
            val oldToken = h.token
            h.onUi { h.configured.cancelForm(oldToken) }
            h.change { h.configured.complete() }
            assertEquals(SessionStatus.COMPLETED, h.state.saved!!.status)
            val exact = h.state.saved
            val archive = h.archive()
            val before = d.contents(path)
            val count = writes.get()
            f.denyWrites(path, d.tables)
            h.onUi {
                h.configured.editMetadata(); h.configured.nextEnd(h.state.epoch); h.configured.start(); h.configured.complete()
                h.configured.title(oldToken, "Forbidden title")
                h.configured.chooseEquipment(oldToken, EquipmentKind.BOW, null)
                h.configured.chooseEquipment(oldToken, EquipmentKind.ARROW_SET, null)
                h.configured.confirmForm(oldToken)
                for (context in listOf(oldContext, h.context)) {
                    h.configured.tapped(context, NormalizedPoint(.4f, 0f))
                    h.configured.precision(context, true)
                    assertNull(h.configured.beginAim(context, NormalizedPoint(.4f, 0f)))
                    h.configured.confirmPosition(context)
                    h.configured.selectImpact(context, initial.ends.first().end.impacts.first().uuid)
                    h.configured.beginReposition(context)
                    h.configured.repositionPoint(context, NormalizedPoint(.5f, 0f))
                    h.configured.repositionReason(context, "Forbidden reposition")
                    h.configured.confirmReposition(context)
                    h.configured.beginDecision(context); h.configured.exceptional(context, true)
                    h.configured.scoreValue(context, BoundaryValue(9))
                    h.configured.decisionReason(context, "Forbidden judgment"); h.configured.confirmDecision(context)
                }
                h.configured.validateEnd()
            }
            h.idle()
            assertFalse(h.state.canForm); assertFalse(h.state.canAdd); assertFalse(h.state.canSelectImpact)
            assertNull(h.state.form); assertNull(h.state.edit); assertNull(h.state.pending); assertNull(h.state.problem)
            assertEquals(exact, h.state.saved)
            assertEquals(count, writes.get()); assertEquals(before, d.contents(path))
            assertEquals(archive, h.archive()); assertRecipeIntegrity(path)
            archive
        }
        openSessionStore(path).use { reopened -> assertEquals(expected, reopened.fullV3()) }
        assertRecipeIntegrity(path)
    }
}

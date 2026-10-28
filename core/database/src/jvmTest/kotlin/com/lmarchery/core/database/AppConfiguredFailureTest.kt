package com.lmarchery.core.database

import com.lmarchery.core.database.ArchiveV3DiskData as d
import com.lmarchery.core.database.EquipmentTestData as f
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.feature.configured.*
import com.lmarchery.feature.export.ExportState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.*
import kotlin.test.*

class AppConfiguredFailureTest {
    @TempDir lateinit var directory:Path
    private val path get()=directory.resolve("configured-pipeline.db")
    @Test fun `actual impact INSERT rolls back controller retains intent then exact explicit retry succeeds`() = runBlocking<Unit> {
        val inserted=AtomicBoolean()
        val store=hookedStore(path) { sql,_->if(sql.startsWith("INSERT") && sql.contains("`impacts`"))inserted.set(true) }
        AppIntegrationHarness(path,store).use { h ->
            h.editing();val before=d.contents(path);val saved=h.state.saved
            d.raw(path,"CREATE TRIGGER fail_current_decision BEFORE INSERT ON score_decisions BEGIN SELECT RAISE(ABORT,'synthetic late failure'); END")
            h.onUi { h.configured.tapped(h.context,NormalizedPoint(.105f,-0.0f)) };h.idle()
            val pending=requireNotNull(h.state.pending);assertTrue(inserted.get());assertEquals(saved,h.state.saved)
            assertEquals(before,d.contents(path));assertEquals(ConfiguredProblemKind.UNCONFIRMED,h.state.problem!!.kind)
            h.assertOtherOperationsLocked();h.onUi { h.configured.retryExact() };assertEquals(before,d.contents(path))
            d.raw(path,"DROP TRIGGER fail_current_decision")
            h.onUi { h.configured.reconcile() };h.idle();assertEquals(ConfiguredReconciliation.EXPECTED,h.state.reconciliation)
            assertSame(pending,h.state.pending);h.change { h.configured.retryExact() }
            assertSame(pending.request.proposed,h.state.saved);assertEquals(1,h.state.selectedEnd!!.end.impacts.size)
            assertEquals(h.state.saved,h.archive().sessions.single());assertRecipeIntegrity(path)
        }
    }
    @Test fun `lost native COMMIT acknowledgement reaches real controller and read reconciliation performs zero DML`() = runBlocking<Unit> {
        val arm=AtomicBoolean();val pendingCommit=AtomicBoolean();val committed=AtomicBoolean()
        val writes=AtomicInteger()
        val store=hookedStore(path) { sql,_->
            if(sql.startsWith("UPDATE") && sql.contains("`sessions`")){writes.incrementAndGet();if(arm.get())pendingCommit.set(true)}
            if(sql=="END TRANSACTION" && pendingCommit.compareAndSet(true,false)) {arm.set(false);committed.set(true);throw IllegalStateException("Synthetic acknowledgement lost after actual COMMIT")}
        }
        lateinit var proposed:com.lmarchery.core.scoring.configured.ConfiguredSession
        AppIntegrationHarness(path,store).use { h ->
            h.editing();val saved=h.state.saved;arm.set(true)
            h.onUi { h.configured.editMetadata();h.configured.title(h.token," Acquired commit 🏹 ");h.configured.confirmForm(h.token) };h.idle()
            val pending=requireNotNull(h.state.pending);proposed=pending.request.proposed
            assertTrue(committed.get());assertEquals(saved,h.state.saved);assertEquals(proposed,h.archive().sessions.single())
            assertEquals(ConfiguredProblemKind.UNCONFIRMED,h.state.problem!!.kind)
            val count=writes.get();h.assertOtherOperationsLocked();h.onUi { h.configured.retryExact() };assertEquals(count,writes.get())
            f.denyWrites(path,d.tables);h.onUi { h.configured.reconcile() };h.idle()
            assertNull(h.state.pending);assertEquals(proposed,h.state.saved);assertEquals(count,writes.get())
            h.closeEditor();assertFalse(h.legacy.state.value.archiveOperationLocked)
        }
        openSessionStore(path).use { assertEquals(proposed,it.fullV3().sessions.single());assertRecipeIntegrity(path) }
    }
    @Test fun `actual cancellation after SQL INSERT rolls back without killing UI actor and can be reconciled explicitly`() = runBlocking<Unit> {
        val cancel=AtomicReference<(() -> Unit)?>(null);val inserted=AtomicBoolean()
        val store=hookedStore(path) { sql,_->if(sql.startsWith("INSERT") && sql.contains("`sessions`")) {inserted.set(true);cancel.getAndSet(null)?.invoke()} }
        AppIntegrationHarness(path,store).use { h ->
            h.store.fullV3();val before=d.contents(path)
            h.onUi { h.configured.create();h.configured.title(h.token,"Cancelled write") }
            cancel.set { h.job.children.forEach { it.cancel(CancellationException("After actual INSERT")) } }
            h.onUi { h.configured.confirmForm(h.token) };h.idle()
            val pending=requireNotNull(h.state.pending);assertTrue(inserted.get());assertTrue(h.job.isActive)
            assertEquals(before,d.contents(path));assertNull(h.state.saved);assertTrue(h.legacy.state.value.archiveOperationLocked)
            h.onUi { h.configured.reconcile() };h.idle();assertEquals(ConfiguredReconciliation.ABSENT,h.state.reconciliation)
            h.change { h.configured.retryExact() };assertSame(pending.request.proposed,h.state.saved);assertEquals(1,h.archive().sessions.size)
        }
    }
    @Test fun `two real UI actors lose no candidate on concurrent revision and reject automatic third state rebase`() = runBlocking<Unit> {
        val writes=AtomicInteger()
        fun observer(sql:String,ignored:Boolean) { if(sql.startsWith("UPDATE") && sql.contains("`sessions`"))writes.incrementAndGet() }
        AppIntegrationHarness(path,hookedStore(path,::observer)).use { one ->
            one.editing();val original=one.state.saved!!;one.closeEditor()
            AppIntegrationHarness(path,hookedStore(path,::observer)).use { two ->
                one.onUi { one.configured.open(original.uuid) };two.onUi { two.configured.open(original.uuid) };one.idle();two.idle();two.synchronizeClock(original.updatedAt)
                for((h,title) in listOf(one to "Writer one",two to "Writer two"))h.onUi { h.configured.editMetadata();h.configured.title(h.token,title) }
                val start=CompletableDeferred<Unit>()
                listOf(one,two).map { h->async(Dispatchers.Default) {start.await();h.onUi {h.configured.confirmForm(h.token)};h.idle()} }.also { start.complete(Unit);it.awaitAll() }
                val winner=listOf(one,two).single { it.state.pending==null };val loser=listOf(one,two).single { it.state.pending!=null }
                val pending=loser.state.pending!!;assertEquals(ConfiguredProblemKind.REVISION,loser.state.problem!!.kind)
                assertEquals(winner.state.saved!!.updatedAt,pending.request.proposed.updatedAt) // Equal times cannot authorize both revisions.
                val stored=one.archive();assertEquals(winner.state.saved,stored.sessions.single());val count=writes.get()
                loser.onUi { loser.configured.reconcile() };loser.idle();assertEquals(ConfiguredReconciliation.THIRD_STATE,loser.state.reconciliation)
                loser.onUi { loser.configured.retryExact() };assertEquals(count,writes.get());assertSame(pending,loser.state.pending)
                loser.onUi { loser.configured.abandonSubmittedAndClose() };loser.ready()
                loser.onUi { loser.configured.open(original.uuid) };loser.idle()
                loser.onUi { loser.configured.editMetadata();loser.configured.title(loser.token,"Explicitly resolved") };loser.change { loser.configured.confirmForm(loser.token) }
                assertEquals("Explicitly resolved",one.archive().sessions.single().metadata.title)
                assertRecipeIntegrity(path)
            }
        }
    }
    @Test fun `corrupt capture cannot become editable or exportable and no controller repairs it`() = runBlocking<Unit> {
        AppIntegrationHarness(path).use { h ->
            h.editing();val uuid=h.state.saved!!.uuid;h.closeEditor()
            d.raw(path,"UPDATE end_configurations SET regulatoryEdition='contradictory'")
            val before=d.contents(path);f.denyWrites(path,d.tables)
            h.onUi { h.configured.open(uuid) };h.idle()
            assertNull(h.state.saved);assertEquals(ConfiguredProblemKind.INTERVENTION,h.state.problem!!.kind)
            h.onUi { h.configured.close() };h.ready()
            h.onUi { h.exporter.beginV3() }
            assertIs<ExportState.Failed>(withTimeout(15000) {h.exporter.state.first {it is ExportState.Failed}})
            assertFalse(h.legacy.state.value.archiveOperationLocked);assertEquals(0,h.documents.outputs.get());assertEquals(before,d.contents(path))
        }
    }
}

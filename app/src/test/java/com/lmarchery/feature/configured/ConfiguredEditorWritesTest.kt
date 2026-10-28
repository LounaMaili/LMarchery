package com.lmarchery.feature.configured

import com.lmarchery.backup.V3TestData as f
import com.lmarchery.core.database.*
import com.lmarchery.core.model.NormalizedPoint
import kotlinx.coroutines.*
import java.io.IOException
import kotlin.test.*

class ConfiguredEditorWritesTest {
    @Test fun `double creation is blocked before acknowledgement exact candidate retained`(): Unit = runBlocking<Unit> {
        EditorHarness().use { h -> h.hold=CompletableDeferred(); h.c.create(); h.c.title(h.token,"One"); val token=h.token
            h.c.confirmForm(token); val intent=h.ui.pending!!; h.c.confirmForm(token); h.c.create(); h.c.close(); h.c.retryExact()
            assertEquals(1,h.requests.size); assertEquals(1,h.uuids); assertNull(h.ui.saved); assertTrue(h.ui.busy); assertTrue(h.gate)
            assertEquals(token,intent.context.form!!.token); h.hold!!.complete(Unit); yield(); assertEquals(intent.request.proposed,h.ui.saved); assertNull(h.ui.pending)
        }
    }
    @Test fun `repeated confirmation cannot add another impact after acknowledgement`(): Unit = EditorHarness().use { h ->
        h.editing(); h.c.precision(h.ctx,true); val old=h.ctx; val token=h.c.beginAim(old,NormalizedPoint(.15f,0f))!!
        h.c.finishAim(token,NormalizedPoint(.15f,0f)); val confirm=h.ctx; h.c.confirmPosition(confirm); h.c.confirmPosition(confirm)
        assertEquals(1,h.ui.selectedEnd!!.end.impacts.size); assertEquals(4,h.requests.size)
    }
    @Test fun `lost acknowledgement with proposed stored is reconciled by read only without UUID regeneration`(): Unit = EditorHarness().use { h ->
        h.editing(); h.write={ h.stored=it.proposed; ConfiguredWriteResult.CommitUnconfirmed(it,IOException()) }; h.tap(.15f)
        val pending=h.ui.pending!!; val count=h.requests.size; val ids=h.uuids
        assertEquals(ConfiguredProblemKind.UNCONFIRMED,h.ui.problem!!.kind); assertTrue(h.ui.saved!!.ends.single().end.impacts.isEmpty()); assertTrue(h.gate)
        h.c.retryExact(); h.c.nextEnd(); h.c.editMetadata(); h.tap(.25f); assertEquals(count,h.requests.size)
        h.c.reconcile(); assertEquals(pending.request.proposed,h.ui.saved); assertNull(h.ui.pending); assertEquals(count,h.requests.size); assertEquals(ids,h.uuids)
    }
    @Test fun `read expected permits only explicit retry with same identities dates and exact form`(): Unit = EditorHarness().use { h ->
        h.editing(); h.write={ ConfiguredWriteResult.CommitUnconfirmed(it,IOException()) }; h.c.nextEnd(); val token=h.token; h.c.explicitNext(token,true); h.c.confirmForm(token)
        val exact=h.ui.pending!!; val count=h.requests.size; val ids=h.uuids; h.c.reconcile(); assertEquals(ConfiguredReconciliation.EXPECTED,h.ui.reconciliation)
        assertSame(exact,h.ui.pending); assertEquals(count,h.requests.size); h.write=null; h.c.retryExact()
        assertSame(exact.request.proposed,h.requests.last().proposed); assertEquals(exact.request,h.requests.last()); assertEquals(ids,h.uuids)
    }
    @Test fun `failed create needs explicit absence read before retrying same UUID`(): Unit = EditorHarness().use { h ->
        h.write={ ConfiguredWriteResult.TechnicalFailure(it,IOException()) }; h.draft(); val intent=h.ui.pending!!; h.c.retryExact(); assertEquals(1,h.requests.size)
        h.c.reconcile(); assertEquals(ConfiguredReconciliation.ABSENT,h.ui.reconciliation); h.write=null; h.c.retryExact()
        assertEquals(intent.request,h.requests.last()); assertEquals(1,h.uuids); assertNotNull(h.ui.saved)
    }
    @Test fun `concurrent third state keeps expected proposed and refuses automatic rebase`(): Unit = EditorHarness().use { h ->
        h.editing(); val expected=h.ui.saved!!; h.write={ h.stored=expected.withMetadata(expected.metadata.copy(title="Other writer"),h.now)
            ConfiguredWriteResult.Conflict(it,ConfiguredWriteConflict.REVISION,StorageDiagnostic("session","Synthetic concurrent writer")) }
        h.tap(.15f); val intent=h.ui.pending!!; h.c.reconcile(); assertEquals(ConfiguredReconciliation.THIRD_STATE,h.ui.reconciliation)
        val n=h.requests.size; h.c.retryExact(); h.c.start(); h.c.confirmPosition(h.ctx); assertEquals(n,h.requests.size); assertSame(intent,h.ui.pending)
        assertEquals(expected,(intent.request as ConfiguredWriteRequest.Update).expected); assertEquals("Other writer",h.ui.compared!!.metadata.title)
    }
    @Test fun `all writer conflict categories remain distinguishable and preserve exact request`() {
        for (kind in ConfiguredWriteConflict.entries) EditorHarness().use { h -> h.write={ ConfiguredWriteResult.Conflict(it,kind,StorageDiagnostic("path","Conflict")) }; h.draft()
            assertEquals(kind.name,h.ui.problem!!.kind.name); assertNotNull(h.ui.pending); assertNull(h.ui.saved); assertTrue(h.gate) }
    }
    @Test fun `intervention outcomes and SQL technical failure never become success`() {
        for (outcome in ConfiguredCommitOutcome.entries) EditorHarness().use { h -> h.write={ ConfiguredWriteResult.NeedsIntervention(it,
            ArchiveV3StorageDiagnostic(ArchiveV3StorageIssueKind.DOMAIN,"config","Broken"),outcome) }; h.draft()
            assertNotNull(h.ui.pending); assertNull(h.ui.saved); assertEquals(if(outcome==ConfiguredCommitOutcome.UNCONFIRMED) ConfiguredProblemKind.UNCONFIRMED else ConfiguredProblemKind.INTERVENTION,h.ui.problem!!.kind) }
    }
    @Test fun `already applied full request is acknowledged without altering values`(): Unit = EditorHarness().use { h -> h.write={ ConfiguredWriteResult.AlreadyApplied(it) }; h.draft()
        assertEquals(h.requests.single().proposed,h.ui.saved); assertNull(h.ui.pending); assertTrue(h.ui.confirmation!!.contains("Déjà")) }
    @Test fun `acknowledgement of different request cannot replace exact candidate`(): Unit = EditorHarness().use { h ->
        h.write={ ConfiguredWriteResult.Created(ConfiguredWriteRequest.Create(f.mixed())) }; h.draft()
        assertNull(h.ui.saved); assertNotNull(h.ui.pending); assertEquals(ConfiguredProblemKind.UNCONFIRMED,h.ui.problem!!.kind)
    }
    @Test fun `secondary refresh failure after known commit does not become uncertain`(): Unit = EditorHarness(onSaved={ error("Secondary refresh") }).use { h -> h.draft()
        assertNotNull(h.ui.saved); assertNull(h.ui.pending); assertNull(h.ui.problem); assertTrue(h.ui.confirmation!!.contains("commit confirmé")) }
    @Test fun `read failure retains candidate and never authorizes retry`(): Unit = EditorHarness().use { h ->
        h.write={ ConfiguredWriteResult.CommitUnconfirmed(it,IOException()) }; h.draft(); val exact=h.ui.pending
        h.readOverride=ConfiguredSessionReadResult.TechnicalFailure(IOException()); h.c.reconcile(); assertSame(exact,h.ui.pending); assertFalse(h.ui.canRetryExact)
    }
    @Test fun `missing expected is not permission to recreate`(): Unit = EditorHarness().use { h -> h.editing()
        h.write={ ConfiguredWriteResult.CommitUnconfirmed(it,IOException()) }; h.tap(.15f); h.stored=null; h.c.reconcile()
        assertEquals(ConfiguredReconciliation.THIRD_STATE,h.ui.reconciliation); assertFalse(h.ui.canRetryExact); assertNotNull(h.ui.pending) }
    @Test fun `explicit leave unknown operation is distinct and never claims rollback`(): Unit = EditorHarness().use { h ->
        h.write={ ConfiguredWriteResult.CommitUnconfirmed(it,IOException()) }; h.draft(); h.c.abandonLocalAndClose(); h.c.close(); assertTrue(h.ui.open)
        h.c.abandonSubmittedAndClose(); assertFalse(h.ui.open); assertFalse(h.gate); assertNull(h.ui.saved); assertEquals(1,h.requests.size)
    }
    @Test fun `coroutine cancellation conservatively keeps intent and blocks mutations`() {
        val h=EditorHarness(); h.hold=CompletableDeferred(); h.c.create(); h.c.title(h.token,"Cancelled"); h.c.confirmForm(h.token)
        val exact=h.ui.pending; h.scope.cancel(); assertSame(exact,h.ui.pending); assertFalse(h.ui.busy); assertTrue(h.gate)
        assertEquals(ConfiguredProblemKind.UNCONFIRMED,h.ui.problem!!.kind); assertNull(h.ui.saved)
    }
}

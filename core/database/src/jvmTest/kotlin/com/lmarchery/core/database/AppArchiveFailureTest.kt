package com.lmarchery.core.database

import com.lmarchery.core.database.ArchiveV3RestoreData as r
import com.lmarchery.core.database.ArchiveV3DiskData as d
import com.lmarchery.core.database.EquipmentTestData as f
import com.lmarchery.core.database.ConfiguredWriteData as c
import com.lmarchery.core.export.ArchiveV3JsonCodec
import com.lmarchery.core.scoring.archive.v3.snapshotArchiveV3
import com.lmarchery.feature.archiveimport.*
import com.lmarchery.feature.export.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class AppArchiveFailureTest {
    @TempDir lateinit var directory:Path
    private val path get()=directory.resolve("archive-pipeline.db")
    private val bytes get()=ArchiveV3JsonCodec().encode(r.source())
    @Test fun `restore rollback after native INSERT keeps common UI gate until explicit fresh preview and confirmation`() = runBlocking<Unit> {
        val inserted=AtomicBoolean()
        val store=hookedStore(path) {sql,_->if(sql.startsWith("INSERT") && sql.contains("`impacts`"))inserted.set(true)}
        AppIntegrationHarness(path,store).use { h ->
            h.store.equipment.create(f.bow(850));val before=d.contents(path)
            assertIs<ImportState.PreviewV3>(h.preview("source.json",bytes))
            d.raw(path,"CREATE TRIGGER late_restore_failure BEFORE INSERT ON arrow_sets BEGIN SELECT RAISE(ABORT,'synthetic late restore'); END")
            assertIs<ImportState.UnconfirmedV3>(h.confirm());assertTrue(inserted.get());assertEquals(before,d.contents(path))
            h.assertOtherOperationsLocked();assertFalse(h.state.open)
            d.raw(path,"DROP TRIGGER late_restore_failure")
            h.onUi {h.importer.repreviewV3()}
            h.awaitUiState(h.importer.state) {it is ImportState.PreviewV3}
            // Resume the observer immediately on publication, before producer cleanup.
            // This reproduces the former cross-dispatcher assertion failure deterministically.
            withContext(Dispatchers.Unconfined) {
                assertIs<ImportState.SucceededV3>(h.confirm())
                assertFalse(h.legacy.state.value.archiveOperationLocked)
            }
            assertTrue(h.archive().bows.contains(f.bow(850)));assertEquals(r.source().sessions,h.archive().sessions)
            assertEquals(1,h.documents.inputs.get());assertRecipeIntegrity(path)
        }
    }
    @Test fun `acquired restore COMMIT lost ACK then explicit memory repreview recognizes it without reopening or writing`() = runBlocking<Unit> {
        val arm=AtomicBoolean();val pending=AtomicBoolean();val committed=AtomicBoolean()
        val store=hookedStore(path) {sql,_->
            if(sql.startsWith("INSERT") && sql.contains("`sessions`") && arm.get())pending.set(true)
            if(sql=="END TRANSACTION" && pending.compareAndSet(true,false)){arm.set(false);committed.set(true);throw IllegalStateException("Lost actual COMMIT acknowledgement")}
        }
        AppIntegrationHarness(path,store).use {h->
            val original=assertIs<ImportState.PreviewV3>(h.preview("source.json",bytes));arm.set(true)
            assertIs<ImportState.UnconfirmedV3>(h.confirm());assertTrue(committed.get());assertEquals(r.source(),h.archive())
            h.assertOtherOperationsLocked();val before=d.contents(path);f.denyWrites(path,d.tables)
            h.documents.put("source.json","not the selected archive any more".toByteArray())
            h.onUi {h.importer.repreviewV3()}
            val fresh=assertIs<ImportState.PreviewV3>(h.awaitUiState(h.importer.state) {it is ImportState.PreviewV3})
            assertNotEquals(original.confirmationId,fresh.confirmationId)
            h.onUi {h.importer.confirmV3(original.confirmationId)};assertEquals(fresh,h.importer.state.value)
            assertTrue(assertIs<ImportState.SucceededV3>(h.confirm()).alreadyPresent)
            assertEquals(1,h.documents.inputs.get());assertEquals(before,d.contents(path));assertFalse(h.legacy.state.value.archiveOperationLocked)
        }
    }
    @Test fun `stale preview after another catalog writer never imports even an unrelated new session`() = runBlocking<Unit> {
        AppIntegrationHarness(path).use {h->
            val bow=f.bow(850);h.store.equipment.create(bow)
            assertIs<ImportState.PreviewV3>(h.preview("source.json",bytes))
            openSessionStore(path).use { other->assertIs<EquipmentUpdateResult.Updated>(other.equipment.update(bow,bow.copy(name="Other writer",updatedAt=f.now.plusSeconds(1)))) }
            val changed=d.contents(path);f.denyWrites(path,d.tables)
            assertIs<ImportState.ConflictV3>(h.confirm());assertEquals(changed,d.contents(path));assertTrue(h.archive().sessions.isEmpty())
            assertFalse(h.legacy.state.value.archiveOperationLocked);assertEquals(1,h.documents.inputs.get())
        }
    }
    @Test fun `cross category identity conflict blocks independent material additions through real import coordinator`() = runBlocking<Unit> {
        AppIntegrationHarness(path).use {h->
            val local=c.rich();h.store.createConfiguredSession(local);val impact=local.ends.single().end.impacts.first().uuid
            val conflicting=r.accepted(snapshotArchiveV3(emptyList(),emptyList(),listOf(com.lmarchery.core.model.equipment.Bow(impact,"Synthetic conflicting bow",createdAt=f.now,updatedAt=f.now)),listOf(f.arrows(999))))
            val before=d.contents(path);f.denyWrites(path,d.tables)
            assertIs<ImportState.ConflictV3>(h.preview("collision.json",ArchiveV3JsonCodec().encode(conflicting)))
            h.onUi {h.importer.confirmV3("not a preview")};assertEquals(before,d.contents(path))
            assertFalse(h.legacy.state.value.archiveOperationLocked);assertTrue(h.archive().arrowSets.isEmpty())
        }
    }
    @Test fun `malformed selected bytes and real input close failure cannot reach a restore or retain UI gate`() = runBlocking<Unit> {
        AppIntegrationHarness(path).use {h->
            h.store.fullV3();val before=d.contents(path);f.denyWrites(path,d.tables)
            assertIs<ImportState.Failed>(h.preview("truncated.json",bytes.copyOf(bytes.size-1)))
            assertFalse(h.legacy.state.value.archiveOperationLocked);assertEquals(before,d.contents(path))
            h.documents.failInputClose=true
            assertIs<ImportState.Failed>(h.preview("close-failure.json",bytes))
            assertEquals(before,d.contents(path));assertFalse(h.legacy.state.value.archiveOperationLocked)
            h.documents.failInputClose=false
            assertIs<ImportState.PreviewV3>(h.preview("valid.json",bytes));h.onUi {h.importer.cancel()}
            assertFalse(h.legacy.state.value.archiveOperationLocked);assertEquals(before,d.contents(path))
        }
    }
    @Test fun `output close failure is not export success and explicit next export uses fresh transaction and file`() = runBlocking<Unit> {
        AppIntegrationHarness(path).use {h->
            h.store.createConfiguredSession(c.rich());val before=d.contents(path);f.denyWrites(path,d.tables)
            h.documents.failOutputClose=true;val choosing=h.prepareExport(3)
            h.onUi {h.exporter.claimDestinationRequest();h.exporter.destinationChosen(choosing.requestId,h.documents.uri("failed.json"))}
            val failed=assertIs<ExportState.Failed>(h.awaitUiState(h.exporter.state) {it is ExportState.Failed})
            assertTrue(failed.potentiallyIncomplete);assertFalse(h.legacy.state.value.archiveOperationLocked);assertEquals(before,d.contents(path))
            assertTrue(h.documents.bytes("failed.json").isNotEmpty()) // Preserved, never erased or declared successful.
            h.documents.failOutputClose=false;val encoded=h.export(3,"fresh.json")
            assertContentEquals(ArchiveV3JsonCodec().encode(h.archive()),encoded);assertEquals(before,d.contents(path))
        }
    }
    @Test fun `duplicate export callbacks and stale selector tickets never rewrite a disk document or steal another lock`() = runBlocking<Unit> {
        AppIntegrationHarness(path).use {h->
            h.store.createConfiguredSession(c.rich());val choosing=h.prepareExport(3)
            h.onUi {h.exporter.claimDestinationRequest();h.exporter.destinationChosen(choosing.requestId,h.documents.uri("once.json"));h.exporter.destinationChosen(choosing.requestId,h.documents.uri("twice.json"))}
            h.awaitUiState(h.exporter.state) {it is ExportState.Succeeded}
            assertEquals(1,h.documents.outputs.get())
            h.onUi {h.configured.open(c.rich().uuid)};h.idle();assertTrue(h.legacy.state.value.archiveOperationLocked)
            h.onUi {h.exporter.destinationChosen(choosing.requestId,h.documents.uri("stale.json"))}
            assertTrue(h.legacy.state.value.archiveOperationLocked);assertEquals(1,h.documents.outputs.get())
            h.closeEditor()
        }
    }
    @Test fun `prepared export stays one coherent snapshot while a later explicit export sees another writer catalog revision`() = runBlocking<Unit> {
        AppIntegrationHarness(path).use { h ->
            h.store.createConfiguredSession(c.rich());val preparedArchive=h.archive()
            val choosing=h.prepareExport(3)
            openSessionStore(path).use { other -> assertIs<EquipmentCreateResult.Created>(other.equipment.create(f.bow(850))) }
            val updatedArchive=h.archive();assertEquals(listOf(f.bow(850)),updatedArchive.bows)
            val before=d.contents(path);f.denyWrites(path,d.tables)
            h.onUi {h.exporter.claimDestinationRequest();h.exporter.destinationChosen(choosing.requestId,h.documents.uri("prepared.json"))}
            h.awaitUiState(h.exporter.state) {it is ExportState.Succeeded}
            val old=assertIs<com.lmarchery.core.export.ArchiveV3JsonRead.Accepted>(ArchiveV3JsonCodec().decode(h.documents.bytes("prepared.json"))).archive
            assertEquals(preparedArchive,old);assertTrue(old.bows.isEmpty())
            // A faster observer must still see the UI lock released before export returns.
            val fresh=withContext(Dispatchers.Unconfined) { h.export(3,"latest.json") }
            assertEquals(updatedArchive,assertIs<com.lmarchery.core.export.ArchiveV3JsonRead.Accepted>(ArchiveV3JsonCodec().decode(fresh)).archive)
            assertEquals(before,d.contents(path));assertRecipeIntegrity(path)
        }
    }

}

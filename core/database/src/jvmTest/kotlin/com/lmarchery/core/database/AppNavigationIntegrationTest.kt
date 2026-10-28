package com.lmarchery.core.database

import com.lmarchery.core.database.ArchiveV3DiskData as d
import com.lmarchery.core.database.EquipmentTestData as f
import com.lmarchery.core.database.ConfiguredWriteData as c
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.equipment.Bow
import com.lmarchery.core.model.session.*
import com.lmarchery.core.scoring.configured.*
import com.lmarchery.data.EquipmentKind
import com.lmarchery.feature.archiveimport.ImportState
import com.lmarchery.feature.configured.ConfiguredProblemKind
import com.lmarchery.feature.equipment.EquipmentField
import com.lmarchery.feature.export.ExportState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class AppNavigationIntegrationTest {
    @TempDir lateinit var directory:Path
    private val path get()=directory.resolve("navigation.db")
    @Test fun `precise candidates and cancelled configuration exclude other actors and stale same end callback never writes`() = runBlocking<Unit> {
        val writes=AtomicInteger()
        val store=hookedStore(path) {sql,_->if(listOf("INSERT","UPDATE","DELETE").any{sql.startsWith(it)} && d.tables.any {sql.contains("`$it`")})writes.incrementAndGet()}
        AppIntegrationHarness(path,store).use {h->
            h.editing();val original=h.state.saved!!;val old=h.context;val before=d.contents(path);val count=writes.get()
            h.onUi {h.configured.precision(h.context,true)}
            val token=h.onUi {requireNotNull(h.configured.beginAim(h.context,NormalizedPoint(.105f,-0.0f)))}
            h.onUi {h.configured.finishAim(token,NormalizedPoint(.105f,-0.0f));h.configured.nextEnd();h.configured.editMetadata();h.importer.begin()}
            assertIs<ImportState.Failed>(h.importer.state.value);assertNull(h.state.form)
            h.assertOtherOperationsLocked();assertEquals(before,d.contents(path));assertEquals(count,writes.get())
            h.onUi {h.configured.cancelPosition(h.context);h.configured.confirmPosition(old)}
            assertEquals(count,writes.get());assertEquals(original,h.state.saved)
            h.onUi {h.configured.nextEnd();h.configured.explicitNext(h.token,true);h.configured.distance(h.token,"70");h.configured.cancelForm(h.token)}
            assertEquals(count,writes.get());assertEquals(before,d.contents(path))
            val next=h.onUi {requireNotNull(h.configured.beginAim(h.context,NormalizedPoint(.25f,-0.0f)))}
            h.onUi {h.configured.finishAim(next,NormalizedPoint(.25f,-0.0f))};val confirm=h.context
            h.change {h.configured.confirmPosition(confirm);h.configured.confirmPosition(confirm)}
            assertEquals(1,h.state.selectedEnd!!.end.impacts.size);assertEquals(8,h.state.selectedEnd!!.end.impacts.single().scoreFinal)
            val id=h.state.saved!!.uuid;h.closeEditor();h.onUi {h.configured.open(id)};h.idle();val current=writes.get()
            h.onUi {h.configured.tapped(old,NormalizedPoint(.35f,0f));h.configured.finishAim(token,NormalizedPoint(.35f,0f))}
            assertEquals(current,writes.get());assertEquals(1,h.state.selectedEnd!!.end.impacts.size);assertRecipeIntegrity(path)
        }
    }
    @Test fun `catalog revisions and historical completed associations preserve every score while completed configured root is immutable`() = runBlocking<Unit> {
        AppIntegrationHarness(path).use {h->
            h.store.restoreV2(d.source());val configured=c.rich().complete(f.now.plusSeconds(20));h.store.createConfiguredSession(configured)
            val bow=h.createEquipment(EquipmentKind.BOW) as Bow
            h.onUi {h.legacy.openSession(f.session(100, true).uuid)};h.ready();val original=h.legacy.state.value.session!!
            assertEquals(SessionStatus.COMPLETED,original.status)
            h.onUi {h.legacy.beginEquipmentEdit()};h.ready()
            h.onUi {h.legacy.chooseEquipment(EquipmentKind.BOW,bow.uuid);h.legacy.confirmEquipment()};h.ready()
            val revised=h.legacy.state.value.session!!
            assertEquals(bow.uuid,revised.bowUuid);assertEquals(original.arrowSetUuid,revised.arrowSetUuid);assertEquals(original.ends,revised.ends)
            val captures=f.contents(path,listOf("session_configurations","end_configurations"))
            h.onUi {h.legacy.returnToList()};h.ready();h.onUi {h.equipment.open()}
            withTimeout(15000){h.equipment.state.first{!it.busy}}
            h.onUi {h.equipment.show(EquipmentKind.BOW,bow.uuid)};withTimeout(15000){h.equipment.state.first{!it.busy}}
            h.onUi {h.equipment.edit();h.equipment.setField(EquipmentField.NAME," Catalog renamed 🏹 ");h.equipment.save()}
            withTimeout(15000){h.equipment.state.first{!it.busy}}
            assertNull(h.equipment.state.value.pending);assertNull(h.equipment.state.value.problem)
            val revisedBow=assertIs<Bow>(h.equipment.state.value.original);assertEquals(bow.uuid,revisedBow.uuid);assertEquals(bow.createdAt,revisedBow.createdAt)
            assertEquals(bow.drawWeightLbs!!.toRawBits(),revisedBow.drawWeightLbs!!.toRawBits())
            h.onUi {h.equipment.close()}
            assertEquals(captures,f.contents(path,listOf("session_configurations","end_configurations")))
            val complete=h.archive();val stored=complete.sessions.single{it.uuid==original.uuid}
            assertEquals(revised.ends,stored.ends.map{it.end});assertEquals(revised.bowUuid,stored.metadata.bowUuid)
            assertEquals(configured,complete.sessions.single{it.uuid==configured.uuid})
            val before=d.contents(path);f.denyWrites(path,d.tables)
            h.onUi {h.configured.open(configured.uuid)};h.idle()
            h.onUi {h.configured.editMetadata();h.configured.nextEnd();h.configured.complete()}
            assertNull(h.state.form);assertEquals(before,d.contents(path));assertEquals(configured,h.state.saved)
        }
    }
    @Test fun `physical legacy rows retain their editor while legitimately restored historical configured origin can be revised`() = runBlocking<Unit> {
        AppIntegrationHarness(path).use {h->
            h.store.restoreV2(d.source());h.onUi {h.configured.open(f.id(301))};h.idle()
            assertEquals(ConfiguredProblemKind.NOT_CONFIGURED,h.state.problem!!.kind);assertNull(h.state.saved);h.closeEditor()
            val source=d.configured(v2(sessions=listOf(f.session(12000))))
            assertIs<ArchiveV3ImportResult.Imported>(h.store.restoreV3(source));h.refresh()
            val expected=source.sessions.single()
            val entries=h.legacy.state.value.entries
            assertNotNull(entries.single{it.uuid==f.id(301)}.session)
            assertNull(entries.single{it.uuid==f.id(301)}.configured)
            assertEquals(expected,entries.single{it.uuid==expected.uuid}.configured)
            h.onUi {h.configured.open(expected.uuid)};h.idle()
            assertEquals(InitialConfigurationOrigin.LEGACY_DERIVED,h.state.saved!!.initialConfigurationOrigin)
            h.onUi {h.configured.editMetadata();h.configured.notes(h.token,"")};h.change {h.configured.confirmForm(h.token)}
            assertEquals(expected.ends,h.state.saved!!.ends);h.nextEnd()
            assertEquals(EndConfigurationOrigin.Inherited(expected.ends.last().uuid),h.state.selectedEnd!!.origin)
            assertEquals(expected.ends,h.state.saved!!.ends.take(expected.ends.size))
            assertEquals(listOf("LEGACY_HOMOGENEOUS"),f.rows(path,"SELECT representation FROM session_configurations WHERE sessionUuid='${f.id(301)}'"))
            assertIs<SessionReadResult.NeedsIntervention>(h.store.read(expected.uuid));assertRecipeIntegrity(path)
        }
    }
}

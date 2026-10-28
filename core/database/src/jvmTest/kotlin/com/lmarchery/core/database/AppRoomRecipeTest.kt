package com.lmarchery.core.database

import com.lmarchery.core.database.ArchiveV3DiskData as d
import com.lmarchery.core.database.EquipmentTestData as f
import com.lmarchery.core.export.*
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.equipment.*
import com.lmarchery.core.model.scoring.BoundaryValue
import com.lmarchery.core.model.session.*
import com.lmarchery.core.scoring.configured.ConfiguredSession
import com.lmarchery.core.scoring.fixtures.ClassicTargetFixtures
import com.lmarchery.core.scoring.profiles.WorldArcheryIndoorTargetProfiles as WA
import com.lmarchery.feature.archiveimport.ImportState
import com.lmarchery.feature.sessions.*
import com.lmarchery.data.EquipmentKind
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.io.TempDir
import java.nio.file.*
import kotlin.test.*

class AppRoomRecipeTest {
    @TempDir lateinit var directory: Path
    private val source get() = directory.resolve("source.db")
    private val restored get() = directory.resolve("restored.db")

    @Test fun `production controllers V1 V2 V3 migrate edit export restore reopen and preserve every historical row`() = runBlocking<Unit> {
        d.historical(source, 1, d.source(false))
        val original = f.contents(source, f.oldTables)
        lateinit var bows: List<Bow>; lateinit var arrows: List<ArrowSet>
        lateinit var v1: ByteArray; lateinit var v2: ByteArray; lateinit var v3: ByteArray
        lateinit var expected: com.lmarchery.core.scoring.archive.v3.DomainArchiveV3
        lateinit var configured: ConfiguredSession
        AppIntegrationHarness(source).use { h ->
            h.refresh()
            val bow = h.createEquipment(EquipmentKind.BOW); val set = h.createEquipment(EquipmentKind.ARROW_SET)
            h.onUi { h.legacy.configure() }
            withTimeout(15000) { h.legacy.state.first { !it.equipmentChoicesLoading } }
            h.onUi { h.legacy.setTitle(" Legacy quick 🏹 "); h.legacy.chooseCreationEquipment(EquipmentKind.BOW, bow.uuid)
                h.legacy.chooseCreationEquipment(EquipmentKind.ARROW_SET, set.uuid); h.legacy.createSession() }
            h.ready(); assertNull(h.legacy.state.value.problem)
            for (radius in listOf(0f,.15f,1.1f)) { h.onUi { h.legacy.addImpact(NormalizedPoint(radius,-0.0f)) };h.ready() }
            h.onUi { h.legacy.validateEnd() }; h.ready(); h.onUi { h.legacy.completeSession() }; h.ready()
            val old = requireNotNull(h.legacy.state.value.session); assertEquals(19,calculateEndScoreForRecipe(old.ends.single()))
            assertEquals(bow.uuid,old.bowUuid); assertEquals(set.uuid,old.arrowSetUuid)
            h.onUi { h.legacy.returnToList() }; h.ready()
            v1=h.export(1,"legacy-v1.json");v2=h.export(2,"legacy-v2.json")
            val baseline=h.archive();bows=baseline.bows;arrows=baseline.arrowSets
            h.onUi { h.configured.create(); h.configured.title(h.token," Mixed recipe é 🏹 ");h.configured.scoring(h.token,ScoringMode.COMPETITION) }
            withTimeout(15000) { h.configured.state.first { it.equipmentChoices.values(EquipmentKind.BOW).any { choice -> choice.uuid==bow.uuid } } }
            h.onUi { h.configured.chooseEquipment(h.token,EquipmentKind.BOW,bow.uuid);h.configured.chooseEquipment(h.token,EquipmentKind.ARROW_SET,set.uuid) }
            h.change { h.configured.confirmForm(h.token) };h.change { h.configured.start() };h.nextEnd()
            h.tap(.105f);h.onUi { h.configured.selectImpact(h.context,h.state.selectedEnd!!.end.impacts.single().uuid);h.configured.beginDecision(h.context) }
            val boundary=h.state.scoreOptions!!.nearby.first { it.inner.score==10 && it.outer.score==9 }
            h.change { h.configured.boundary(h.context,boundary.reference);h.configured.scoreValue(h.context,boundary.inner);h.configured.confirmDecision(h.context) }
            h.change { h.configured.beginReposition(h.context);h.configured.repositionPoint(h.context,NormalizedPoint(.25f,-0.0f));h.configured.repositionReason(h.context," Placement corrigé ");h.configured.confirmReposition(h.context) }
            h.change { h.configured.beginDecision(h.context);h.configured.exceptional(h.context,true);h.configured.scoreValue(h.context,BoundaryValue(9));h.configured.decisionReason(h.context,"Observation");h.configured.confirmDecision(h.context) }
            h.change { h.configured.beginReposition(h.context);h.configured.repositionPoint(h.context,NormalizedPoint(.35f,-0.0f));h.configured.repositionReason(h.context,"Seconde position");h.configured.confirmReposition(h.context) }
            val traces=h.state.selectedImpact!!.repositions;assertEquals(2,traces.size);assertNotNull(traces.last().invalidatedManualDecision)
            h.onUi { h.configured.closeSelection(h.context) };h.tap(0f);h.tap(1.1f);h.change { h.configured.validateEnd() }
            val fixed=h.state.selectedEnd!!;assertEquals(17,fixed.end.impacts.sumOf { it.scoreFinal })
            h.onUi { h.configured.nextEnd();h.configured.explicitNext(h.token,true);h.configured.chooseProfile(h.token,WA.compound40.uuid);h.configured.distance(h.token,"70");h.configured.indoor(h.token,false);h.configured.shortcut(h.token,EndArrowCountShortcut.SIX) }
            h.change { h.configured.confirmForm(h.token) };h.tap(.075f);assertEquals(9,h.state.selectedEnd!!.end.impacts.single().scoreFinal)
            h.onUi { h.configured.nextEnd();h.configured.explicitNext(h.token,true) };h.change { h.configured.confirmForm(h.token) }
            val explicit=h.state.selectedEnd!!;h.nextEnd();assertEquals(com.lmarchery.core.scoring.configured.EndConfigurationOrigin.Inherited(explicit.uuid),h.state.selectedEnd!!.origin)
            h.onUi { h.configured.nextEnd();h.configured.explicitNext(h.token,true);h.configured.chooseProfile(h.token,ClassicTargetFixtures.classic40.uuid);h.configured.distance(h.token,"30");h.configured.count(h.token,"7");h.configured.scoring(h.token,ScoringMode.MANUAL_CORDON) }
            h.change { h.configured.confirmForm(h.token) };h.tap(0f)
            h.onUi { h.configured.nextEnd();h.configured.explicitNext(h.token,true);h.configured.shortcut(h.token,EndArrowCountShortcut.FREE) }
            h.change { h.configured.confirmForm(h.token) };for(radius in listOf(.15f,.25f,1.1f,0f))h.tap(radius)
            h.change { h.configured.validateEnd() };h.change { h.configured.complete() }
            configured=h.state.saved!!;assertEquals(fixed,configured.ends.first());assertEquals(traces,configured.ends.first().end.impacts.first().repositions)
            val summary=requireNotNull(presentConfiguredScore(configured).summary)
            assertEquals(44L,summary.totals.validated.score);assertEquals(19L,summary.totals.provisional.score)
            assertEquals(63L,summary.totals.recorded.score);assertEquals(9L,summary.totals.recorded.impactCount)
            assertEquals(2L,summary.totals.recorded.xCount);assertEquals(2L,summary.totals.recorded.missCount)
            assertEquals(4,summary.groups.size);assertEquals(320L,summary.totals.recorded.recordedCapacityMaximum)
            h.closeEditor();expected=h.archive();v3=h.export(3,"complete-v3.json")
            assertEquals(expected,assertIs<ArchiveV3JsonRead.Accepted>(ArchiveV3JsonCodec().decode(v3)).archive)
            val after=f.contents(source,f.oldTables);original.forEach { (table,rows)->assertTrue(after.getValue(table).containsAll(rows),table) }
        }
        AppIntegrationHarness(restored).use { h ->
            for (bow in bows) assertIs<EquipmentCreateResult.Created>(h.store.equipment.create(bow))
            for (set in arrows) assertIs<EquipmentCreateResult.Created>(h.store.equipment.create(set))
            assertIs<ImportState.Preview>(h.preview("legacy-v1.json",v1));assertIs<ImportState.Succeeded>(h.confirm())
            assertEquals(bows,h.archive().bows);assertEquals(arrows,h.archive().arrowSets) // V1 material not provided is not deletion.
            assertIs<ImportState.PreviewV2>(h.preview("legacy-v2.json",v2));assertIs<ImportState.SucceededV2>(h.confirm())
            val legacyRows=f.rows(restored,"SELECT uuid FROM sessions ORDER BY uuid")
            assertIs<ImportState.PreviewV3>(h.preview("complete-v3.json",v3));assertIs<ImportState.SucceededV3>(h.confirm())
            assertEquals(expected,h.archive())
            assertEquals(legacyRows,f.rows(restored,"SELECT sessionUuid FROM session_configurations WHERE representation='LEGACY_HOMOGENEOUS' ORDER BY sessionUuid"))
            assertEquals(listOf(configured.uuid),f.rows(restored,"SELECT sessionUuid FROM session_configurations WHERE representation='CONFIGURED'"))
            val before=d.contents(restored);f.denyWrites(restored,d.tables)
            val preview=assertIs<ImportState.PreviewV3>(h.preview("complete-v3.json",v3))
            h.onUi { h.importer.confirmV3(preview.confirmationId);h.importer.confirmV3(preview.confirmationId) }
            val receipt=withTimeout(15000) { h.importer.state.first { it is ImportState.SucceededV3 } }
            assertTrue(assertIs<ImportState.SucceededV3>(receipt).alreadyPresent);h.ready();assertEquals(before,d.contents(restored))
            assertContentEquals(v3,h.export(3,"reexport-v3.json"))
        }
        for(path in listOf(source,restored))openSessionStore(path).use { store ->
            assertEquals(expected,store.fullV3());assertContentEquals(v3,ArchiveV3JsonCodec().encode(store.fullV3()));assertRecipeIntegrity(path)
        }
        println("PR44 full recipe PASS: production controllers + disk document streams + native SQLite; V1->2->3, V1/V2/V3 coexistence, exact V3 reopen/restore and zero-DML idempotence.")
    }
    private fun calculateEndScoreForRecipe(end: End)=com.lmarchery.core.scoring.calculateEndScore(end)
}

internal fun assertRecipeIntegrity(path:Path) {
    assertContentEquals("SQLite format 3\u0000".toByteArray(),Files.newInputStream(path).use { it.readNBytes(16) })
    assertEquals(listOf("3"),f.rows(path,"PRAGMA user_version"));assertEquals(listOf("ok"),f.rows(path,"PRAGMA integrity_check"))
    assertTrue(f.rows(path,"PRAGMA foreign_key_check").isEmpty())
    val names=f.rows(path,"SELECT name FROM sqlite_master WHERE type='table' AND name NOT IN ('room_master_table','sqlite_sequence','android_metadata') ORDER BY name")
    assertEquals(d.tables.sorted(),names)
}

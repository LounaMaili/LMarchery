package com.lmarchery.core.database

import com.lmarchery.core.database.ArchiveV3DiskData as d
import com.lmarchery.core.database.EquipmentTestData as f
import com.lmarchery.core.export.ArchiveV3JsonCodec
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.scoring.archive.v3.DomainArchiveV3
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

/** Real controllers/Room/native SQLite disk files; not a physical touch test. */
class AppImpactDragTest {
    @TempDir lateinit var directory: Path
    private val next = NormalizedPoint(.25f,.1f)

    @Test fun `both editors drag refine cancel and ignore stale commands under anti DML triggers`() = runBlocking<Unit> {
        for (configured in listOf(false,true)) {
            val path=directory.resolve("cancel-$configured.db")
            AppIntegrationHarness(path).use { h ->
                h.store.restoreV2(d.source())
                if (configured) { h.editing();h.tap(.105f);h.tap(.105f) }
                else {
                    h.onUi { h.legacy.configure();h.legacy.createSession() };h.ready()
                    repeat(3) { h.onUi { h.legacy.addImpact(NormalizedPoint(.105f,0f)) };h.ready() }
                }
                val before=h.archive();val rows=d.contents(path);f.denyWrites(path,d.tables)
                h.onUi {
                    if (configured) {
                        val arrow=h.state.selectedEnd!!.end.impacts.last()
                        h.configured.selectImpact(h.context,arrow.uuid);h.configured.beginDragReposition(h.context)
                        val ctx=h.state.impactDrag!!;val t=h.configured.beginImpactDrag(ctx,arrow.point)!!
                        h.configured.moveImpactDrag(t,next);h.configured.finishImpactDrag(t,next)
                        h.configured.confirmReposition(h.context) // Old path must not submit the drag.
                        h.configured.closeSelection(h.context);h.configured.confirmImpactDrag(ctx,next)
                        assertNull(h.state.pending);assertNull(h.state.impactDrag)
                    } else {
                        val ui=h.legacy.state.value;val end=ui.displayedEnd!!;val arrow=end.impacts.last()
                        h.legacy.selectImpact(end.uuid,arrow.uuid);h.legacy.beginDragReposition()
                        val ctx=h.legacy.state.value.impactDrag!!;val t=h.legacy.beginImpactDrag(ctx,arrow.point)!!
                        h.legacy.moveImpactDrag(t,next);h.legacy.finishImpactDrag(t,next)
                        h.legacy.confirmReposition();h.legacy.cancelImpactDrag(ctx);h.legacy.confirmImpactDrag(ctx,next)
                        assertNull(h.legacy.state.value.pending);assertNull(h.legacy.state.value.impactDrag)
                    }
                }
                assertEquals(rows,d.contents(path));assertEquals(before,h.archive());assertRecipeIntegrity(path)
            }
        }
    }

    @Test fun `configured manual decision drag confirms once preserves other arrows and reopens exactly`() = runBlocking<Unit> {
        val path=directory.resolve("configured-confirm.db");lateinit var expected:DomainArchiveV3
        AppIntegrationHarness(path).use { h ->
            h.store.restoreV2(d.source());h.editing();h.tap(.105f);h.tap(.105f)
            h.onUi {
                h.configured.selectImpact(h.context,h.state.selectedEnd!!.end.impacts.first().uuid)
                h.configured.beginDecision(h.context)
                val b=h.state.scoreOptions!!.nearby.single { it.inner.score==10 && it.outer.score==9 }
                h.configured.boundary(h.context,b.reference);h.configured.scoreValue(h.context,b.inner)
            }
            h.change { h.configured.confirmDecision(h.context) }
            h.onUi { h.configured.closeSelection(h.context)
                h.configured.beginImpactPicking(h.context)
                val picking=h.state.impactPicking!!.context
                h.configured.pickImpact(picking,NormalizedPoint(.105f,0f),.001f)
                assertEquals(2,h.state.impactPicking!!.candidates.size)
                h.configured.choosePickedImpact(picking,h.state.selectedEnd!!.end.impacts.first().uuid) }
            val original=h.state.selectedImpact!!;val before=h.archive();val rows=d.contents(path)
            h.onUi { h.configured.beginDragReposition(h.context) };val ctx=h.state.impactDrag!!
            h.onUi {
                val token=h.configured.beginImpactDrag(ctx,original.point)!!
                h.configured.moveImpactDrag(token,next);h.configured.finishImpactDrag(token,next)
                h.configured.confirmImpactDrag(ctx,next)
            }
            assertEquals(rows,d.contents(path));assertEquals(before,h.archive())
            h.onUi { h.configured.repositionReason(h.context,"Déplacement synthétique motivé") }
            h.change { h.configured.confirmImpactDrag(ctx,next);h.configured.confirmImpactDrag(ctx,next) }
            val moved=h.state.selectedImpact!!
            assertEquals(original.uuid,moved.uuid);assertEquals(next,moved.point);assertEquals(1,moved.repositions.size)
            assertEquals(original.finalScoreDecision,moved.repositions.single().invalidatedManualDecision)
            assertEquals(original.inputMode,moved.inputMode);assertEquals(original.createdAt,moved.createdAt)
            expected=h.archive()
            assertEquals(before.sessions.filter { it.uuid!=h.state.saved!!.uuid },expected.sessions.filter { it.uuid!=h.state.saved!!.uuid })
            assertEquals(before.profiles,expected.profiles);assertEquals(before.bows,expected.bows);assertEquals(before.arrowSets,expected.arrowSets)
            assertEquals(before.sessions.single { it.uuid == h.state.saved!!.uuid }.ends.first().end.impacts.last(),h.state.selectedEnd!!.end.impacts.last())
            assertRecipeIntegrity(path)
        }
        openSessionStore(path).use { assertEquals(expected,it.fullV3());assertContentEquals(ArchiveV3JsonCodec().encode(expected),ArchiveV3JsonCodec().encode(it.fullV3())) }
        assertRecipeIntegrity(path)
    }

    @Test fun `historical successive drags keep UUID both traces and confirmed data after reopen`() = runBlocking<Unit> {
        val path=directory.resolve("legacy-confirm.db");lateinit var expected:DomainArchiveV3
        AppIntegrationHarness(path).use { h ->
            h.store.restoreV2(d.source())
            h.onUi { h.legacy.configure();h.legacy.createSession() };h.ready()
            repeat(3) { h.onUi { h.legacy.addImpact(NormalizedPoint(.105f,0f)) };h.ready() }
            val old=h.archive();val end=h.legacy.state.value.displayedEnd!!;val arrow=end.impacts.first()
            for (point in listOf(next,NormalizedPoint(.45f,-.2f))) {
                h.onUi { h.legacy.selectImpact(end.uuid,arrow.uuid);h.legacy.beginDragReposition() }
                val ctx=h.legacy.state.value.impactDrag!!
                h.onUi { val t=h.legacy.beginImpactDrag(ctx,h.legacy.state.value.selectedImpact!!.point)!!;h.legacy.finishImpactDrag(t,point) }
                h.onUi { h.legacy.confirmImpactDrag(ctx,point);h.legacy.confirmImpactDrag(ctx,point) };h.ready()
                assertNull(h.legacy.state.value.pending);assertNull(h.legacy.state.value.problem)
            }
            val moved=h.legacy.state.value.selectedImpact!!;assertEquals(arrow.uuid,moved.uuid);assertEquals(2,moved.repositions.size)
            expected=h.archive();assertEquals(old.bows,expected.bows);assertEquals(old.arrowSets,expected.arrowSets)
            val current=h.legacy.state.value.session!!.uuid
            assertEquals(old.sessions.filter { it.uuid!=current },expected.sessions.filter { it.uuid!=current });assertRecipeIntegrity(path)
        }
        openSessionStore(path).use { assertEquals(expected,it.fullV3()) };assertRecipeIntegrity(path)
    }
}

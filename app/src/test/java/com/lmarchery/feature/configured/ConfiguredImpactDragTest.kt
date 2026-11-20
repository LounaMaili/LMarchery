package com.lmarchery.feature.configured

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.database.*
import com.lmarchery.feature.sessions.*
import kotlin.test.*

class ConfiguredImpactDragTest {
    private val moved = NormalizedPoint(.25f, .1f)
    private fun EditorHarness.drag(): ImpactDragContext {
        selectFirst(); c.beginDragReposition(ctx); return ui.impactDrag!!
    }
    @Test fun `drag is explicit previews only and confirms exactly once`() = EditorHarness().use { h ->
        h.editing(); h.tap(.105f); val saved = h.ui.saved; val writes = h.requests.size; val uuids = h.uuids; val now = h.now
        assertNull(h.ui.impactDrag)
        val ctx = h.drag(); val token = h.c.beginImpactDrag(ctx, h.ui.selectedImpact!!.point)!!
        h.c.moveImpactDrag(token, moved); assertFalse(h.ui.canConfirmReposition)
        h.c.confirmImpactDrag(ctx, moved); assertEquals(writes, h.requests.size)
        h.c.finishImpactDrag(token, moved); h.c.finishImpactDrag(token, moved)
        assertSame(saved, h.ui.saved); assertEquals(moved, h.ui.edit!!.candidate)
        assertEquals(writes, h.requests.size); assertEquals(uuids, h.uuids); assertEquals(now, h.now)
        h.c.confirmReposition(h.ctx); assertEquals(writes, h.requests.size) // Old confirmation cannot act on drag.
        h.c.confirmImpactDrag(ctx, moved); h.c.confirmImpactDrag(ctx, moved)
        assertEquals(writes + 1, h.requests.size); assertNull(h.ui.impactDrag)
        val arrow = h.ui.selectedEnd!!.end.impacts.single()
        assertEquals(moved, arrow.point); assertEquals(1, arrow.repositions.size); assertEquals(uuids, h.uuids)
    }
    @Test fun `cancel restores saved point and precision without a write`() = EditorHarness().use { h ->
        h.editing(); h.tap(.105f); h.c.precision(h.ctx,true); val saved=h.ui.saved;val writes=h.requests.size
        val ctx=h.drag();assertTrue(h.ui.precision);assertFalse(h.ui.canAim);assertTrue(h.ui.guardTargetTouches)
        val t=h.c.beginImpactDrag(ctx,h.ui.selectedImpact!!.point)!!;h.c.finishImpactDrag(t,moved)
        h.c.closeSelection(h.ctx)
        assertSame(saved,h.ui.saved);assertEquals(writes,h.requests.size);assertNull(h.ui.impactDrag);assertTrue(h.ui.precision)
    }
    @Test fun `interrupted refinement retains the last released candidate`() = EditorHarness().use { h ->
        h.editing();h.tap(.105f);val ctx=h.drag()
        var t=h.c.beginImpactDrag(ctx,h.ui.selectedImpact!!.point)!!;h.c.finishImpactDrag(t,moved)
        t=h.c.beginImpactDrag(ctx,moved)!!;h.c.moveImpactDrag(t,NormalizedPoint(.8f,0f));h.c.cancelAim(t)
        h.c.finishImpactDrag(t,NormalizedPoint(.8f,0f));assertEquals(moved,h.ui.edit!!.candidate)
    }
    @Test fun `cancel and reopen invalidate mode tickets and old callbacks`() = EditorHarness().use { h ->
        h.editing();h.tap(.105f);val saved=h.ui.saved!!;val old=h.drag();val p=h.ui.selectedImpact!!.point
        val t=h.c.beginImpactDrag(old,p)!!;h.c.closeSelection(h.ctx);h.c.close();h.c.open(saved.uuid)
        val fresh=h.drag();assertNotEquals(old,fresh)
        assertNull(h.c.beginImpactDrag(old,p));h.c.finishImpactDrag(t,moved);h.c.confirmImpactDrag(old,moved)
        assertEquals(fresh,h.ui.impactDrag);assertNull(h.ui.edit!!.candidate)
        val freshToken=h.c.beginImpactDrag(fresh,p)!!;h.c.finishImpactDrag(freshToken,moved)
        val writes=h.requests.size
        // Equal coordinates do not make a confirmation from the previous rendering current.
        h.c.confirmImpactDrag(old,moved);assertEquals(writes,h.requests.size)
        assertEquals(moved,h.ui.edit!!.candidate);assertEquals(fresh,h.ui.impactDrag)
        h.c.confirmImpactDrag(fresh,moved);assertEquals(writes+1,h.requests.size)
    }
    @Test fun `wrong owner and wrong start point never acquire a gesture`() = EditorHarness().use { h ->
        h.editing();h.tap(.105f);val ctx=h.drag();val p=h.ui.selectedImpact!!.point
        assertNull(h.c.beginImpactDrag(ctx.copy(ticket=ctx.ticket+1),p))
        assertNull(h.c.beginImpactDrag(ctx.copy(owner=ctx.owner.copy(impactUuid="other")),p))
        assertNull(h.c.beginImpactDrag(ctx,moved));assertNull(h.ui.activeAim)
    }
    @Test fun `precision callbacks and stale taps cannot change a direct candidate`() = EditorHarness().use { h ->
        h.editing();h.tap(.105f);h.c.precision(h.ctx,true);val ctx=h.drag();val t=h.c.beginImpactDrag(ctx,h.ui.selectedImpact!!.point)!!
        h.c.moveAim(t,moved);h.c.finishAim(t,moved);assertNotNull(h.ui.activeAim)
        h.c.finishImpactDrag(t,moved);h.c.tapped(h.ctx,NormalizedPoint(.9f,0f));assertEquals(moved,h.ui.edit!!.candidate)
        h.c.confirmImpactDrag(ctx,NormalizedPoint(.25f,.10001f));assertEquals(0,h.ui.selectedImpact!!.repositions.size)
    }
    @Test fun `validated and completed ends never allow direct drag`() = EditorHarness().use { h ->
        h.editing();h.tap(.105f);h.c.validateEnd();h.selectFirst();h.c.beginDragReposition(h.ctx)
        assertNull(h.ui.impactDrag);h.c.complete();h.c.beginDragReposition(h.ctx);assertNull(h.ui.impactDrag)
    }
    @Test fun `mixed session drag uses effective compound profile and preserves previous classic end`() = EditorHarness().use { h ->
        h.editing();h.tap(.075f);h.c.validateEnd();val previous=h.ui.saved!!.ends.first()
        h.c.editNextEnd(h.ui.epoch)
        h.c.chooseProfile(h.token,com.lmarchery.core.scoring.profiles.WorldArcheryIndoorTargetProfiles.compound40.uuid)
        h.c.confirmForm(h.token);h.tap(.025f)
        val ctx=h.drag();val t=h.c.beginImpactDrag(ctx,h.ui.selectedImpact!!.point)!!
        val point=NormalizedPoint(.075f,0f);h.c.finishImpactDrag(t,point)
        assertEquals(9,h.ui.edit!!.preview!!.scoreFinal);assertEquals(10,h.ui.selectedImpact!!.scoreFinal)
        h.c.confirmImpactDrag(ctx,point)
        assertEquals(9,h.ui.selectedImpact!!.scoreFinal);assertEquals(previous,h.ui.saved!!.ends.first())
    }
    @Test fun `uncertain commit retains exact intention without automatic replay`() = EditorHarness().use { h ->
        h.editing();h.tap(.105f);val ctx=h.drag();val t=h.c.beginImpactDrag(ctx,h.ui.selectedImpact!!.point)!!;h.c.finishImpactDrag(t,moved)
        h.write={ConfiguredWriteResult.CommitUnconfirmed(it,java.io.IOException("Synthetic lost acknowledgement"))}
        h.c.confirmImpactDrag(ctx,moved);val pending=h.ui.pending;val writes=h.requests.size
        h.c.confirmImpactDrag(ctx,moved);h.c.beginDragReposition(h.ctx)
        assertSame(pending,h.ui.pending);assertEquals(writes,h.requests.size);assertNull(h.c.beginImpactDrag(ctx,moved))
    }
}

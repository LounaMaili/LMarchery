package com.lmarchery.feature.configured

import com.lmarchery.backup.V3TestData as f
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.*
import com.lmarchery.core.model.session.*
import com.lmarchery.core.scoring.fixtures.ClassicTargetFixtures
import com.lmarchery.feature.sessions.*
import kotlin.test.*

class ConfiguredEditorImpactTest {
    @Test fun `competition near cordon keeps geometry until explicit decision using effective target`(): Unit = EditorHarness().use { h ->
        h.editing(); h.tap(.105f); assertEquals(9,h.ui.selectedEnd!!.end.impacts.single().scoreFinal)
        h.selectFirst(); h.c.beginDecision(h.ctx); val boundary=h.ui.scoreOptions!!.nearby.single { it.inner.score==10 && it.outer.score==9 }
        h.c.boundary(h.ctx,boundary.reference); h.c.scoreValue(h.ctx,boundary.inner); h.c.confirmDecision(h.ctx)
        val arrow=h.ui.selectedImpact!!; assertEquals(9,arrow.scoreCalculated); assertEquals(10,arrow.scoreFinal); assertTrue(arrow.finalScoreDecision.provenance.isManual)
    }
    @Test fun `exceptional reason and legal profile values required before confirming`(): Unit = EditorHarness().use { h ->
        h.editing(); h.tap(.25f); h.selectFirst(); h.c.beginDecision(h.ctx); h.c.exceptional(h.ctx,true)
        h.c.scoreValue(h.ctx,BoundaryValue(9)); val n=h.requests.size; h.c.confirmDecision(h.ctx); assertEquals(n,h.requests.size)
        h.c.decisionReason(h.ctx,"Observed"); h.c.scoreValue(h.ctx,BoundaryValue(99)); h.c.confirmDecision(h.ctx); assertEquals(n,h.requests.size)
        h.c.scoreValue(h.ctx,BoundaryValue(9)); h.c.confirmDecision(h.ctx); assertEquals(9,h.ui.selectedImpact!!.scoreFinal); assertEquals(8,h.ui.selectedImpact!!.scoreCalculated)
    }
    @Test fun `manual decision invalidated by two successive repositionings with immutable history`(): Unit = EditorHarness(f.mixed()).use { h ->
        h.open(); val e=h.ui.saved!!.ends[1]; h.c.selectEnd(e.uuid); h.selectFirst(); h.c.beginDecision(h.ctx); h.c.exceptional(h.ctx,true)
        h.c.scoreValue(h.ctx,BoundaryValue(10)); h.c.decisionReason(h.ctx,"Correction"); h.c.confirmDecision(h.ctx)
        val before=h.ui.selectedImpact!!; h.c.beginReposition(h.ctx); h.c.repositionPoint(h.ctx,NormalizedPoint(.25f,0f))
        assertFalse(h.ui.canConfirmReposition); val n=h.requests.size; h.c.confirmReposition(h.ctx); assertEquals(n,h.requests.size)
        h.c.repositionReason(h.ctx," Position incorrecte "); h.c.confirmReposition(h.ctx); val after=h.ui.selectedImpact!!
        assertEquals(8,after.scoreFinal); assertEquals(before.finalScoreDecision,after.repositions.single().previousDecision)
        assertEquals(before.finalScoreDecision,after.repositions.single().invalidatedManualDecision); assertTrue(before.repositions.isEmpty())
        h.c.beginReposition(h.ctx); h.c.repositionPoint(h.ctx,NormalizedPoint(.35f,0f)); h.c.confirmReposition(h.ctx)
        assertEquals(2,h.ui.selectedImpact!!.repositions.size); assertEquals(after.repositions.first(),h.ui.selectedImpact!!.repositions.first()); assertEquals(7,h.ui.selectedImpact!!.scoreFinal)
    }
    @Test fun `X M and geometric indicators remain coherent after corrections`(): Unit = EditorHarness().use { h ->
        h.c.create(); h.c.title(h.token,"Generic X"); h.c.chooseProfile(h.token,ClassicTargetFixtures.classic40.uuid); h.c.confirmForm(h.token); h.c.start(); h.nextEnd()
        h.tap(0f); h.tap(1.1f); assertTrue(h.ui.selectedEnd!!.end.impacts.first().isX); assertTrue(h.ui.selectedEnd!!.end.impacts.last().isMiss)
        h.selectFirst(); h.c.beginReposition(h.ctx); h.c.repositionPoint(h.ctx,NormalizedPoint(.15f,0f)); h.c.confirmReposition(h.ctx)
        assertFalse(h.ui.selectedImpact!!.isX); assertEquals(9,h.ui.selectedImpact!!.scoreFinal)
        h.closeSelection(); h.c.selectImpact(h.ctx,h.ui.selectedEnd!!.end.impacts.last().uuid); h.c.beginReposition(h.ctx); h.c.repositionPoint(h.ctx,NormalizedPoint(.95f,0f)); h.c.confirmReposition(h.ctx)
        assertFalse(h.ui.selectedImpact!!.isMiss); assertEquals(1,h.ui.selectedImpact!!.scoreFinal)
    }
    @Test fun `precise proposal cancel release and stale token never write`(): Unit = EditorHarness().use { h ->
        h.editing(); h.c.precision(h.ctx,true); val ctx=h.ctx; val token=h.c.beginAim(ctx,NormalizedPoint(.105f,0f))!!
        h.c.moveAim(token,NormalizedPoint(.25f,0f)); assertEquals(3,h.requests.size); h.c.cancelAim(token)
        h.c.finishAim(token,NormalizedPoint(.35f,0f)); assertNull(h.ui.position)
        val next=h.c.beginAim(ctx,NormalizedPoint(.15f,0f))!!; h.c.finishAim(next,NormalizedPoint(.15f,0f)); assertNotNull(h.ui.position)
        h.c.cancelPosition(h.ctx); h.c.confirmPosition(ctx); assertNull(h.ui.position); assertTrue(h.ui.selectedEnd!!.end.impacts.isEmpty())
    }
    @Test fun `callbacks for another session end revision or selected impact are ignored`(): Unit = EditorHarness().use { h ->
        h.editing(); val old=h.ctx; h.tap(.15f); val n=h.requests.size; h.c.tapped(old,NormalizedPoint(.25f,0f)); assertEquals(n,h.requests.size)
        h.c.tapped(h.ctx.copy(owner=h.ctx.owner.copy(sessionUuid=f.id(999))),NormalizedPoint(.25f,0f)); assertEquals(n,h.requests.size)
        val before=h.ctx; h.nextEnd(); h.c.tapped(before,NormalizedPoint(.25f,0f)); assertTrue(h.ui.selectedEnd!!.end.impacts.isEmpty())
        h.c.selectEnd(before.owner.endUuid); val selectCtx=h.ctx; h.selectFirst(); h.c.beginReposition(selectCtx); assertEquals(ImpactProposal.Selection,h.ui.edit!!.proposal)
    }
    @Test fun `unsubmitted correction blocks form navigation and cannot leak across ends`(): Unit = EditorHarness().use { h ->
        h.editing(); h.tap(.15f); h.selectFirst(); h.c.beginDecision(h.ctx); val exact=h.ui.edit; val n=h.requests.size
        h.c.nextEnd(); h.c.editMetadata(); h.c.complete(); h.c.validateEnd(); h.c.selectEnd(h.ui.selectedEndUuid!!)
        assertEquals(exact,h.ui.edit); assertEquals(n,h.requests.size); h.closeSelection(); assertTrue(h.ui.canForm)
    }
    @Test fun `new judgment is possible after traced reposition without losing old trace`(): Unit = EditorHarness(f.mixed()).use { h ->
        h.open(); h.c.selectEnd(h.ui.saved!!.ends[1].uuid); h.selectFirst(); h.c.beginReposition(h.ctx); h.c.repositionPoint(h.ctx,NormalizedPoint(.055f,0f)); h.c.confirmReposition(h.ctx)
        val trace=h.ui.selectedImpact!!.repositions; h.c.beginDecision(h.ctx); val b=h.ui.scoreOptions!!.nearby.first { it.inner.score==10 }; h.c.boundary(h.ctx,b.reference); h.c.scoreValue(h.ctx,b.inner); h.c.confirmDecision(h.ctx)
        assertEquals(trace,h.ui.selectedImpact!!.repositions); assertEquals(10,h.ui.selectedImpact!!.scoreFinal)
    }
    @Test fun `judgment outside tactile tolerance uses another actual boundary without automatic promotion`(): Unit = EditorHarness().use { h ->
        h.editing(); h.tap(.25f); h.selectFirst(); h.c.beginDecision(h.ctx)
        val boundary=h.ui.scoreOptions!!.other.first { it.inner.score==10 && it.outer.score==9 }
        val n=h.requests.size; h.c.boundary(h.ctx,boundary.reference); h.c.scoreValue(h.ctx,boundary.inner); h.c.confirmDecision(h.ctx)
        assertEquals(n,h.requests.size)
        h.c.showBoundaries(h.ctx); h.c.boundary(h.ctx,boundary.reference); h.c.scoreValue(h.ctx,boundary.inner); h.c.confirmDecision(h.ctx)
        assertEquals(8,h.ui.selectedImpact!!.scoreCalculated);assertEquals(10,h.ui.selectedImpact!!.scoreFinal)
    }
    @Test fun `indoor non X profile rejects an X correction and preserves candidate until legal value`(): Unit = EditorHarness().use { h ->
        h.editing();h.tap(0f);h.selectFirst();h.c.beginDecision(h.ctx);h.c.exceptional(h.ctx,true);h.c.decisionReason(h.ctx,"Observation")
        val n=h.requests.size;h.c.scoreValue(h.ctx,BoundaryValue(10,true));h.c.confirmDecision(h.ctx)
        assertEquals(n,h.requests.size);assertFalse(h.ui.selectedImpact!!.isX);assertNull(h.ui.proposedScore)
    }
    @Test fun `reposition candidate at original point clears old candidate without any save`(): Unit = EditorHarness().use { h ->
        h.editing();h.tap(.15f);h.selectFirst();h.c.beginReposition(h.ctx);h.c.repositionPoint(h.ctx,NormalizedPoint(.25f,0f));assertTrue(h.ui.canConfirmReposition)
        val n=h.requests.size;h.c.repositionPoint(h.ctx,NormalizedPoint(.15f,0f));h.c.confirmReposition(h.ctx)
        assertEquals(n,h.requests.size);assertFalse(h.ui.canConfirmReposition);assertNull(h.ui.edit!!.candidate)
    }

    @Test fun `invalid boundary replacement cannot accidentally confirm the older selected judgment`(): Unit = EditorHarness().use { h ->
        h.editing();h.tap(.105f);h.selectFirst();h.c.beginDecision(h.ctx)
        val valid=h.ui.scoreOptions!!.nearby.first();h.c.boundary(h.ctx,valid.reference);h.c.scoreValue(h.ctx,valid.inner);assertTrue(h.ui.canConfirmScore)
        val n=h.requests.size;h.c.boundary(h.ctx,BoundaryReference(BoundaryType.SCORE_ZONES,.123f));h.c.confirmDecision(h.ctx)
        assertEquals(n,h.requests.size);assertFalse(h.ui.canConfirmScore);assertNull(h.ui.proposedScore);assertNull(h.ui.selectedBoundary)
    }

}

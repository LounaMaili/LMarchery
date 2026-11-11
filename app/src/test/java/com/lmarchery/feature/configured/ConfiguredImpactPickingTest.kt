package com.lmarchery.feature.configured

import com.lmarchery.backup.V3TestData as f
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.feature.sessions.*
import kotlinx.coroutines.*
import kotlin.test.*

class ConfiguredImpactPickingTest {
    private val nine = NormalizedPoint(.105f, 0f)
    @Test fun `single hit opens existing selection with no UUID date or write`() = EditorHarness().use { h ->
        h.editing(); h.tap(.105f); val before = h.ui.saved; val now = h.now; val allocations = h.uuids; val writes = h.requests.size
        val arrow = h.ui.selectedEnd!!.end.impacts.single()
        h.c.beginImpactPicking(h.ctx); val context = h.ui.impactPicking!!.context
        assertTrue(h.ui.guardTargetTouches)
        h.c.pickImpact(context, nine, .001f)
        assertEquals(arrow, h.ui.selectedImpact); assertNull(h.ui.impactPicking); assertFalse(h.ui.guardTargetTouches)
        assertSame(before, h.ui.saved); assertEquals(now, h.now); assertEquals(allocations, h.uuids); assertEquals(writes, h.requests.size)
    }
    @Test fun `overlapping arrows require explicit choice and reject an unlisted UUID`() = EditorHarness().use { h ->
        h.editing(); h.tap(.105f); h.tap(.105f); val writes = h.requests.size
        val arrows = h.ui.selectedEnd!!.end.impacts
        h.c.beginImpactPicking(h.ctx); val context = h.ui.impactPicking!!.context
        h.c.pickImpact(context, nine, .001f)
        assertEquals(arrows.map { it.uuid }, h.ui.impactPicking!!.candidates); assertNull(h.ui.edit)
        h.c.choosePickedImpact(context, f.id(999)); assertNull(h.ui.edit)
        h.c.choosePickedImpact(context, arrows.last().uuid)
        assertEquals(arrows.last(), h.ui.selectedImpact); assertEquals(writes, h.requests.size)
    }
    @Test fun `empty hit never adds and cancellation preserves precise input mode`() = EditorHarness().use { h ->
        h.editing(); h.tap(.105f); h.c.precision(h.ctx, true)
        val before = h.ui.saved; val writes = h.requests.size
        h.c.beginImpactPicking(h.ctx); val context = h.ui.impactPicking!!.context
        h.c.pickImpact(context, NormalizedPoint(.8f, .8f), .01f)
        assertTrue(h.ui.impactPicking!!.hasTapped); assertTrue(h.ui.impactPicking!!.candidates.isEmpty())
        h.c.cancelImpactPicking(context)
        assertTrue(h.ui.precision); assertNull(h.ui.edit); assertSame(before, h.ui.saved); assertEquals(writes, h.requests.size)
    }
    @Test fun `cancel restart and wrong owner callbacks never act on the new picker`() = EditorHarness().use { h ->
        h.editing(); h.tap(.105f); h.c.beginImpactPicking(h.ctx); val old = h.ui.impactPicking!!.context
        h.c.cancelImpactPicking(old); h.c.beginImpactPicking(h.ctx); val current = h.ui.impactPicking!!.context
        assertNotEquals(old, current)
        h.c.pickImpact(old, nine, .01f); h.c.choosePickedImpact(old, h.ui.selectedEnd!!.end.impacts.single().uuid)
        h.c.pickImpact(current.copy(sessionUuid = f.id(999)), nine, .01f)
        h.c.pickImpact(current.copy(endUuid = f.id(999)), nine, .01f)
        h.c.cancelImpactPicking(old)
        assertEquals(current, h.ui.impactPicking!!.context); assertFalse(h.ui.impactPicking!!.hasTapped); assertNull(h.ui.edit)
    }
    @Test fun `picking blocks placement forms navigation and close without changing saved aggregate`() = EditorHarness().use { h ->
        h.editing(); h.tap(.105f); val before = h.ui.saved; val writes = h.requests.size; val stale = h.ctx
        h.c.beginImpactPicking(h.ctx)
        h.c.tapped(stale, NormalizedPoint(.25f, 0f)); h.c.tapped(h.ctx, NormalizedPoint(.25f, 0f))
        h.c.nextEnd(); h.c.editMetadata(); h.c.validateEnd(); h.c.complete(); h.c.close()
        h.c.selectImpact(h.ctx, h.ui.selectedEnd!!.end.impacts.single().uuid)
        assertNotNull(h.ui.impactPicking); assertNull(h.ui.form); assertNull(h.ui.edit); assertTrue(h.ui.open)
        assertSame(before, h.ui.saved); assertEquals(writes, h.requests.size)
    }
    @Test fun `validated and completed end cannot enter picking`() = EditorHarness().use { h ->
        h.editing(); h.tap(.105f); h.c.validateEnd()
        h.c.beginImpactPicking(h.ctx); assertNull(h.ui.impactPicking)
        h.c.complete(); h.c.beginImpactPicking(h.ctx); assertNull(h.ui.impactPicking)
    }
    @Test fun `precise candidate and in flight write retain their own intention`() = runBlocking<Unit> {
        EditorHarness().use { h ->
            h.editing(); h.tap(.105f); h.c.precision(h.ctx, true)
            val token = h.c.beginAim(h.ctx, nine)!!; h.c.finishAim(token, nine); val position = h.ui.position
            h.c.beginImpactPicking(h.ctx); assertNull(h.ui.impactPicking); assertSame(position, h.ui.position)
            h.c.cancelPosition(h.ctx); h.c.precision(h.ctx, false)
            val gate = CompletableDeferred<Unit>(); h.hold = gate; h.tap(.25f); val pending = h.ui.pending
            h.c.beginImpactPicking(h.ctx); assertNull(h.ui.impactPicking); assertSame(pending, h.ui.pending)
            gate.complete(Unit); yield(); assertNull(h.ui.pending)
        }
    }
    @Test fun `invalid tolerance clears an old ambiguous hit without changing data`() = EditorHarness().use { h ->
        h.editing(); h.tap(.105f); h.tap(.105f); val before = h.ui.saved
        h.c.beginImpactPicking(h.ctx); val context = h.ui.impactPicking!!.context
        h.c.pickImpact(context, nine, .01f); assertEquals(2, h.ui.impactPicking!!.candidates.size)
        h.c.pickImpact(context, nine, Float.NaN)
        assertTrue(h.ui.impactPicking!!.candidates.isEmpty()); assertEquals(ConfiguredProblemKind.VALIDATION, h.ui.problem!!.kind)
        assertSame(before, h.ui.saved)
    }
    @Test fun `selection keeps the effective compound profile and original automatic position provenance`() = EditorHarness(f.mixed()).use { h ->
        h.open(); val end = h.ui.saved!!.ends[1]; h.c.selectEnd(end.uuid)
        val original = end.end.impacts.single(); val before = h.ui.saved; val writes = h.requests.size
        assertEquals(.075f, original.point.x); assertEquals(9, original.scoreFinal)
        h.c.beginImpactPicking(h.ctx); h.c.pickImpact(h.ui.impactPicking!!.context, original.point, .001f)
        assertEquals(original, h.ui.selectedImpact); assertEquals(end.configuration, h.ui.selectedEnd!!.configuration)
        assertSame(before, h.ui.saved); assertEquals(writes, h.requests.size)
    }
    @Test fun `off canvas miss remains valid and selectable by number without invisible spatial choice`() = EditorHarness().use { h ->
        h.editing(); h.tap(1.1f); val arrow = h.ui.selectedEnd!!.end.impacts.single(); val before = h.ui.saved
        h.c.beginImpactPicking(h.ctx); val context = h.ui.impactPicking!!.context
        h.c.pickImpact(context, NormalizedPoint(1f, 0f), .2f)
        assertTrue(h.ui.impactPicking!!.candidates.isEmpty()); assertNull(h.ui.edit)
        h.c.cancelImpactPicking(context); h.c.selectImpact(h.ctx, arrow.uuid)
        assertEquals(arrow, h.ui.selectedImpact); assertTrue(h.ui.selectedImpact!!.isMiss); assertSame(before, h.ui.saved)
    }
}

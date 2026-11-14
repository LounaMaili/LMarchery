package com.lmarchery.feature.sessions

import kotlin.test.*

/** JVM presentation contract only; not a measurement of Android touch rectangles. */
class ImpactPickingActionsTest {
    private val picking = ImpactPicking(ImpactPickContext("session", "end", 1))
    @Test fun `activation and cancellation have permanent distinct ordered slots`() {
        for (state in listOf(null, picking, picking.copy(hasTapped = true), picking.copy(candidates = listOf("a", "b")))) {
            for (canBegin in listOf(false, true)) {
                val actions = impactPickingActions(state, canBegin)
                assertEquals(listOf(ImpactPickingAction.BEGIN, ImpactPickingAction.CANCEL), actions.map { it.action })
                assertEquals(2, actions.map { it.action.label }.distinct().size)
            }
        }
    }
    @Test fun `repeated activation cannot turn the activation slot into cancellation`() {
        val idle = impactPickingActions(null, true)
        val active = impactPickingActions(picking, true) // Even contradictory input fails closed.
        assertTrue(idle[0].enabled); assertFalse(idle[1].enabled)
        assertEquals(idle[0].action, active[0].action); assertFalse(active[0].enabled)
        assertTrue(active[1].enabled)
    }
    @Test fun `empty full busy and read only states never offer an unguarded activation`() {
        assertTrue(impactPickingActions(null, false).none { it.enabled })
    }
    @Test fun `cancel and restart retain labels and positions without reusing the old context`() {
        val restarted = picking.copy(context = picking.context.copy(ticket = 2))
        assertEquals(impactPickingActions(null, true).map { it.action }, impactPickingActions(restarted, false).map { it.action })
        assertNotEquals(picking.context, restarted.context)
    }
}

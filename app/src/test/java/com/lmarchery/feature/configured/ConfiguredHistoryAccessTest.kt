package com.lmarchery.feature.configured

import com.lmarchery.backup.V3TestData as f
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.BoundaryValue
import com.lmarchery.core.model.session.SessionStatus
import com.lmarchery.core.scoring.configured.ConfiguredSession
import com.lmarchery.data.EquipmentKind
import com.lmarchery.feature.sessions.configuredHistoryLabels
import kotlin.test.*

/** Pure text assertions and controller doubles; these are not SQLite or Compose device tests. */
class ConfiguredHistoryAccessTest {
    @Test fun `draft card offers preparation without claiming read only`() {
        val labels = configuredHistoryLabels(SessionStatus.DRAFT)
        assertEquals("Ouvrir le brouillon", labels.openActionLabel)
        assertEquals("Brouillon · configurations par volée", labels.statusLine)
    }

    @Test fun `active card offers resumption without claiming read only`() {
        val labels = configuredHistoryLabels(SessionStatus.IN_PROGRESS)
        assertEquals("Reprendre la séance", labels.openActionLabel)
        assertEquals("En cours · configurations par volée", labels.statusLine)
    }

    @Test fun `completed card explicitly offers read only consultation`() {
        val labels = configuredHistoryLabels(SessionStatus.COMPLETED)
        assertEquals("Consulter — lecture seule", labels.openActionLabel)
        assertEquals("Terminée · configurations par volée · lecture seule", labels.statusLine)
    }

    private fun sessions(): List<ConfiguredSession> {
        val active = f.mixed()
        return listOf(ConfiguredSession.create(active.uuid, active.metadata, active.initialConfiguration, f.now),
            active, f.mixed(completed = true))
    }

    @Test fun `card access agrees with controller guards for all three statuses`(): Unit = sessions().forEach { session ->
        EditorHarness(session).use { h ->
            h.open()
            val completed = session.status == SessionStatus.COMPLETED
            assertEquals(completed, configuredHistoryLabels(session.status).statusLine.contains("lecture seule"))
            assertEquals(!completed, h.ui.canForm)
            assertEquals(session.status == SessionStatus.IN_PROGRESS, h.ui.sessionActive)
            assertTrue(h.ui.canNavigate)
            h.c.editMetadata()
            if (completed) assertNull(h.ui.form) else {
                assertIs<ConfiguredForm.Metadata>(h.ui.form)
                h.c.cancelForm(h.token)
            }
            assertTrue(h.requests.isEmpty())
            assertEquals(session, h.ui.saved)
        }
    }

    @Test fun `completed aggregate rejects score position metadata material and stale confirmations`(): Unit =
        EditorHarness(f.mixed(completed = true)).use { h ->
            h.open()
            val expected = h.ui.saved
            val context = h.ctx
            h.c.editMetadata(); h.c.nextEnd(); h.c.start(); h.c.complete(); h.c.validateEnd()
            h.c.title(-1, "Unexpected title"); h.c.chooseEquipment(-1, EquipmentKind.BOW, null)
            h.c.chooseEquipment(-1, EquipmentKind.ARROW_SET, null); h.c.confirmForm(-1)
            h.c.tapped(context, NormalizedPoint(.3f, 0f)); h.c.precision(context, true)
            assertNull(h.c.beginAim(context, NormalizedPoint(.3f, 0f)))
            h.c.confirmPosition(context); h.c.selectImpact(context, f.id(13))
            h.c.beginReposition(context); h.c.repositionPoint(context, NormalizedPoint(.4f, 0f))
            h.c.repositionReason(context, "Unexpected correction"); h.c.confirmReposition(context)
            h.c.beginDecision(context); h.c.exceptional(context, true); h.c.scoreValue(context, BoundaryValue(9))
            h.c.decisionReason(context, "Unexpected decision"); h.c.confirmDecision(context)
            assertFalse(h.ui.mutable); assertFalse(h.ui.canSelectImpact)
            assertNull(h.ui.form); assertNull(h.ui.pending); assertNull(h.ui.edit); assertNull(h.ui.position)
            assertTrue(h.requests.isEmpty()); assertEquals(expected, h.ui.saved); assertEquals(expected, h.stored)
        }

    @Test fun `draft and active configurable material changes remain explicit and preserve all ends`(): Unit =
        sessions().filter { it.status != SessionStatus.COMPLETED }.forEach { session ->
            EditorHarness(session).use { h ->
                h.open(); h.c.editMetadata()
                h.c.chooseEquipment(h.token, EquipmentKind.BOW, null)
                h.c.chooseEquipment(h.token, EquipmentKind.ARROW_SET, null)
                assertTrue(h.requests.isEmpty())
                h.c.confirmForm(h.token)
                val actual = h.ui.saved!!
                assertEquals(1, h.requests.size)
                assertNull(actual.metadata.bowUuid); assertNull(actual.metadata.arrowSetUuid)
                assertEquals(session.metadata.date, actual.metadata.date)
                assertEquals(session.ends, actual.ends)
                assertEquals(session.initialConfiguration, actual.initialConfiguration)
                assertEquals(session.initialConfigurationOrigin, actual.initialConfigurationOrigin)
                assertEquals(session.status, actual.status)
            }
        }

    @Test fun `consultation navigation and reopening never request a revision`(): Unit = sessions().forEach { session ->
        EditorHarness(session).use { h ->
            h.open()
            session.ends.forEach { h.c.selectEnd(it.uuid) }
            h.c.close(); assertFalse(h.ui.open)
            h.open()
            assertEquals(2, h.reads)
            assertEquals(session, h.ui.saved)
            assertEquals(session, h.stored)
            assertTrue(h.requests.isEmpty())
        }
    }
}

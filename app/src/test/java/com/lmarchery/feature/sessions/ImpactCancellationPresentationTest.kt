package com.lmarchery.feature.sessions

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.session.*
import com.lmarchery.core.model.time.SessionDateTime
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.core.scoring.profiles.BuiltInTargetRegistry
import com.lmarchery.core.scoring.profiles.WorldArcheryIndoorTargetProfiles as WA
import com.lmarchery.core.scoring.scoreArrowImpact
import com.lmarchery.feature.configured.ConfiguredEditorState
import java.time.Instant
import java.time.ZoneId
import kotlin.test.*

/** Pure presentation assertions repeated by Android variants; these are not device/SQLite tests. */
class ImpactCancellationPresentationTest {
    private val now = Instant.parse("2026-11-15T12:00:00Z")
    private fun id(n: Int) = "cd000000-0000-4000-8000-${n.toString().padStart(12, '0')}"
    private fun session(): Session {
        val impacts = (1..3).map { i -> scoreArrowImpact(id(10 + i), i, WA.recurve40,
            NormalizedPoint(if (i == 3) 1.1f else 0f, 0f), ScoringMode.TRAINING, createdAt = now) }
        val end = End(id(2), 1, EndStatus.EDITING, impacts, createdAt = now, updatedAt = now)
        return Session(id(1), SessionDateTime.fromInstant(now, ZoneId.of("UTC")), SessionStatus.IN_PROGRESS,
            startedAt = now, title = "Synthetic", targetType = WA.recurve40, distanceM = 18, arrowCountMode = ArrowCountMode.FIXED,
            arrowCountPerEnd = 3, isIndoor = true, scoringMode = ScoringMode.TRAINING, ends = listOf(end), createdAt = now, updatedAt = now)
    }
    private fun content(s: Session) = validatedSessionContent(TargetProfileSnapshot(s.targetType,
        BuiltInTargetRegistry.find(s.targetType.uuid)!!.regulatoryEdition), s)

    @Test fun `four historical numbers with one cancellation still form an editable three arrow end`() {
        var s = session().cancelImpact(id(2), id(12), now)
        s = s.withEnd(s.ends.single().withImpact(scoreArrowImpact(id(14), 4, WA.recurve40, NormalizedPoint(.25f, 0f), ScoringMode.TRAINING, createdAt = now)))
        val ui = SessionUiState(screen = SessionScreen.SESSION, saved = content(s))
        assertTrue(s.supportsSessionEditing()); assertFalse(ui.canAddImpact); assertTrue(ui.canValidate)
        val configured = ConfiguredEditorState(open = true, saved = content(s).configuredLegacyValue(), selectedEndUuid = id(2))
        assertFalse(configured.hasCapacity); assertFalse(configured.canAdd)
        assertEquals(listOf(1, 3, 4), s.ends.single().activeImpacts.map { it.index })
    }

    @Test fun `cancelled selection is never offered for judgment displacement or ambiguous picking`() {
        val s = session().cancelImpact(id(2), id(12), now)
        val ui = SessionUiState(screen = SessionScreen.SESSION, saved = content(s), selectedEndUuid = id(2),
            impactEdit = ImpactEdit(id(1), id(2), id(12)))
        assertNull(ui.selectedImpact); assertFalse(ui.canBeginReposition); assertFalse(ui.canBeginScoreCorrection)
        assertTrue(s.ends.single().activeImpacts.none { it.uuid == id(12) })
        assertEquals(2, s.ends.single().activeImpacts.size)
    }

    @Test fun `local saving and confirmed dialogs block underlying actions until deliberately closed`() {
        val s = session(); val base = content(s)
        for (phase in CancellationPhase.entries) {
            val dialog = ImpactCancellationDialog(s.uuid, id(2), id(12), 42, phase = phase)
            val ui = SessionUiState(screen = SessionScreen.SESSION, saved = base, cancellation = dialog)
            assertFalse(ui.canNavigate); assertFalse(ui.canAddImpact); assertFalse(ui.canValidate); assertFalse(ui.canComplete)
            val configured = ConfiguredEditorState(open = true, saved = base.configuredLegacyValue(), selectedEndUuid = id(2), cancellation = dialog)
            assertFalse(configured.canNavigate); assertFalse(configured.canAdd); assertFalse(configured.canSelectImpact)
        }
    }

    @Test fun `score rows distinguish active miss from preserved cancelled ten`() {
        val s = session().cancelImpact(id(2), id(12), now)
        val row = presentEndScore(s.ends.single())
        assertEquals(listOf("Flèche 1 · 10 points", "Flèche 3 · M (0 point)"), row.arrows)
        assertEquals(10, row.total)
        assertEquals(10, s.ends.single().impacts[1].scoreFinal)
        assertTrue(s.ends.single().impacts[2].isMiss)
    }
}

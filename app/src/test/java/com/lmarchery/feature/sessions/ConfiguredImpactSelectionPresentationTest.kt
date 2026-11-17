package com.lmarchery.feature.sessions

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.session.*
import com.lmarchery.core.model.time.SessionDateTime
import com.lmarchery.core.scoring.configured.*
import com.lmarchery.core.scoring.profiles.BuiltInTargetRegistry
import com.lmarchery.core.scoring.profiles.WorldArcheryIndoorTargetProfiles as WA
import com.lmarchery.core.scoring.scoreArrowImpact
import com.lmarchery.feature.configured.configuredImpactSelectionLabel
import java.time.Instant
import java.time.ZoneId
import kotlin.test.*

class ConfiguredImpactSelectionPresentationTest {
    private val at = Instant.parse("2026-11-15T12:00:00Z")
    private fun id(n: Int) = "cd250000-0000-4000-8000-${n.toString().padStart(12, '0')}"
    private fun session(): ConfiguredSession {
        val profile = BuiltInTargetRegistry.find(WA.recurve40.uuid)!!
        var s = ConfiguredSession.create(id(1), ConfiguredSessionMetadata(SessionDateTime.fromInstant(at, ZoneId.of("UTC")), "Synthetic label"),
            EndConfiguration(profile.definition, profile.regulatoryEdition, 18, ArrowCountMode.FIXED, 3, true, ScoringMode.TRAINING), at)
            .start(at).createNextEnd(id(2), at)
        for (i in 1..2) s = s.withImpact(id(2), scoreArrowImpact(id(10 + i), i, profile.definition,
            NormalizedPoint(.1f, 0f), ScoringMode.TRAINING, createdAt = at))
        return s
    }
    @Test fun `active impact keeps its real index and correction phase`() {
        val s = session(); val edit = ImpactEdit(s.uuid, id(2), id(12))
        assertEquals("Flèche sélectionnée #2 · SELECTED", configuredImpactSelectionLabel(s.ends.single(), edit))
    }
    @Test fun `confirmed cancellation reads the preserved identity rather than a null active selection`() {
        val old = session(); val s = old.cancelImpact(id(2), id(12), at.plusSeconds(1))
        val edit = ImpactEdit(s.uuid, id(2), id(12), ImpactEditPhase.CONFIRMED)
        assertEquals(old.ends.single().end.impacts, s.ends.single().end.impacts)
        assertTrue(s.ends.single().end.activeImpacts.none { it.uuid == id(12) })
        assertEquals("Saisie #2 annulée · historique conservé.", configuredImpactSelectionLabel(s.ends.single(), edit))
    }
    @Test fun `missing impact or wrong owner is explicit and never replaced by a fictitious identity`() {
        val s = session(); val end = s.ends.single()
        for (bad in listOf(ImpactEdit(id(90), end.uuid, id(12)), ImpactEdit(s.uuid, id(91), id(12)), ImpactEdit(s.uuid, end.uuid, id(92))))
            assertEquals("Sélection incohérente : relisez la séance avant toute correction.", configuredImpactSelectionLabel(end, bad))
    }
}

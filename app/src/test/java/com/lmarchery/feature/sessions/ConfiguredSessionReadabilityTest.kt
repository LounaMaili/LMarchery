package com.lmarchery.feature.sessions

import com.lmarchery.backup.V3TestData as f
import com.lmarchery.core.model.session.ArrowCountMode
import com.lmarchery.core.model.session.EndStatus
import com.lmarchery.core.scoring.configured.ConfiguredSession
import com.lmarchery.core.model.session.NextEndConfigurationChoice
import kotlin.test.*

class ConfiguredSessionReadabilityTest {
    @Test fun `a draft shows its setup and readable date without exposing the zone`() {
        val saved = f.mixed()
        val draft = ConfiguredSession.create(saved.uuid, saved.metadata, saved.initialConfiguration, f.now)
        assertEquals(listOf(f.classic), draft.effectiveConfigurations())
        assertEquals("18 m · ${f.classic.targetType.name} · Salle · Compétition", draft.configurationLabel())
        assertEquals("23 octobre 2026 à 03:02", draft.metadata.date.shortLabel())
        assertTrue(draft.civilDateLabel().contains("Europe/Paris (+02:00)"))
    }

    @Test fun `mixed settings show their count and leave each captured configuration intact`() {
        val session = f.mixed()
        assertEquals(listOf(f.classic, f.compound), session.effectiveConfigurations())
        assertEquals("2 configurations de tir · réglages affichés par volée", session.configurationLabel())
        assertEquals("70 m · ${f.compound.targetType.name} · Extérieur · Entraînement",
            session.ends.last().configuration.shootingLabel())
        assertEquals("6 flèches par volée", session.ends.last().configuration.capacityLabel())
        assertEquals(EndStatus.VALIDATED, session.ends.first().end.status)
    }

    @Test fun `the effective first end can use settings distinct from the original draft`() {
        val saved = f.mixed()
        val session = ConfiguredSession.create(saved.uuid, saved.metadata, f.classic, f.now).start(f.now)
            .createNextEnd(f.id(2), f.now, NextEndConfigurationChoice.Replace(f.compound))
        assertEquals(f.compound.shootingLabel(), session.configurationLabel())
    }

    @Test fun `configuration identity includes the regulatory edition even when the face and distance match`() {
        val saved = f.mixed()
        val revisedFace = f.classic.targetType.copy(uuid = f.id(9), isBuiltIn = false)
        val revised = f.classic.copy(targetType = revisedFace, regulatoryEdition = "TEST")
        val session = ConfiguredSession.create(saved.uuid, saved.metadata, f.classic, f.now).start(f.now)
            .createNextEnd(f.id(2), f.now)
            .createNextEnd(f.id(3), f.now, NextEndConfigurationChoice.Replace(revised))
        assertEquals(f.classic.shootingLabel(), revised.shootingLabel())
        assertEquals(2, session.effectiveConfigurations().size)
        assertTrue(session.configurationLabel().startsWith("2 configurations"))
    }

    @Test fun `natural labels distinguish free capacity, status, no X and true maximum`() {
        assertEquals("Nombre libre de flèches",
            f.classic.copy(arrowCountMode = ArrowCountMode.FREE, arrowCountPerEnd = null).capacityLabel())
        assertEquals("Validée", EndStatus.VALIDATED.label())
        assertEquals("Provisoire", EndStatus.EDITING.label())
        assertEquals(listOf("Nombre de 9 : 1", "Flèches notées M (0 point) : 2"),
            scoreCountLabels(9, 1, 1, false, 0, 2))
        assertEquals(listOf("Nombre de 10 : 2", "Nombre de 9 : 1", "Flèches dans le X central : 1", "Flèches notées M (0 point) : 0"),
            scoreCountLabels(10, 2, 1, true, 1, 0))
    }
}

package com.lmarchery.core.scoring

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.session.*
import com.lmarchery.core.scoring.profiles.BuiltInTargetRegistry
import com.lmarchery.core.scoring.profiles.WorldArcheryIndoorTargetProfiles as WA
import kotlin.test.*

class EndConfigurationProfileCompatibilityTest {
    @Test fun `all published profiles and editions are preserved by custom shooting parameters`() {
        for (entry in BuiltInTargetRegistry.all) {
            val before = entry.definition.copy()
            val configuration = EndConfiguration(entry.definition, entry.regulatoryEdition, 50,
                ArrowCountMode.FIXED, 6, false, ScoringMode.TRAINING)
            assertSame(entry.definition, configuration.targetType)
            assertEquals(entry.regulatoryEdition, configuration.regulatoryEdition)
            assertEquals(before, entry.definition)
            assertEquals(entry, BuiltInTargetRegistry.find(entry.definition.uuid))
            assertEquals(50, configuration.distanceM)
        }
    }

    @Test fun `custom distance never selects another official division or changes geometric scoring`() {
        val point = NormalizedPoint(.075f, 0f)
        for ((profile, expected) in listOf(WA.recurve40 to 10, WA.compound40 to 9)) {
            val original = EndConfiguration(profile, WA.RULEBOOK_VERSION, 18, ArrowCountMode.FIXED, 3, true, ScoringMode.TRAINING)
            val custom = original.copy(distanceM = 70, isIndoor = false).withArrowCountShortcut(EndArrowCountShortcut.SIX)
            assertEquals(original.targetType, custom.targetType); assertEquals(original.regulatoryEdition, custom.regulatoryEdition)
            assertEquals(expected, calculateScore(custom.targetType, point).score)
            assertEquals(calculateScore(original.targetType, point), calculateScore(custom.targetType, point))
            assertFalse(custom.targetType.xRingEnabled); assertEquals(18, profile.defaultDistanceM)
        }
    }

    @Test fun `same diameter classic and compound profiles compare differently and need an explicit change`() {
        val recurve = EndConfiguration(WA.recurve40, WA.RULEBOOK_VERSION, 18, ArrowCountMode.FIXED, 3, true, ScoringMode.TRAINING)
        val compound = recurve.copy(targetType = WA.compound40)
        assertEquals(recurve.targetType.diameterCm, compound.targetType.diameterCm)
        assertEquals(setOf(EndConfigurationField.TARGET_TYPE), compareEndConfigurations(recurve, compound))
        assertSame(recurve, resolveNextEndConfiguration(compound, recurve))
        assertSame(compound, resolveNextEndConfiguration(recurve, recurve, NextEndConfigurationChoice.Replace(compound)))
        assertEquals(WA.recurve40, recurve.targetType)
    }
}

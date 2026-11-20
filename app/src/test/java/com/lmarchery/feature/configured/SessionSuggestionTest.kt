package com.lmarchery.feature.configured

import com.lmarchery.backup.V3TestData as fixture
import com.lmarchery.core.database.SessionPresetRepository
import com.lmarchery.core.model.session.ArrowCountMode
import com.lmarchery.core.model.session.EndConfiguration
import com.lmarchery.core.model.session.ScoringMode
import com.lmarchery.core.model.target.TargetType
import com.lmarchery.core.scoring.configured.SessionPreset
import com.lmarchery.core.scoring.fixtures.ClassicTargetFixtures as Classic
import com.lmarchery.core.scoring.profiles.WorldArcheryIndoorTargetProfiles as WA
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionSuggestionTest {
    @Test
    fun `five suggestions retain the exact profile definition and edition`() {
        val expected = listOf(
            indoor(WA.recurve40),
            indoor(WA.compound40),
            outdoor(Classic.classic80, 30),
            outdoor(Classic.classic80, 50),
            outdoor(Classic.classic122, 70),
        )
        assertEquals(expected, SessionSuggestion.entries.map { it.configuration.configuration() })
    }

    @Test
    fun `selection only updates the draft and edited settings can become a separate personal preset`() {
        val personal = SessionPreset(fixture.id(70), "Mon réglage", indoor(WA.recurve40), 8,
            fixture.id(71), fixture.id(72), fixture.now)
        val catalog = PresetCatalog(personal)
        EditorHarness(presets = catalog).use { editor ->
            val controller = editor.c
            controller.create()
            val token = editor.token
            controller.applySuggestion(token, SessionSuggestion.INDOOR_COMPOUND)
            assertEquals(ConfiguredMetadataDraft(), (editor.ui.form as ConfiguredForm.Creation).metadata)

            controller.applyPreset(token, personal.uuid)
            controller.location(token, "Terrain")
            controller.notes(token, "Réglage à essayer")
            val previous = editor.ui.form as ConfiguredForm.Creation
            val previousCatalog = editor.ui.presets

            controller.applySuggestion(token, SessionSuggestion.OUTDOOR_50)
            val selected = editor.ui.form as ConfiguredForm.Creation
            assertEquals(outdoor(Classic.classic80, 50), selected.configuration.configuration())
            assertEquals(previous.metadata, selected.metadata)
            assertEquals(previous.presetEquipment, selected.presetEquipment)
            assertEquals(previousCatalog, editor.ui.presets)
            assertEquals(listOf(personal), catalog.items)
            assertEquals(0, catalog.insertions)
            assertEquals(0, editor.uuids)
            assertTrue(editor.requests.isEmpty())
            assertNull(editor.ui.saved)

            controller.distance(token, "42")
            controller.namePreset(token)
            controller.presetName(token, "Extérieur personnalisé")
            controller.savePreset(token)
            val newPreset = catalog.items.last()
            assertEquals(outdoor(Classic.classic80, 42), newPreset.configuration)
            assertEquals(personal, catalog.items.first())
            assertEquals(personal.bowUuid, newPreset.bowUuid)
            assertEquals(personal.arrowSetUuid, newPreset.arrowSetUuid)
            assertEquals(1, catalog.insertions)
            assertTrue(editor.requests.isEmpty())
        }
    }

    @Test
    fun `a stale suggestion and a choice while naming a preset leave the draft intact`() {
        EditorHarness(presets = PresetCatalog()).use { editor ->
            val controller = editor.c
            controller.create()
            val oldToken = editor.token
            controller.cancelForm(oldToken)
            controller.close()
            controller.create()
            val current = editor.ui.form
            controller.applySuggestion(oldToken, SessionSuggestion.OUTDOOR_70)
            controller.namePreset(editor.token)
            controller.applySuggestion(editor.token, SessionSuggestion.OUTDOOR_70)
            assertEquals(current, editor.ui.form)
            assertTrue(editor.requests.isEmpty())
        }
    }

    private fun indoor(target: TargetType) =
        EndConfiguration(target, "2026-01-27", 18, ArrowCountMode.FIXED, 3, true, ScoringMode.TRAINING)

    private fun outdoor(target: TargetType, distance: Int) =
        EndConfiguration(target, null, distance, ArrowCountMode.FIXED, 6, false, ScoringMode.TRAINING)

    private class PresetCatalog(vararg presets: SessionPreset) : SessionPresetRepository {
        val items = presets.toMutableList()
        var insertions = 0
            private set

        override suspend fun list() = items.toList()

        override suspend fun add(preset: SessionPreset): Boolean {
            insertions++
            return items.add(preset)
        }

        override suspend fun update(expected: SessionPreset, proposed: SessionPreset) = error("Unexpected preset edit")
        override suspend fun delete(expected: SessionPreset) = error("Unexpected preset deletion")
    }
}

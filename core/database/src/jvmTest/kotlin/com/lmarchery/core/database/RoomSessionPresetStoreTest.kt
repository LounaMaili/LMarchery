package com.lmarchery.core.database

import com.lmarchery.core.model.session.ArrowCountMode
import com.lmarchery.core.model.session.EndArrowCountShortcut
import com.lmarchery.core.model.session.EndConfiguration
import com.lmarchery.core.model.session.ScoringMode
import com.lmarchery.core.scoring.configured.SessionPreset
import com.lmarchery.core.scoring.profiles.WorldArcheryIndoorTargetProfiles as WA
import com.lmarchery.feature.configured.ConfiguredForm
import com.lmarchery.feature.configured.ConfiguredProblemKind
import com.lmarchery.feature.configured.ConfiguredSessionController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

class RoomSessionPresetStoreTest {
    @TempDir lateinit var directory: Path

    private val profile = WA.compound40
    private val now = Instant.parse("2026-11-19T10:20:30.123456789Z")
    private fun uuid(index: Int) = "b0000000-0000-4000-8000-${index.toString().padStart(12, '0')}"
    private fun preset() = SessionPreset(uuid(1), "Poulies 🏹 extérieur",
        EndConfiguration(profile, WA.RULEBOOK_VERSION, 50, ArrowCountMode.FIXED, 6, false, ScoringMode.COMPETITION),
        plannedEndCount = 10, bowUuid = uuid(2), arrowSetUuid = uuid(3), createdAt = now)

    @Test fun `preset validates name exact profile edition count and references`() {
        val value = preset()
        assertFailsWith<IllegalArgumentException> { value.copy(name = " ") }
        assertFailsWith<IllegalArgumentException> { value.copy(name = "a".repeat(SessionPreset.MAX_NAME_LENGTH + 1)) }
        assertFailsWith<IllegalArgumentException> { value.copy(configuration = value.configuration.copy(regulatoryEdition = null)) }
        assertFailsWith<IllegalArgumentException> { value.copy(plannedEndCount = 0) }
        assertFailsWith<IllegalArgumentException> { value.copy(bowUuid = "invalide") }
    }

    @Test fun `Room reopens exact presets with opaque references and rejects collisions without overwrite`() = runBlocking {
        val path = directory.resolve("presets.db")
        val fixed = preset()
        val free = fixed.copy(uuid = uuid(4), name = "Libre",
            configuration = fixed.configuration.copy(arrowCountMode = ArrowCountMode.FREE, arrowCountPerEnd = null),
            plannedEndCount = null, bowUuid = null, arrowSetUuid = null)
        openSessionPresetStore(path).use { store ->
            assertTrue(store.add(fixed))
            assertTrue(store.add(free))
            assertFalse(store.add(fixed.copy(uuid = uuid(5), plannedEndCount = 4)))
            assertFalse(store.add(fixed.copy(name = "Autre nom")))
        }
        openSessionPresetStore(path).use { store -> assertEquals(listOf(free, fixed), store.list()) }
    }

    @Test fun `app copies a preset into editable fields and saves an independent session and a new preset`() = runBlocking {
        val path = directory.resolve("presets.db")
        openSessionPresetStore(path).use { store -> assertTrue(store.add(preset())) }
        openSessionPresetStore(path).use { store ->
            AppIntegrationHarness(directory.resolve("sessions.db")).use { h ->
                val editor = ConfiguredSessionController(h.repository, h.scope, h.legacy::acquireImport,
                    h.legacy::releaseArchiveOperation, h.legacy::refresh, presets = store)
                h.onUi { editor.create() }
                h.awaitUiState(editor.state) { it.presets?.items != null }
                val token = editor.state.value.form!!.token
                h.onUi {
                    editor.applyPreset(token, uuid(1))
                    val applied = assertIs<ConfiguredForm.Creation>(editor.state.value.form)
                    assertEquals(preset().configuration, applied.configuration.configuration())
                    assertEquals("10", applied.metadata.plannedEnds)
                    assertEquals(preset().name, applied.metadata.title)
                    assertEquals(uuid(2), applied.metadata.equipment.bowUuid)
                    assertEquals(uuid(3), applied.metadata.equipment.arrowSetUuid)
                    editor.title(token, "Séance indépendante")
                    editor.distance(token, "35")
                    editor.shortcut(token, EndArrowCountShortcut.FREE)
                    editor.namePreset(token)
                    editor.presetName(token, "  Réglages copie  ")
                    editor.savePreset(token)
                }
                h.awaitUiState(editor.state) { !it.busy }
                val copy = store.list().single { it.name == "Réglages copie" }
                assertEquals(35, copy.configuration.distanceM)
                assertEquals(ArrowCountMode.FREE, copy.configuration.arrowCountMode)
                assertEquals(uuid(2), copy.bowUuid)
                assertTrue(h.archiveV4().sessions.isEmpty())
                h.onUi { editor.distance(token, "30"); editor.confirmForm(token) }
                val saved = h.awaitUiState(editor.state) { !it.busy }.saved!!
                assertEquals(30, saved.initialConfiguration.distanceM)
                assertEquals("Séance indépendante", saved.metadata.title)
                assertEquals(uuid(2), saved.metadata.bowUuid)
                assertEquals(uuid(3), saved.metadata.arrowSetUuid)
                assertEquals(saved, h.archiveV4().sessions.single())
                assertTrue(h.archiveV4().bows.isEmpty())
                assertTrue(h.archiveV4().arrowSets.isEmpty())
                assertEquals(preset(), store.list().single { it.uuid == uuid(1) })
                assertEquals(copy, store.list().single { it.uuid == copy.uuid })
            }
        }
    }

    @Test fun `two SQLite writers cannot overwrite the same expected revision or delete a changed preset`() = runBlocking {
        val path = directory.resolve("presets.db")
        val original = preset()
        val other = original.copy(uuid = uuid(5), name = "Occupé")
        val revised = original.copy(name = "Renommé", configuration = original.configuration.copy(distanceM = 45))
        val competing = original.copy(name = "Renommé autrement")
        openSessionPresetStore(path).use { first ->
            assertTrue(first.add(original))
            assertTrue(first.add(other))
            assertEquals(SessionPresetChange.NAME_TAKEN, first.update(original, revised.copy(name = other.name)))
            assertFailsWith<IllegalArgumentException> { first.update(original, revised.copy(createdAt = now.plusSeconds(1))) }
            openSessionPresetStore(path).use { second ->
                val results = listOf(
                    async(Dispatchers.Default) { first.update(original, revised) },
                    async(Dispatchers.Default) { second.update(original, competing) },
                ).awaitAll()
                assertEquals(1, results.count { it == SessionPresetChange.APPLIED })
                assertEquals(1, results.count { it == SessionPresetChange.REVISION_CONFLICT })
                assertEquals(SessionPresetChange.REVISION_CONFLICT, second.delete(original))
                assertEquals(other, first.list().single { it.uuid == other.uuid })
            }
        }
        openSessionPresetStore(path).use { store ->
            val winner = store.list().single { it.uuid == original.uuid }
            assertTrue(winner == revised || winner == competing)
            assertEquals(SessionPresetChange.APPLIED, store.delete(winner))
            assertEquals(SessionPresetChange.REVISION_CONFLICT, store.delete(winner))
        }
        openSessionPresetStore(path).use { assertEquals(listOf(other), it.list()) }
    }

    @Test fun `cancelled and outdated preset actions write nothing and confirmed changes preserve the existing session`() = runBlocking {
        val path = directory.resolve("presets.db")
        val original = preset()
        openSessionPresetStore(path).use { store ->
            assertTrue(store.add(original))
            AppIntegrationHarness(directory.resolve("sessions.db")).use { h ->
                val editor = ConfiguredSessionController(h.repository, h.scope, h.legacy::acquireImport,
                    h.legacy::releaseArchiveOperation, h.legacy::refresh, presets = store)
                fun token() = editor.state.value.form!!.token
                h.onUi { editor.create() }
                h.awaitUiState(editor.state) { it.presets?.items != null }
                h.onUi { editor.applyPreset(token(), original.uuid); editor.confirmForm(token()) }
                h.awaitUiState(editor.state) { it.saved != null && !it.busy }
                val session = h.archiveV4()
                h.onUi { editor.close() }
                h.ready()
                h.onUi { editor.create() }
                h.awaitUiState(editor.state) { it.presets?.items != null }
                h.onUi {
                    editor.title(token(), "Brouillon à conserver")
                    editor.editPreset(token(), original.uuid)
                    val abandoned = token()
                    editor.title(abandoned, "Abandonné")
                    editor.cancelForm(abandoned)
                    editor.confirmForm(abandoned)
                    editor.requestPresetDeletion(token(), original.uuid)
                    val deletion = token()
                    editor.cancelForm(deletion)
                    editor.confirmForm(deletion)
                }
                assertEquals(listOf(original), store.list())
                h.onUi {
                    editor.editPreset(token(), original.uuid)
                    val edit = token()
                    editor.title(edit, "  Renommé  ")
                    editor.distance(edit, "35")
                    editor.shortcut(edit, EndArrowCountShortcut.FREE)
                    editor.planned(edit, "12")
                    editor.confirmForm(edit)
                    editor.confirmForm(edit)
                }
                h.awaitUiState(editor.state) { !it.busy }
                assertEquals("Brouillon à conserver", assertIs<ConfiguredForm.Creation>(editor.state.value.form).metadata.title)
                val revised = original.copy(name = "Renommé", plannedEndCount = 12,
                    configuration = original.configuration.copy(distanceM = 35, arrowCountMode = ArrowCountMode.FREE, arrowCountPerEnd = null))
                assertEquals(listOf(revised), store.list())
                assertEquals(session, h.archiveV4())
                h.onUi {
                    editor.requestPresetDeletion(token(), original.uuid)
                    val deletion = token()
                    editor.confirmForm(deletion)
                    editor.confirmForm(deletion)
                }
                h.awaitUiState(editor.state) { !it.busy }
                assertTrue(store.list().isEmpty())
                assertEquals(session, h.archiveV4())
            }
        }
        openSessionPresetStore(path).use { assertTrue(it.list().isEmpty()) }
    }

    @Test fun `interruption after a preset commit refreshes the catalogue and invalidates the old command`() = runBlocking {
        val original = preset()
        val renamed = original.copy(name = "Renommé")
        openSessionPresetStore(directory.resolve("presets.db")).use { store ->
            assertTrue(store.add(original))
            var updates = 0
            var deletions = 0
            val interrupted = object : SessionPresetRepository by store {
                override suspend fun update(expected: SessionPreset, proposed: SessionPreset): SessionPresetChange {
                    updates++
                    assertEquals(SessionPresetChange.APPLIED, store.update(expected, proposed))
                    throw CancellationException("Synthetic interruption after update commit")
                }

                override suspend fun delete(expected: SessionPreset): SessionPresetChange {
                    deletions++
                    assertEquals(SessionPresetChange.APPLIED, store.delete(expected))
                    throw CancellationException("Synthetic interruption after deletion commit")
                }
            }
            AppIntegrationHarness(directory.resolve("sessions.db")).use { h ->
                val editor = ConfiguredSessionController(h.repository, h.scope, h.legacy::acquireImport,
                    h.legacy::releaseArchiveOperation, h.legacy::refresh, presets = interrupted)
                fun token() = editor.state.value.form!!.token
                h.onUi { editor.create() }
                h.awaitUiState(editor.state) { it.presets?.items != null }
                val edit = h.onUi {
                    editor.editPreset(token(), original.uuid)
                    token().also { editor.title(it, renamed.name); editor.confirmForm(it) }
                }
                val updateResult = h.awaitUiState(editor.state) { !it.busy }
                assertEquals(ConfiguredProblemKind.UNCONFIRMED, updateResult.problem?.kind)
                h.awaitUiState(editor.state) { it.presets?.items == listOf(renamed) }
                h.onUi { editor.confirmForm(edit) }
                assertEquals(1, updates)

                val deletion = h.onUi {
                    editor.requestPresetDeletion(token(), renamed.uuid)
                    token().also(editor::confirmForm)
                }
                val deleteResult = h.awaitUiState(editor.state) { !it.busy }
                assertEquals(ConfiguredProblemKind.UNCONFIRMED, deleteResult.problem?.kind)
                h.awaitUiState(editor.state) { it.presets?.items == emptyList<SessionPreset>() }
                h.onUi { editor.confirmForm(deletion) }
                assertEquals(1, deletions)
                assertTrue(store.list().isEmpty())
                assertTrue(h.archiveV4().sessions.isEmpty())
            }
        }
    }
}

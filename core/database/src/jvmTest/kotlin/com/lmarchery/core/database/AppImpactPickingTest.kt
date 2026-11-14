package com.lmarchery.core.database

import com.lmarchery.core.database.ArchiveV3DiskData as d
import com.lmarchery.core.database.EquipmentTestData as f
import com.lmarchery.core.export.ArchiveV3JsonCodec
import com.lmarchery.feature.sessions.*
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.scoring.archive.v3.DomainArchiveV3
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

/** Real application controllers + Room + native disk SQLite; no Android input injection. */
class AppImpactPickingTest {
    @TempDir lateinit var directory: Path
    private val path get() = directory.resolve("impact-picking.db")

    @Test fun `reopened editors reject old picker callbacks and duplicate choices under SQL write guards`() = runBlocking<Unit> {
        for (configured in listOf(true, false)) AppIntegrationHarness(directory.resolve("reopen-$configured.db")).use { h ->
            if (configured) { h.editing(); h.tap(.105f); h.tap(.105f) }
            else {
                h.onUi { h.legacy.configure(); h.legacy.createSession() }; h.ready()
                repeat(2) { h.onUi { h.legacy.addImpact(NormalizedPoint(.105f, 0f)) }; h.ready() }
            }
            val before = h.archive(); val rows = d.contents(h.path)
            val bytes = ArchiveV3JsonCodec().encode(before)
            f.denyWrites(h.path, d.tables)
            if (configured) {
                val uuid = h.state.saved!!.uuid
                val old = h.onUi { h.configured.beginImpactPicking(h.context); h.state.impactPicking!!.context }
                h.onUi { h.configured.abandonLocalAndClose() }; h.ready()
                h.onUi { h.configured.open(uuid) }; h.idle()
                h.onUi {
                    h.configured.precision(h.context, true)
                    h.configured.beginImpactPicking(h.context); val current = h.state.impactPicking!!
                    h.configured.beginImpactPicking(h.context); assertSame(current, h.state.impactPicking)
                    val arrow = h.state.selectedEnd!!.end.impacts.last()
                    h.configured.pickImpact(old, arrow.point, .001f); h.configured.choosePickedImpact(old, arrow.uuid)
                    h.configured.cancelImpactPicking(old); assertSame(current, h.state.impactPicking)
                    h.configured.pickImpact(current.context, arrow.point, .001f)
                    assertEquals(2, h.state.impactPicking!!.candidates.size)
                    h.configured.choosePickedImpact(current.context, arrow.uuid); val edit = h.state.edit!!
                    h.configured.choosePickedImpact(current.context, arrow.uuid); assertSame(edit, h.state.edit)
                    assertEquals(arrow, h.state.selectedImpact); assertTrue(h.state.precision)
                    assertNull(h.state.pending); assertNull(h.state.problem)
                }
            } else {
                val uuid = h.legacy.state.value.session!!.uuid
                val old = h.onUi { h.legacy.beginImpactPicking(); h.legacy.state.value.impactPicking!!.context }
                h.onUi { h.legacy.cancelImpactPicking(old); h.legacy.returnToList() }; h.ready()
                h.onUi { h.legacy.openSession(uuid) }; h.ready()
                h.onUi {
                    h.legacy.setPrecisionAim(true)
                    h.legacy.beginImpactPicking(); val current = h.legacy.state.value.impactPicking!!
                    h.legacy.beginImpactPicking(); assertSame(current, h.legacy.state.value.impactPicking)
                    val arrow = h.legacy.state.value.displayedEnd!!.impacts.last()
                    h.legacy.pickImpact(old, arrow.point, .001f); h.legacy.choosePickedImpact(old, arrow.uuid)
                    h.legacy.cancelImpactPicking(old); assertSame(current, h.legacy.state.value.impactPicking)
                    h.legacy.pickImpact(current.context, arrow.point, .001f)
                    assertEquals(2, h.legacy.state.value.impactPicking!!.candidates.size)
                    h.legacy.choosePickedImpact(current.context, arrow.uuid); val edit = h.legacy.state.value.impactEdit!!
                    h.legacy.choosePickedImpact(current.context, arrow.uuid); assertSame(edit, h.legacy.state.value.impactEdit)
                    assertEquals(arrow, h.legacy.state.value.selectedImpact); assertTrue(h.legacy.state.value.precisionAimEnabled)
                    assertNull(h.legacy.state.value.pending); assertNull(h.legacy.state.value.problem)
                }
            }
            assertEquals(rows, d.contents(h.path)); assertEquals(before, h.archive())
            assertContentEquals(bytes, ArchiveV3JsonCodec().encode(h.archive())); assertRecipeIntegrity(h.path)
        }
    }

    private suspend fun manualFirst(h: AppIntegrationHarness) {
        h.onUi {
            h.configured.selectImpact(h.context, h.state.selectedEnd!!.end.impacts.first().uuid)
            h.configured.beginDecision(h.context)
            val boundary = h.state.scoreOptions!!.nearby.single { it.inner.score == 10 && it.outer.score == 9 }
            h.configured.boundary(h.context, boundary.reference)
            h.configured.scoreValue(h.context, boundary.inner)
        }
        h.change { h.configured.confirmDecision(h.context) }
        h.onUi { h.configured.closeSelection(h.context) }
    }

    @Test fun `configured selection ambiguity and cancellation execute no DML on any of twelve tables`() = runBlocking<Unit> {
        AppIntegrationHarness(path).use { h ->
            h.editing(); h.tap(.105f); h.tap(.105f); manualFirst(h)
            val saved = h.state.saved; val arrows = h.state.selectedEnd!!.end.impacts
            val before = d.contents(path); val archive = h.archive(); val bytes = ArchiveV3JsonCodec().encode(archive)
            f.denyWrites(path, d.tables)
            h.onUi { h.configured.beginImpactPicking(h.context) }; val first = h.state.impactPicking!!.context
            h.onUi { h.configured.pickImpact(first, NormalizedPoint(.8f, .8f), .01f); h.configured.cancelImpactPicking(first) }
            h.onUi { h.configured.beginImpactPicking(h.context) }; val current = h.state.impactPicking!!.context
            h.onUi {
                h.configured.pickImpact(first, arrows.first().point, .01f)
                h.configured.pickImpact(current, arrows.first().point, .01f)
            }
            assertEquals(arrows.map { it.uuid }, h.state.impactPicking!!.candidates); assertNull(h.state.edit)
            h.assertOtherOperationsLocked()
            h.onUi { h.configured.choosePickedImpact(current, arrows.first().uuid) }
            assertEquals(arrows.first(), h.state.selectedImpact); assertSame(saved, h.state.saved)
            assertEquals(before, d.contents(path)); assertEquals(archive, h.archive())
            assertContentEquals(bytes, ArchiveV3JsonCodec().encode(h.archive())); assertRecipeIntegrity(path)
        }
    }

    @Test fun `historical full end uses spatial selection without any SQLite write or implicit conversion`() = runBlocking<Unit> {
        AppIntegrationHarness(path).use { h ->
            h.onUi { h.legacy.configure(); h.legacy.createSession() }; h.ready()
            for (r in listOf(.105f, .25f, .75f)) {
                h.onUi { h.legacy.addImpact(NormalizedPoint(r, 0f)) }; h.ready()
            }
            val saved = h.legacy.state.value.saved; val arrow = h.legacy.state.value.displayedEnd!!.impacts.first()
            val before = d.contents(path); val archive = h.archive(); f.denyWrites(path, d.tables)
            h.onUi { h.legacy.beginImpactPicking() }; val context = h.legacy.state.value.impactPicking!!.context
            h.onUi { h.legacy.pickImpact(context, arrow.point, .001f) }
            assertEquals(arrow, h.legacy.state.value.selectedImpact); assertSame(saved, h.legacy.state.value.saved)
            assertEquals(before, d.contents(path)); assertEquals(archive, h.archive())
            assertEquals(listOf("LEGACY_HOMOGENEOUS"), f.rows(path, "SELECT representation FROM session_configurations"))
            assertRecipeIntegrity(path)
        }
    }

    @Test fun `spatially selected manual decision keeps traced reposition and unrelated data after reopen`() = runBlocking<Unit> {
        lateinit var expected: DomainArchiveV3
        AppIntegrationHarness(path).use { h ->
            h.store.restoreV2(d.source())
            h.editing(); h.tap(.105f); manualFirst(h)
            val original = h.state.selectedEnd!!.end.impacts.single(); val before = h.archive(); val rows = d.contents(path)
            h.onUi { h.configured.beginImpactPicking(h.context) }; val context = h.state.impactPicking!!.context
            h.onUi {
                h.configured.pickImpact(context, original.point, .001f)
                h.configured.beginReposition(h.context)
                h.configured.repositionPoint(h.context, NormalizedPoint(.25f, 0f))
                h.configured.confirmReposition(h.context)
            }
            assertFalse(h.state.canConfirmReposition); assertEquals(rows, d.contents(path))
            h.onUi { h.configured.repositionReason(h.context, "Position observée après sélection explicite") }
            h.change { h.configured.confirmReposition(h.context) }
            val revised = h.state.selectedImpact!!
            assertEquals(original.uuid, revised.uuid); assertEquals(original.createdAt, revised.createdAt)
            assertEquals(8, revised.scoreFinal); assertEquals(original.finalScoreDecision, revised.repositions.single().invalidatedManualDecision)
            expected = h.archive()
            assertEquals(before.sessions.filter { it.uuid != h.state.saved!!.uuid }, expected.sessions.filter { it.uuid != h.state.saved!!.uuid })
            assertEquals(before.profiles, expected.profiles); assertEquals(before.bows, expected.bows); assertEquals(before.arrowSets, expected.arrowSets)
            assertRecipeIntegrity(path)
        }
        openSessionStore(path).use { reopened ->
            assertEquals(expected, reopened.fullV3()); assertRecipeIntegrity(path)
            assertContentEquals(ArchiveV3JsonCodec().encode(expected), ArchiveV3JsonCodec().encode(reopened.fullV3()))
        }
    }
    @Test fun `stable selection slots and repeated correction openings cause no DML in either editor`() = runBlocking<Unit> {
        for (configured in listOf(false, true)) for (count in listOf(1, 3)) {
            val file = directory.resolve("slots-$configured-$count.db")
            AppIntegrationHarness(file).use { h ->
                if (configured) { h.editing(); repeat(count) { h.tap(.105f) } }
                else {
                    h.onUi { h.legacy.configure(); h.legacy.createSession() }; h.ready()
                    repeat(count) { h.onUi { h.legacy.addImpact(NormalizedPoint(.105f, 0f)) }; h.ready() }
                }
                val before = d.contents(file); val archive = h.archive()
                val bytes = ArchiveV3JsonCodec().encode(archive)
                f.denyWrites(file, d.tables)
                h.onUi {
                    for (precise in listOf(false, true)) {
                        if (configured) {
                            h.configured.precision(h.context, precise)
                            val slots = impactPickingActions(h.state.impactPicking, h.state.canBeginImpactPicking)
                            h.configured.beginImpactPicking(h.context); val active = h.state.impactPicking!!
                            h.configured.beginImpactPicking(h.context)
                            assertSame(active, h.state.impactPicking)
                            assertFalse(impactPickingActions(active, false)[0].enabled)
                            h.configured.cancelImpactPicking(active.context); h.configured.cancelImpactPicking(active.context)
                            assertEquals(slots, impactPickingActions(h.state.impactPicking, h.state.canBeginImpactPicking))
                            assertEquals(precise, h.state.precision)
                            h.configured.selectImpact(h.context, h.state.selectedEnd!!.end.impacts.first().uuid)
                            h.configured.beginDecision(h.context); val edit = h.state.edit
                            h.configured.beginDecision(h.context); h.configured.beginReposition(h.context)
                            assertSame(edit, h.state.edit)
                            h.configured.closeSelection(h.context); assertNull(h.state.pending)
                        } else {
                            h.legacy.setPrecisionAim(precise)
                            val slots = impactPickingActions(h.legacy.state.value.impactPicking, h.legacy.state.value.canBeginImpactPicking)
                            h.legacy.beginImpactPicking(); val active = h.legacy.state.value.impactPicking!!
                            h.legacy.beginImpactPicking(); assertSame(active, h.legacy.state.value.impactPicking)
                            assertFalse(impactPickingActions(active, false)[0].enabled)
                            h.legacy.cancelImpactPicking(active.context); h.legacy.cancelImpactPicking(active.context)
                            assertEquals(slots, impactPickingActions(h.legacy.state.value.impactPicking, h.legacy.state.value.canBeginImpactPicking))
                            assertEquals(precise, h.legacy.state.value.precisionAimEnabled)
                            val end = h.legacy.state.value.displayedEnd!!
                            h.legacy.selectImpact(end.uuid, end.impacts.first().uuid)
                            h.legacy.beginScoreCorrection(); val edit = h.legacy.state.value.impactEdit
                            h.legacy.beginScoreCorrection(); h.legacy.beginReposition()
                            assertSame(edit, h.legacy.state.value.impactEdit)
                            h.legacy.cancelImpactEdit(); assertNull(h.legacy.state.value.pending)
                        }
                    }
                }
                assertEquals(before, d.contents(file)); assertEquals(archive, h.archive())
                assertContentEquals(bytes, ArchiveV3JsonCodec().encode(h.archive())); assertRecipeIntegrity(file)
            }
        }
    }

}

package com.lmarchery.core.database

import com.lmarchery.core.database.ArchiveV3DiskData as d
import com.lmarchery.core.database.EquipmentTestData as f
import com.lmarchery.core.export.ArchiveV3JsonCodec
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
}

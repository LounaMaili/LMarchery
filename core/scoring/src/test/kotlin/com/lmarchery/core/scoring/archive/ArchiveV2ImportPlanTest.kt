package com.lmarchery.core.scoring.archive

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.equipment.ArrowSet
import com.lmarchery.core.model.equipment.Bow
import com.lmarchery.core.model.session.*
import com.lmarchery.core.model.time.SessionDateTime
import com.lmarchery.core.scoring.fixtures.ClassicTargetFixtures
import java.time.Instant
import java.time.ZoneId
import kotlin.test.*

class ArchiveV2ImportPlanTest {
    private val now = Instant.parse("2026-10-17T10:00:00.123456789Z")
    private fun id(n: Int) = "a0000000-0000-4000-8000-${n.toString().padStart(12,'0')}"
    private val target = ClassicTargetFixtures.classic40.copy(uuid = id(900), isBuiltIn = false)
    private fun bow(n: Int = 800) = Bow(id(n), "Bow", createdAt = now, updatedAt = now)
    private fun arrows(n: Int = 801) = ArrowSet(id(n), "Arrows", createdAt = now, updatedAt = now)
    private fun session(seed: Int = 0) = Session(id(seed + 1), SessionDateTime.fromInstant(now, ZoneId.of("Europe/Paris")),
        SessionStatus.IN_PROGRESS, startedAt = now, title = "Synthetic planner", targetType = target, distanceM = 18, arrowCountMode = ArrowCountMode.FREE,
        isIndoor = true, scoringMode = ScoringMode.COMPETITION, bowUuid = id(800), arrowSetUuid = id(801),
        ends = listOf(End(id(seed + 2), 1, EndStatus.EDITING,
            listOf(ArrowImpact(id(seed + 3), 1, NormalizedPoint(.105f, -0.0f), inputMode = InputMode.MANUAL,
                createdAt = now, updatedAt = now, targetType = target)), createdAt = now, updatedAt = now)),
        createdAt = now, updatedAt = now)
    private fun archive(sessions: List<Session> = emptyList(), bows: List<Bow> = emptyList(),
        arrows: List<ArrowSet> = emptyList(), profiles: List<TargetProfileSnapshot> = sessions.map { TargetProfileSnapshot(it.targetType, null) }.distinct()): DomainArchiveV2 =
        assertIs<ArchiveV2Reconstruction.Accepted>(reconstructArchiveV2(snapshotArchiveV2(profiles, sessions, bows, arrows))).archive
    private fun rich() = archive(listOf(session()), listOf(bow()), listOf(arrows()))

    @Test fun `all four categories classify new and identical full state explicitly`() {
        val source = rich(); val new = planArchiveImportV2(source, archive())
        assertTrue(new.canImport); assertTrue(new.hasAdditions); assertFalse(new.isEmptyArchive)
        assertEquals(listOf(id(1)), new.sessions.newUuids); assertEquals(listOf(id(900)), new.profiles.newUuids)
        assertEquals(listOf(id(800)), new.bows.newUuids); assertEquals(listOf(id(801)), new.arrowSets.newUuids)
        val same = planArchiveImportV2(source, source)
        assertTrue(same.canImport); assertFalse(same.hasAdditions)
        assertEquals(listOf(id(1)), same.sessions.identicalUuids); assertEquals(listOf(id(900)), same.profiles.identicalUuids)
        assertEquals(listOf(id(800)), same.bows.identicalUuids); assertEquals(listOf(id(801)), same.arrowSets.identicalUuids)
    }
    @Test fun `equipment only and entirely empty archives are valid plans without deletion semantics`() {
        assertTrue(planArchiveImportV2(archive(bows = listOf(bow())), archive()).canImport)
        val empty = planArchiveImportV2(archive(), rich())
        assertTrue(empty.isEmptyArchive); assertFalse(empty.hasAdditions); assertTrue(empty.canImport)
    }
    @Test fun `unreferenced profiles can be imported as complete standalone roots`() {
        val p = TargetProfileSnapshot(target, "Club")
        val plan = planArchiveImportV2(archive(profiles = listOf(p)), archive())
        assertEquals(listOf(id(900)), plan.profiles.newUuids); assertTrue(plan.canImport)
    }
    @Test fun `same dates never make divergent material content identical`() {
        val old = rich()
        val source = archive(bows = listOf(bow().copy(notes = "Other")), arrows = listOf(arrows().copy(length = "Other")))
        val plan = planArchiveImportV2(source, old)
        assertFalse(plan.canImport); assertEquals(listOf(id(800)), plan.bows.conflictingUuids)
        assertEquals(listOf(id(801)), plan.arrowSets.conflictingUuids)
    }
    @Test fun `recent timestamps do not arbitrate a divergent session`() {
        val plan = planArchiveImportV2(archive(listOf(session().copy(title = "Other", updatedAt = now.plusSeconds(1)))), rich())
        assertEquals(listOf(id(1)), plan.sessions.conflictingUuids); assertFalse(plan.canImport)
    }
    @Test fun `impact content and provenance are compared even with unchanged aggregate dates`() {
        val s = session(); val e = s.ends.single()
        val altered = s.copy(ends = listOf(e.copy(impacts = listOf(e.impacts.single().copy(note = "Different")))))
        val plan = planArchiveImportV2(archive(listOf(altered)), rich())
        assertEquals(listOf(id(1)), plan.sessions.conflictingUuids)
        assertTrue(plan.conflicts.any { it.uuid == id(3) })
    }
    @Test fun `profile edition conflict blocks related otherwise identical sessions`() {
        val old = rich(); val p = TargetProfileSnapshot(target, "Different edition")
        val plan = planArchiveImportV2(archive(listOf(session()), profiles = listOf(p)), old)
        assertEquals(listOf(id(900)), plan.profiles.conflictingUuids)
        assertEquals(listOf(id(1)), plan.sessions.conflictingUuids); assertTrue(plan.sessions.identicalUuids.isEmpty())
    }
    @Test fun `global collisions distinguish every identity kind and parent`() {
        val source = rich()
        for (n in listOf(1, 2, 3, 900)) {
            val plan = planArchiveImportV2(source, archive(bows = listOf(bow(n))))
            assertTrue(plan.conflicts.any { it.uuid == id(n) }); assertFalse(plan.canImport)
        }
        assertFalse(planArchiveImportV2(archive(arrows = listOf(arrows(800))), archive(bows = listOf(bow()))).canImport)
        assertFalse(planArchiveImportV2(archive(bows = listOf(bow(3))), source).canImport)
    }
    @Test fun `descendant moved to a different incoming parent is a conflict`() {
        val s = session(); val another = session(100).copy(ends = listOf(s.ends.single()))
        val plan = planArchiveImportV2(archive(listOf(another)), rich())
        assertTrue(plan.conflicts.any { it.uuid == id(2) }); assertTrue(plan.sessions.newUuids.isEmpty())
    }
    @Test fun `opaque equipment references neither claim identities nor require fiches`() {
        val source = archive(listOf(session()))
        val plan = planArchiveImportV2(source, archive())
        assertTrue(plan.canImport); assertTrue(plan.bows.newUuids.isEmpty()); assertTrue(plan.arrowSets.newUuids.isEmpty())
        assertEquals(id(800), source.sessions.single().bowUuid)
    }
    @Test fun `root input order is irrelevant and output is sorted and immutable`() {
        val a = archive(listOf(session(100), session()), listOf(bow(810), bow()))
        val b = archive(listOf(session(), session(100)), listOf(bow(), bow(810)))
        val one = planArchiveImportV2(a, archive()); val two = planArchiveImportV2(b, archive())
        assertEquals(one, two); assertEquals(one.hashCode(), two.hashCode())
        assertFailsWith<UnsupportedOperationException> { (one.bows.newUuids as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (one.conflicts as MutableList).clear() }
    }
    @Test fun `mixed plan retains each category count without inventing updates`() {
        val plan = planArchiveImportV2(archive(listOf(session(), session(100)), listOf(bow(), bow(810)), listOf(arrows())), archive(listOf(session()), listOf(bow())))
        assertEquals(listOf(id(101)), plan.sessions.newUuids); assertEquals(listOf(id(1)), plan.sessions.identicalUuids)
        assertEquals(listOf(id(810)), plan.bows.newUuids); assertEquals(listOf(id(800)), plan.bows.identicalUuids)
        assertEquals(listOf(id(801)), plan.arrowSets.newUuids); assertTrue(plan.profiles.newUuids.isEmpty())
    }
}

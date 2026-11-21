package com.lmarchery.feature.sessions

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.BoundaryValue
import com.lmarchery.core.model.scoring.ScoreDecisionRequest
import com.lmarchery.core.model.session.ArrowCountMode
import com.lmarchery.core.model.session.ArrowImpact
import com.lmarchery.core.model.session.End
import com.lmarchery.core.model.session.EndConfiguration
import com.lmarchery.core.model.session.EndStatus
import com.lmarchery.core.model.session.ScoringMode
import com.lmarchery.core.model.session.Session
import com.lmarchery.core.model.session.SessionStatus
import com.lmarchery.core.model.target.TargetType
import com.lmarchery.core.model.time.SessionDateTime
import com.lmarchery.core.scoring.archive.TargetProfileSnapshot
import com.lmarchery.core.scoring.grouping.GroupingMissPolicy.EXCLUDE_MISSES
import com.lmarchery.core.scoring.grouping.GroupingMissPolicy.INCLUDE_MISSES
import com.lmarchery.core.scoring.grouping.GroupingMissPolicy
import com.lmarchery.core.scoring.grouping.GroupingUnit
import com.lmarchery.core.scoring.grouping.calculateEndGrouping
import com.lmarchery.core.scoring.profiles.BuiltInTargetRegistry
import com.lmarchery.core.scoring.profiles.WorldArcheryIndoorTargetProfiles
import com.lmarchery.core.scoring.scoreArrowImpact
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EndGroupingSummaryTest {
    private val now = Instant.parse("2026-11-20T12:00:00Z")
    private val recurve = IndoorProfile.RECURVE_40.target
    private val compound = WorldArcheryIndoorTargetProfiles.compound60

    private fun configuration(target: TargetType = recurve) = EndConfiguration(
        target, BuiltInTargetRegistry.find(target.uuid)?.regulatoryEdition, 18,
        ArrowCountMode.FREE, null, true, ScoringMode.TRAINING,
    )

    private fun arrow(index: Int, x: Float, target: TargetType = recurve) =
        scoreArrowImpact(id(), index, target, NormalizedPoint(x, 0f), ScoringMode.TRAINING, createdAt = now)

    private fun end(vararg impacts: ArrowImpact) =
        End(id(), 1, EndStatus.EDITING, impacts.toList(), createdAt = now, updatedAt = now)

    private fun id() = UUID.randomUUID().toString()

    @Test
    fun `reading is lazy runs off the caller thread and caches each filter across unit changes`() = runBlocking {
        val source = EndGroupingSummarySource(end(arrow(1, 0f), arrow(2, 1.5f)), configuration())
        val caller = Thread.currentThread()
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val calls = mutableListOf<Pair<Thread, GroupingMissPolicy>>()
            val reader = EndGroupingSummaryReader(source, dispatcher) { context, policy ->
                calls.add(Thread.currentThread() to policy)
                calculateEndGrouping(context, policy)
            }
            assertTrue(calls.isEmpty())
            val first = reader.read(INCLUDE_MISSES)
            GroupingUnit.entries.forEach { first.grouping.measurementsIn(it) }
            repeat(15) { assertSame(first, reader.read(INCLUDE_MISSES)) }
            val without = reader.read(EXCLUDE_MISSES)
            repeat(15) {
                assertSame(without, reader.read(EXCLUDE_MISSES))
                assertSame(first, reader.read(INCLUDE_MISSES))
            }
            assertEquals(listOf(INCLUDE_MISSES, EXCLUDE_MISSES), calls.map { it.second })
            calls.forEach { assertNotEquals(caller, it.first) }
        }
    }

    @Test
    fun `selector filters final misses without hiding credited outside points or counting cancellations`() = runBlocking {
        val scored = arrow(1, 0f)
        val manualMiss = arrow(2, .5f).withFinalScore(recurve,
            ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(0), "synthetic miss", now), now)
        val credited = arrow(3, 1.5f).withFinalScore(recurve,
            ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(9), "synthetic judgment", now), now)
        val cancelled = arrow(4, -4f)
        val source = EndGroupingSummarySource(
            end(scored, manualMiss, credited, cancelled).cancelImpact(cancelled.uuid, now).validate(now),
            configuration(),
        )
        val reader = EndGroupingSummaryReader(source)
        val inclusive = reader.read(INCLUDE_MISSES)
        val exclusive = reader.read(EXCLUDE_MISSES)
        assertEquals(19L, inclusive.score)
        assertEquals(inclusive.score, exclusive.score)
        assertEquals(EndStatus.VALIDATED, exclusive.grouping.status)
        assertEquals(3, inclusive.grouping.impactCount)
        assertEquals(1, inclusive.grouping.includedMissCount)
        assertEquals(2, exclusive.grouping.impactCount)
        assertEquals(1, exclusive.grouping.excludedMissCount)
        assertEquals(.75, exclusive.grouping.normalized.center?.x)
        assertEquals(1.5, exclusive.grouping.normalized.dispersion?.diameter)
        assertEquals(listOf(cancelled.uuid), source.end.cancellations.map { it.impactUuid })
    }

    @Test
    fun `empty zero and unmeasurable dispersion stay distinct in the summary`() = runBlocking {
        suspend fun read(end: End, policy: GroupingMissPolicy = INCLUDE_MISSES) =
            EndGroupingSummaryReader(EndGroupingSummarySource(end, configuration())).read(policy)

        val empty = read(end())
        assertNull(empty.score)
        assertNull(empty.grouping.normalized.center)
        assertNull(empty.grouping.normalized.dispersion)

        val onlyMiss = end(arrow(1, 1.5f))
        assertNull(read(onlyMiss).grouping.normalized.dispersion)
        assertEquals(1.5, read(onlyMiss).grouping.normalized.center?.x)
        val filtered = read(onlyMiss, EXCLUDE_MISSES)
        assertEquals(0L, filtered.score)
        assertEquals(0, filtered.grouping.impactCount)
        assertNull(filtered.grouping.normalized.center)

        val two = read(end(arrow(1, 0f), arrow(2, 0f)))
        assertEquals(0.0, two.grouping.normalized.dispersion?.meanRadius)
        assertEquals(0.0, two.grouping.normalized.dispersion?.diameter)
        assertEquals("0", groupingLengthLabel(0.0))
        assertNotEquals("0", groupingLengthLabel(.00001))
    }

    @Test
    fun `physical units use the selected profile and legacy data retain their archived edition`() = runBlocking {
        val configured = EndGroupingSummarySource(end(arrow(1, .5f, compound)), configuration(compound))
        val configuredGrouping = EndGroupingSummaryReader(configured).read(INCLUDE_MISSES).grouping
        assertEquals(15.0, configuredGrouping.measurementsIn(GroupingUnit.CENTIMETERS).center?.x)
        assertEquals(150.0, configuredGrouping.measurementsIn(GroupingUnit.MILLIMETERS).center?.x)

        val target = recurve.copy(uuid = id(), name = "Synthetic legacy face", isBuiltIn = false, diameterCm = 50)
        val legacyEnd = end(arrow(1, .5f, target))
        val session = Session(id(), SessionDateTime.fromInstant(now, ZoneId.of("UTC")), SessionStatus.DRAFT,
            title = "Synthetic", targetType = target, distanceM = 25, arrowCountMode = ArrowCountMode.FIXED,
            arrowCountPerEnd = 3, isIndoor = false, scoringMode = ScoringMode.TRAINING,
            createdAt = now, updatedAt = now).start(now).withEnd(legacyEnd)
        val legacy = legacyGroupingSource(session, TargetProfileSnapshot(target, "Synthetic edition"), legacyEnd)
        val grouping = EndGroupingSummaryReader(legacy).read(INCLUDE_MISSES).grouping
        assertEquals("Synthetic edition", grouping.configuration.regulatoryEdition)
        assertEquals(25, grouping.configuration.distanceM)
        assertEquals(12.5, grouping.measurementsIn(GroupingUnit.CENTIMETERS).center?.x)
    }

    @Test
    fun `a newer recorded revision with the same end identity has a new projection`() = runBlocking {
        val first = end(arrow(1, 0f))
        val second = first.copy(impacts = first.impacts + arrow(2, .5f))
        val old = EndGroupingSummarySource(first, configuration())
        val revised = old.copy(end = second)
        assertEquals(old.end.uuid, revised.end.uuid)
        assertNotEquals(old, revised)
        val prior = EndGroupingSummaryReader(old).read(INCLUDE_MISSES)
        val next = EndGroupingSummaryReader(revised).read(INCLUDE_MISSES)
        assertNull(prior.grouping.normalized.dispersion)
        assertEquals(.5, next.grouping.normalized.dispersion?.diameter)
    }
}

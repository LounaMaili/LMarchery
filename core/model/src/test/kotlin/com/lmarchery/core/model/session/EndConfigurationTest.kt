package com.lmarchery.core.model.session

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.target.TargetType
import com.lmarchery.core.model.target.TargetZone
import com.lmarchery.core.model.time.SessionDateTime
import java.time.Instant
import java.time.ZoneId
import kotlin.test.*

class EndConfigurationTest {
    private val zones = listOf(TargetZone(10, 0f, .1f, "gold"), TargetZone(8, .1f, 1f, "red"))
    private val target = TargetType("90000000-0000-4000-8000-000000000001", "Synthetic full face", "test",
        40, 10, 8, 3, 18, false, false, "", 0f, zones)
    private fun config(
        targetType: TargetType = target, edition: String? = null, distance: Int = 18,
        mode: ArrowCountMode = ArrowCountMode.FIXED, count: Int? = 3, indoor: Boolean = true,
        scoring: ScoringMode = ScoringMode.TRAINING,
    ) = EndConfiguration(targetType, edition, distance, mode, count, indoor, scoring)

    @Test fun `fixed configuration preserves every explicit parameter`() {
        val value = config(edition = "club édition 1", distance = 50, count = 6, indoor = false, scoring = ScoringMode.COMPETITION)
        assertSame(target, value.targetType); assertEquals("club édition 1", value.regulatoryEdition)
        assertEquals(50, value.distanceM); assertEquals(ArrowCountMode.FIXED, value.arrowCountMode)
        assertEquals(6, value.arrowCountPerEnd); assertFalse(value.isIndoor); assertEquals(ScoringMode.COMPETITION, value.scoringMode)
    }

    @Test fun `free mode has no count and distance accepts positive Int endpoints`() {
        for (distance in listOf(1, Int.MAX_VALUE)) {
            val c = config(distance = distance, mode = ArrowCountMode.FREE, count = null)
            assertEquals(distance, c.distanceM); assertNull(c.arrowCountPerEnd)
            assertTrue(validateEndConfiguration(distance, c.arrowCountMode, null, null).isEmpty())
        }
    }

    @Test fun `nonpositive distances are rejected with a typed field issue`() {
        for (distance in listOf(Int.MIN_VALUE, -1, 0)) {
            assertFailsWith<IllegalArgumentException> { config(distance = distance) }
            assertEquals(listOf(EndConfigurationIssue(EndConfigurationField.DISTANCE_M,
                EndConfigurationIssueCode.POSITIVE_DISTANCE_REQUIRED)), validateEndConfiguration(distance, ArrowCountMode.FIXED, 3, null))
        }
    }

    @Test fun `fixed mode requires an explicit count rather than a target default`() {
        assertFailsWith<IllegalArgumentException> { config(count = null) }
        assertEquals(EndConfigurationIssueCode.FIXED_COUNT_REQUIRED,
            validateEndConfiguration(18, ArrowCountMode.FIXED, null, null).single().code)
    }

    @Test fun `fixed count must be strictly positive`() {
        for (count in listOf(Int.MIN_VALUE, -1, 0)) {
            assertFailsWith<IllegalArgumentException> { config(count = count) }
            assertEquals(EndConfigurationIssueCode.POSITIVE_FIXED_COUNT_REQUIRED,
                validateEndConfiguration(18, ArrowCountMode.FIXED, count, null).single().code)
        }
    }

    @Test fun `free mode rejects every supplied count without silently removing it`() {
        for (count in listOf(Int.MIN_VALUE, -1, 0, 1, 3, 6, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { config(mode = ArrowCountMode.FREE, count = count) }
            assertEquals(EndConfigurationIssueCode.FREE_COUNT_MUST_BE_ABSENT,
                validateEndConfiguration(18, ArrowCountMode.FREE, count, null).single().code)
        }
    }

    @Test fun `null and nonblank editions are preserved without parsing trimming or certification`() {
        assertNull(config().regulatoryEdition)
        for (edition in listOf("2026-01-27", " Club 🏹 édition / 2 ", "unknown declared edition"))
            assertEquals(edition, config(edition = edition).regulatoryEdition)
    }

    @Test fun `blank editions are invalid instead of being normalized to missing`() {
        for (edition in listOf("", " ", "\n\t")) {
            assertFailsWith<IllegalArgumentException> { config(edition = edition) }
            assertEquals(EndConfigurationField.REGULATORY_EDITION,
                validateEndConfiguration(18, ArrowCountMode.FIXED, 3, edition).single().field)
        }
    }

    @Test fun `validation returns all scalar issues in deterministic order without changing inputs`() {
        val expected = listOf(
            EndConfigurationIssue(EndConfigurationField.DISTANCE_M, EndConfigurationIssueCode.POSITIVE_DISTANCE_REQUIRED),
            EndConfigurationIssue(EndConfigurationField.ARROW_COUNT_PER_END, EndConfigurationIssueCode.FIXED_COUNT_REQUIRED),
            EndConfigurationIssue(EndConfigurationField.REGULATORY_EDITION, EndConfigurationIssueCode.EDITION_MUST_NOT_BE_BLANK))
        repeat(3) { assertEquals(expected, validateEndConfiguration(0, ArrowCountMode.FIXED, null, " ")) }
    }

    @Test fun `copy validates all scalar invariants and leaves the original intact`() {
        val initial = config(); val before = initial.copy()
        assertFailsWith<IllegalArgumentException> { initial.copy(distanceM = 0) }
        assertFailsWith<IllegalArgumentException> { initial.copy(arrowCountPerEnd = -1) }
        assertFailsWith<IllegalArgumentException> { initial.copy(arrowCountPerEnd = null) }
        assertFailsWith<IllegalArgumentException> { initial.copy(arrowCountMode = ArrowCountMode.FREE) }
        assertFailsWith<IllegalArgumentException> { initial.copy(regulatoryEdition = " ") }
        assertEquals(before, initial)
        assertNull(initial.copy(arrowCountMode = ArrowCountMode.FREE, arrowCountPerEnd = null).arrowCountPerEnd)
    }

    @Test fun `equality and hash use complete content rather than target object identity`() {
        val first = config(); val independent = config(targetType = target.copy())
        assertNotSame(first.targetType, independent.targetType)
        assertEquals(first, independent); assertEquals(first.hashCode(), independent.hashCode())
        assertEquals(1, setOf(first, independent).size); assertTrue(compareEndConfigurations(first, independent).isEmpty())
    }

    @Test fun `comparison identifies each scalar or target change including mode and count together`() {
        val initial = config()
        val changes = listOf(
            initial.copy(targetType = target.copy(uuid = "90000000-0000-4000-8000-000000000002")) to setOf(EndConfigurationField.TARGET_TYPE),
            initial.copy(regulatoryEdition = "edition 2") to setOf(EndConfigurationField.REGULATORY_EDITION),
            initial.copy(distanceM = 70) to setOf(EndConfigurationField.DISTANCE_M),
            initial.copy(arrowCountPerEnd = 5) to setOf(EndConfigurationField.ARROW_COUNT_PER_END),
            initial.copy(arrowCountMode = ArrowCountMode.FREE, arrowCountPerEnd = null) to
                setOf(EndConfigurationField.ARROW_COUNT_MODE, EndConfigurationField.ARROW_COUNT_PER_END),
            initial.copy(isIndoor = false) to setOf(EndConfigurationField.IS_INDOOR),
            initial.copy(scoringMode = ScoringMode.MANUAL_CORDON) to setOf(EndConfigurationField.SCORING_MODE))
        for ((changed, fields) in changes) {
            assertNotEquals(initial, changed)
            assertEquals(fields, compareEndConfigurations(initial, changed))
            assertEquals(fields, compareEndConfigurations(changed, initial))
        }
    }

    @Test fun `comparison reports all fields in declaration order`() {
        val changed = config(target.copy(uuid = "90000000-0000-4000-8000-000000000002"), "edition 2", 50,
            ArrowCountMode.FREE, null, false, ScoringMode.COMPETITION)
        assertEquals(EndConfigurationField.entries.toList(), compareEndConfigurations(config(), changed).toList())
    }

    @Test fun `same profile UUID with different content is not collapsed by comparison`() {
        // Comparison detects divergence; it is not permission to publish a new definition under this UUID.
        val changed = config(targetType = target.copy(zones = listOf(
            TargetZone(10, 0f, .05f, "gold"), TargetZone(8, .05f, 1f, "red"))))
        assertEquals(target.uuid, changed.targetType.uuid)
        assertEquals(setOf(EndConfigurationField.TARGET_TYPE), compareEndConfigurations(config(), changed))
        assertNotEquals(config(), changed)
    }

    @Test fun `identical geometry with distinct profile identities remains distinct`() {
        val other = config(targetType = target.copy(uuid = "90000000-0000-4000-8000-000000000002"))
        assertEquals(target.zones, other.targetType.zones); assertNotEquals(config(), other)
    }

    @Test fun `edition participates in equality even when definition is unchanged`() {
        assertNotEquals(config(edition = "edition 1"), config(edition = "edition 2"))
        assertNotEquals(config(), config(edition = "edition 1"))
    }

    @Test fun `distance indoor scoring and arrow count are independent of target defaults`() {
        for (mode in ScoringMode.entries) for (indoor in listOf(true, false)) {
            val c = config(distance = 70, count = 12, indoor = indoor, scoring = mode)
            assertSame(target, c.targetType); assertEquals(18, target.defaultDistanceM)
            assertEquals(3, target.defaultArrowCount); assertEquals(indoor, c.isIndoor); assertEquals(mode, c.scoringMode)
        }
    }

    @Test fun `external zone list changes cannot alter configuration content or hash`() {
        val external = zones.toMutableList(); val c = config(targetType = target.copy(zones = external))
        val before = c.copy(); val hash = c.hashCode(); external.clear()
        assertEquals(zones, c.targetType.zones); assertEquals(before, c); assertEquals(hash, c.hashCode())
    }

    @Test fun `profile zone view remains unmodifiable through configuration`() {
        val c = config()
        assertFailsWith<UnsupportedOperationException> { (c.targetType.zones as MutableList<TargetZone>).clear() }
        assertEquals(zones, c.targetType.zones)
    }

    @Test fun `shortcuts select three six or free and preserve all other fields`() {
        val original = config(edition = "synthetic edition", distance = 50, count = 12, indoor = false, scoring = ScoringMode.COMPETITION)
        for ((shortcut, count) in listOf(EndArrowCountShortcut.THREE to 3, EndArrowCountShortcut.SIX to 6, EndArrowCountShortcut.FREE to null)) {
            val c = original.withArrowCountShortcut(shortcut)
            assertEquals(original.copy(arrowCountMode = if (count == null) ArrowCountMode.FREE else ArrowCountMode.FIXED,
                arrowCountPerEnd = count), c)
        }
        assertEquals(12, original.arrowCountPerEnd)
    }

    @Test fun `positive custom fixed counts are preserved rather than coerced to shortcuts`() {
        for (count in listOf(1, 2, 4, 5, 7, 12, Int.MAX_VALUE)) {
            val c = config(count = count)
            assertEquals(count, c.arrowCountPerEnd); assertEquals(c, c.copy())
            assertEquals(c, resolveNextEndConfiguration(config(), c))
        }
    }

    @Test fun `first end inherits initial configuration when there is no previous end`() {
        val initial = config(distance = 50, count = 6)
        assertSame(initial, resolveNextEndConfiguration(initial))
        assertSame(initial, resolveNextEndConfiguration(initial, null, NextEndConfigurationChoice.Inherit))
    }

    @Test fun `previous configuration wins over initial defaults for every field`() {
        val initial = config()
        val previous = config(target.copy(uuid = "90000000-0000-4000-8000-000000000002"), "edition 2", 50,
            ArrowCountMode.FREE, null, false, ScoringMode.COMPETITION)
        assertSame(previous, resolveNextEndConfiguration(initial, previous))
        assertSame(previous, resolveNextEndConfiguration(initial, previous, NextEndConfigurationChoice.Inherit))
    }

    @Test fun `explicit replacement wins even on the first end`() {
        val initial = config(); val selected = config(count = 6)
        assertSame(selected, resolveNextEndConfiguration(initial, choice = NextEndConfigurationChoice.Replace(selected)))
    }

    @Test fun `explicit replacement wins over a preceding end without changing either source`() {
        val initial = config(); val previous = config(distance = 30, count = 6); val selected = config(distance = 70, count = 12)
        val snapshots = listOf(initial.copy(), previous.copy(), selected.copy())
        assertSame(selected, resolveNextEndConfiguration(initial, previous, NextEndConfigurationChoice.Replace(selected)))
        assertEquals(snapshots, listOf(initial, previous, selected))
    }

    @Test fun `explicit change becomes the following inheritance until another deliberate change`() {
        val initial = config(); val custom = config(distance = 50, mode = ArrowCountMode.FREE, count = null)
        val changed = resolveNextEndConfiguration(initial, initial, NextEndConfigurationChoice.Replace(custom))
        val following = resolveNextEndConfiguration(initial, changed)
        assertEquals(custom, following)
        val backToInitial = resolveNextEndConfiguration(initial, following, NextEndConfigurationChoice.Replace(initial))
        assertSame(initial, backToInitial); assertSame(initial, resolveNextEndConfiguration(initial, backToInitial))
    }

    @Test fun `equal explicit choice is not silently turned into inheritance`() {
        val previous = config(); val chosen = previous.copy(); val explicit = NextEndConfigurationChoice.Replace(chosen)
        assertEquals(previous, chosen); assertNotSame(previous, chosen)
        assertSame(chosen, resolveNextEndConfiguration(config(distance = 70), previous, explicit))
        assertNotEquals<NextEndConfigurationChoice>(NextEndConfigurationChoice.Inherit, explicit)
    }

    @Test fun `abandoned candidate has no effect on inheritance`() {
        val initial = config(); val previous = config(count = 6)
        val unconfirmedCandidate = previous.copy(distanceM = 70)
        assertSame(previous, resolveNextEndConfiguration(initial, previous))
        assertNotEquals(unconfirmedCandidate, resolveNextEndConfiguration(initial, previous))
        assertEquals(18, previous.distanceM)
    }

    @Test fun `resolving repeatedly is deterministic and does not accumulate changes`() {
        val initial = config(); val previous = config(count = 5, distance = 30)
        val chosen = config(mode = ArrowCountMode.FREE, count = null)
        repeat(100) {
            assertSame(previous, resolveNextEndConfiguration(initial, previous))
            assertSame(chosen, resolveNextEndConfiguration(initial, previous, NextEndConfigurationChoice.Replace(chosen)))
        }
    }

    @Test fun `mixed sequence retains every previously selected immutable value`() {
        val initial = config(); val history = mutableListOf<EndConfiguration>()
        repeat(30) { i ->
            val before = history.map { it.copy() }
            val explicit = if (i % 3 == 0) NextEndConfigurationChoice.Replace(config(distance = i + 1, count = i + 1))
                else NextEndConfigurationChoice.Inherit
            val next = resolveNextEndConfiguration(initial, history.lastOrNull(), explicit)
            assertEquals(before, history); history += next
        }
        assertEquals(1, history.first().distanceM); assertEquals(1, history.first().arrowCountPerEnd)
        assertEquals(config(), initial)
    }

    @Test fun `planning does not change a historical validated end or completed session`() {
        val now = Instant.parse("2026-10-20T10:00:00Z")
        val impact = ArrowImpact("90000000-0000-4000-8000-000000000003", 1, NormalizedPoint(0f, 0f),
            inputMode = InputMode.MANUAL, createdAt = now, updatedAt = now, targetType = target)
        val end = End("90000000-0000-4000-8000-000000000004", 1, EndStatus.EDITING,
            listOf(impact), createdAt = now, updatedAt = now).validate(now)
        val session = Session("90000000-0000-4000-8000-000000000005", SessionDateTime.fromInstant(now, ZoneId.of("UTC")),
            SessionStatus.COMPLETED, now, now, title = "Synthetic closed session", targetType = target, distanceM = 18,
            arrowCountMode = ArrowCountMode.FIXED, arrowCountPerEnd = 3, isIndoor = true, scoringMode = ScoringMode.TRAINING,
            ends = listOf(end), createdAt = now, updatedAt = now)
        val before = session.copy()
        val chosen = config(distance = 70, count = 6, indoor = false)
        assertEquals(chosen, resolveNextEndConfiguration(config(), config(), NextEndConfigurationChoice.Replace(chosen)))
        assertEquals(before, session); assertEquals(listOf(end), session.ends); assertEquals(listOf(impact), end.impacts)
        assertFailsWith<IllegalArgumentException> { end.withImpact(impact) }
        assertFailsWith<IllegalArgumentException> { session.withEnd(end) }
    }

    @Test fun `new configuration does not bypass the historical single profile session invariant`() {
        val now = Instant.parse("2026-10-20T10:00:00Z")
        val original = Session("90000000-0000-4000-8000-000000000005", SessionDateTime.fromInstant(now, ZoneId.of("UTC")),
            SessionStatus.IN_PROGRESS, startedAt = now, title = "Synthetic legacy session", targetType = target, distanceM = 18,
            arrowCountMode = ArrowCountMode.FIXED, arrowCountPerEnd = 3, isIndoor = true, scoringMode = ScoringMode.TRAINING,
            createdAt = now, updatedAt = now)
        val other = config(targetType = target.copy(uuid = "90000000-0000-4000-8000-000000000002"), count = 6)
        val planned = resolveNextEndConfiguration(config(), choice = NextEndConfigurationChoice.Replace(other))
        val impact = ArrowImpact("90000000-0000-4000-8000-000000000003", 1, NormalizedPoint(0f, 0f),
            inputMode = InputMode.MANUAL, createdAt = now, updatedAt = now, targetType = planned.targetType)
        val end = End("90000000-0000-4000-8000-000000000004", 1, EndStatus.EDITING,
            listOf(impact), createdAt = now, updatedAt = now)
        assertFailsWith<IllegalArgumentException> { original.withEnd(end) }
        assertTrue(original.ends.isEmpty()); assertEquals(target, original.targetType)
    }
}

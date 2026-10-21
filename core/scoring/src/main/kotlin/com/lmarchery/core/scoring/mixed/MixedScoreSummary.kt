package com.lmarchery.core.scoring.mixed

import com.lmarchery.core.model.session.EndConfiguration
import java.util.Collections

/** Relation only, not permission to validate an empty, underfilled or overfilled FIXED end. */
enum class EndArrowCountRelation { UNBOUNDED, BELOW_FIXED, AT_FIXED, ABOVE_FIXED }
enum class ConfigurationHomogeneity { EMPTY, HOMOGENEOUS, HETEROGENEOUS }

/** Exact Long numerators/denominators; ratios are approximate Double views of the same scope. */
class ScoringTotals internal constructor(
    val endCount: Long,
    val impactCount: Long,
    val score: Long,
    val recordedCapacityMaximum: Long,
    val recordedArrowMaximum: Long,
    val maxScoreCount: Long,
    val xCount: Long,
    val missCount: Long,
    val nineCount: Long,
) {
    init {
        require(listOf(endCount, impactCount, score, recordedCapacityMaximum, recordedArrowMaximum,
            maxScoreCount, xCount, missCount, nineCount).all { it >= 0 })
        require(score <= recordedArrowMaximum)
        require(listOf(maxScoreCount, xCount, missCount, nineCount).all { it <= impactCount })
    }
    private val snapshot = Snapshot(endCount, impactCount, score, recordedCapacityMaximum, recordedArrowMaximum,
        maxScoreCount, xCount, missCount, nineCount)
    override fun equals(other: Any?) = other is ScoringTotals && snapshot == other.snapshot
    override fun hashCode() = snapshot.hashCode()
    override fun toString() = snapshot.toString()
    private data class Snapshot(val endCount: Long, val impactCount: Long, val score: Long,
        val recordedCapacityMaximum: Long, val recordedArrowMaximum: Long, val maxScoreCount: Long,
        val xCount: Long, val missCount: Long, val nineCount: Long)

    val percentageOfRecordedCapacity get() = ratio(score, recordedCapacityMaximum, 100.0)
    val percentageOfRecordedArrows get() = ratio(score, recordedArrowMaximum, 100.0)
    val averagePerEnd get() = ratio(score, endCount)
    val averagePerArrow get() = ratio(score, impactCount)
}

/** Reads the original immutable end, including its unmodified decisions and reposition evidence. */
class ConfiguredEndScore internal constructor(
    val context: EndScoringContext,
    val arrowCountRelation: EndArrowCountRelation,
    val totals: ScoringTotals,
) {
    override fun equals(other: Any?) = other is ConfiguredEndScore && context == other.context && arrowCountRelation == other.arrowCountRelation && totals == other.totals
    override fun hashCode() = listOf(context, arrowCountRelation, totals).hashCode()
}

class ScoringBreakdown internal constructor(
    val validated: ScoringTotals,
    val provisional: ScoringTotals,
    val recorded: ScoringTotals,
) {
    override fun equals(other: Any?) = other is ScoringBreakdown && validated == other.validated && provisional == other.provisional && recorded == other.recorded
    override fun hashCode() = listOf(validated, provisional, recorded).hashCode()
}

class ConfigurationScoreGroup internal constructor(
    val configuration: EndConfiguration,
    ends: List<ConfiguredEndScore>,
    val totals: ScoringBreakdown,
) {
    val ends: List<ConfiguredEndScore> = Collections.unmodifiableList(ends.toList())
    override fun equals(other: Any?) = other is ConfigurationScoreGroup && configuration == other.configuration && ends == other.ends && totals == other.totals
    override fun hashCode() = listOf(configuration, ends, totals).hashCode()
}

/** No planned maximum or assertion of regulatory comparability, even when homogeneous. */
class MixedScoreSummary internal constructor(ends: List<ConfiguredEndScore>, groups: List<ConfigurationScoreGroup>, val totals: ScoringBreakdown) {
    val ends: List<ConfiguredEndScore> = Collections.unmodifiableList(ends.toList())
    val groups: List<ConfigurationScoreGroup> = Collections.unmodifiableList(groups.toList())
    val homogeneity: ConfigurationHomogeneity get() = when (groups.size) {
        0 -> ConfigurationHomogeneity.EMPTY
        1 -> ConfigurationHomogeneity.HOMOGENEOUS
        else -> ConfigurationHomogeneity.HETEROGENEOUS
    }
    override fun equals(other: Any?) = other is MixedScoreSummary && ends == other.ends && groups == other.groups && totals == other.totals
    override fun hashCode() = listOf(ends, groups, totals).hashCode()
}

private fun ratio(numerator: Long, denominator: Long, scale: Double = 1.0): Double? {
    if (denominator == 0L) return null
    val value = numerator.toDouble() * scale / denominator.toDouble()
    if (!value.isFinite()) fail(MixedScoringIssue.NUMERIC_OVERFLOW, "ratio", "Non-finite result is not representable.")
    return value
}

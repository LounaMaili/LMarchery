package com.lmarchery.core.scoring.mixed

import com.lmarchery.core.model.session.ArrowCountMode
import com.lmarchery.core.model.session.EndStatus

/** FIXED uses the declared count for this recorded end; FREE uses only impacts actually present. */
fun calculateRecordedMaximum(context: EndScoringContext): Long {
    val configuration = context.configuration
    val count = if (configuration.arrowCountMode == ArrowCountMode.FIXED) configuration.arrowCountPerEnd!!.toLong()
        else context.end.impacts.size.toLong()
    return multiply(count, configuration.targetType.maxScore.toLong(), "ends[${context.end.uuid}].recordedCapacityMaximum")
}

/** Sums existing final values only; never calls geometric scoring, resolution or a domain transition. */
fun calculateConfiguredEndScore(context: EndScoringContext): ConfiguredEndScore {
    validateEndScoringContext(context.end, context.binding)
    val impacts = context.end.impacts
    val configuration = context.configuration
    val path = "ends[${context.end.uuid}]"
    val score = impacts.fold(0L) { total, impact -> add(total, impact.scoreFinal.toLong(), "$path.score") }
    val arrowMaximum = multiply(impacts.size.toLong(), configuration.targetType.maxScore.toLong(), "$path.recordedArrowMaximum")
    val relation = if (configuration.arrowCountMode == ArrowCountMode.FREE) EndArrowCountRelation.UNBOUNDED else when {
        impacts.size < configuration.arrowCountPerEnd!! -> EndArrowCountRelation.BELOW_FIXED
        impacts.size == configuration.arrowCountPerEnd -> EndArrowCountRelation.AT_FIXED
        else -> EndArrowCountRelation.ABOVE_FIXED
    }
    return ConfiguredEndScore(context, relation, ScoringTotals(1L, impacts.size.toLong(), score,
        calculateRecordedMaximum(context), arrowMaximum,
        impacts.count { it.scoreFinal == it.targetType.maxScore }.toLong(),
        impacts.count { it.isX }.toLong(), impacts.count { it.isMiss }.toLong(), impacts.count { it.scoreFinal == 9 }.toLong()))
}

/** Deterministic end order (index, UUID); groups follow the first end in that canonical order. */
fun summarizeConfiguredEnds(contexts: List<EndScoringContext>): MixedScoreSummary {
    val owned = contexts.toList().sortedWith(compareBy<EndScoringContext> { it.end.index }.thenBy { it.end.uuid })
    validateContextCollection(owned)
    val results = owned.map(::calculateConfiguredEndScore)
    val groups = results.groupBy { it.context.configuration }.map { (configuration, ends) ->
        ConfigurationScoreGroup(configuration, ends, breakdown(ends))
    }
    return MixedScoreSummary(results, groups, breakdown(results))
}

private fun breakdown(ends: List<ConfiguredEndScore>) = ScoringBreakdown(
    sum(ends.filter { it.context.end.status == EndStatus.VALIDATED }, "validated"),
    sum(ends.filter { it.context.end.status == EndStatus.EDITING }, "provisional"),
    sum(ends, "recorded"),
)

private fun sum(ends: List<ConfiguredEndScore>, path: String): ScoringTotals = ends.fold(ScoringTotals(0, 0, 0, 0, 0, 0, 0, 0, 0)) { a, result ->
    val b = result.totals
    ScoringTotals(add(a.endCount, b.endCount, "$path.endCount"), add(a.impactCount, b.impactCount, "$path.impactCount"),
        add(a.score, b.score, "$path.score"), add(a.recordedCapacityMaximum, b.recordedCapacityMaximum, "$path.recordedCapacityMaximum"),
        add(a.recordedArrowMaximum, b.recordedArrowMaximum, "$path.recordedArrowMaximum"), add(a.maxScoreCount, b.maxScoreCount, "$path.maxScoreCount"),
        add(a.xCount, b.xCount, "$path.xCount"), add(a.missCount, b.missCount, "$path.missCount"), add(a.nineCount, b.nineCount, "$path.nineCount"))
}

private fun add(a: Long, b: Long, path: String) = checked(path) { Math.addExact(a, b) }
private fun multiply(a: Long, b: Long, path: String) = checked(path) { Math.multiplyExact(a, b) }
private inline fun checked(path: String, operation: () -> Long): Long = try { operation() }
catch (_: ArithmeticException) { fail(MixedScoringIssue.NUMERIC_OVERFLOW, path, "Exact Long result cannot be represented; no clamping.") }

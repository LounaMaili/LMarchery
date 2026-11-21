package com.lmarchery.feature.sessions

import com.lmarchery.core.model.session.End
import com.lmarchery.core.model.session.EndConfiguration
import com.lmarchery.core.model.session.Session
import com.lmarchery.core.scoring.archive.TargetProfileSnapshot
import com.lmarchery.core.scoring.grouping.EndGrouping
import com.lmarchery.core.scoring.grouping.GroupingMissPolicy
import com.lmarchery.core.scoring.grouping.calculateEndGrouping
import com.lmarchery.core.scoring.mixed.EndConfigurationBinding
import com.lmarchery.core.scoring.mixed.EndScoringContext
import com.lmarchery.core.scoring.mixed.calculateConfiguredEndScore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

internal data class EndGroupingSummarySource(val end: End, val configuration: EndConfiguration)

internal fun legacyGroupingSource(session: Session, profile: TargetProfileSnapshot, end: End): EndGroupingSummarySource {
    require(profile.definition == session.targetType)
    return EndGroupingSummarySource(
        end,
        EndConfiguration(session.targetType, profile.regulatoryEdition, session.distanceM, session.arrowCountMode,
            session.arrowCountPerEnd, session.isIndoor, session.scoringMode),
    )
}

internal data class EndGroupingSummary(val grouping: EndGrouping, val score: Long?)

/** Owned by the open view for one selected end. Lookups and cache writes run on the caller's UI context. */
internal class EndGroupingSummaryReader(
    private val source: EndGroupingSummarySource,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val calculate: (EndScoringContext, GroupingMissPolicy) -> EndGrouping = ::calculateEndGrouping,
) {
    private val results = mutableMapOf<GroupingMissPolicy, EndGroupingSummary>()

    suspend fun read(policy: GroupingMissPolicy): EndGroupingSummary {
        results[policy]?.let { return it }
        val previous = results.values.firstOrNull()
        val summary = withContext(worker) {
            val context = EndScoringContext(source.end, EndConfigurationBinding(source.end.uuid, source.configuration))
            val grouping = calculate(context, policy)
            val score = when {
                previous != null -> previous.score
                source.end.activeImpacts.isEmpty() -> null
                else -> calculateConfiguredEndScore(context).totals.score
            }
            EndGroupingSummary(grouping, score)
        }
        results[policy] = summary
        return summary
    }
}

/** French decimal separator, with scientific notation when rounding would hide a nonzero value. */
internal fun groupingLengthLabel(value: Double): String = when {
    value == 0.0 -> "0"
    abs(value) < .001 || abs(value) >= 1_000_000 -> String.format(Locale.FRANCE, "%.3g", value)
    else -> String.format(Locale.FRANCE, "%.3f", value).trimEnd('0').trimEnd(',')
}

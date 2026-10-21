package com.lmarchery.core.scoring.configured

import com.lmarchery.core.scoring.mixed.EndConfigurationBinding
import com.lmarchery.core.scoring.mixed.EndScoringContext
import com.lmarchery.core.scoring.mixed.MixedScoreSummary
import com.lmarchery.core.scoring.mixed.bindEndConfigurations
import com.lmarchery.core.scoring.mixed.summarizeConfiguredEnds

/** Reject a foreign owner or a stale/different capture; never substitute the initial profile. */
fun configuredEndScoringContext(session: ConfiguredSession, end: ConfiguredEnd): EndScoringContext {
    requireConfigured(end.sessionUuid == session.uuid && session.ends.singleOrNull { it.uuid == end.uuid } == end,
        ConfiguredSessionIssue.WRONG_OWNER, "ends[${end.uuid}]", "The exact capture must belong to this session revision.")
    return EndScoringContext(end.end, EndConfigurationBinding(end.uuid, end.configuration))
}

fun configuredSessionScoringContexts(session: ConfiguredSession): List<EndScoringContext> {
    validateConfiguredSession(session)
    return bindEndConfigurations(session.ends.map { it.end }, session.ends.map { EndConfigurationBinding(it.uuid, it.configuration) })
}

/** Identical PR #34 calculation, using only stored final scores and effective per-end configurations. */
fun summarizeConfiguredSession(session: ConfiguredSession): MixedScoreSummary =
    summarizeConfiguredEnds(configuredSessionScoringContexts(session))

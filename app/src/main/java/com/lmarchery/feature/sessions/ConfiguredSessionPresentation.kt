package com.lmarchery.feature.sessions

import com.lmarchery.core.model.session.*
import com.lmarchery.core.scoring.configured.*
import com.lmarchery.core.scoring.mixed.*
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ConfiguredScorePresentation(val summary: MixedScoreSummary?, val diagnostic: String?)

/** Only reads stored final values. Numeric failure is visible, never a fabricated zero. */
fun presentConfiguredScore(session: ConfiguredSession): ConfiguredScorePresentation = try {
    ConfiguredScorePresentation(summarizeConfiguredSession(session), null)
} catch (error: MixedScoringException) {
    if (error.code != MixedScoringIssue.NUMERIC_OVERFLOW) throw error
    ConfiguredScorePresentation(null, error.message)
}

fun ConfiguredSession.civilDateLabel(): String = metadata.date.let {
    "${it.localDateTime.format(DateTimeFormatter.ofPattern("d MMM uuuu 'à' HH:mm", Locale.FRANCE))} · ${it.zoneId.id} (${it.offset})"
}

fun configurationLines(configuration: EndConfiguration): List<String> = listOf(
    "${configuration.targetType.name} · ${configuration.targetType.diameterCm} cm · ${configuration.distanceM} m",
    "Profil : ${configuration.targetType.uuid} · édition : ${configuration.regulatoryEdition ?: "non déclarée"}",
    (if (configuration.arrowCountMode == ArrowCountMode.FREE) "FREE · nombre libre" else "FIXED · ${configuration.arrowCountPerEnd} flèches") +
        " · ${if (configuration.isIndoor) "indoor" else "outdoor"} · ${configuration.scoringMode}",
)

fun EndConfigurationOrigin.label(): String = when (this) {
    EndConfigurationOrigin.Initial -> "INITIAL · configuration initiale"
    is EndConfigurationOrigin.Inherited -> "INHERITED · héritée de $previousEndUuid"
    EndConfigurationOrigin.Explicit -> "EXPLICIT · choix explicite conservé"
    EndConfigurationOrigin.LegacyDerived -> "LEGACY_DERIVED · dérivée des données historiques"
}

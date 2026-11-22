package com.lmarchery.feature.sessions

import com.lmarchery.core.model.session.*
import com.lmarchery.core.scoring.configured.*
import com.lmarchery.core.scoring.mixed.*

data class ConfiguredScorePresentation(val summary: MixedScoreSummary?, val diagnostic: String?)

/** Only reads stored final values. Numeric failure is visible, never a fabricated zero. */
fun presentConfiguredScore(session: ConfiguredSession): ConfiguredScorePresentation = try {
    ConfiguredScorePresentation(summarizeConfiguredSession(session), null)
} catch (error: MixedScoringException) {
    if (error.code != MixedScoringIssue.NUMERIC_OVERFLOW) throw error
    ConfiguredScorePresentation(null, error.message)
}

fun ConfiguredSession.civilDateLabel(): String = metadata.date.let {
    "${it.shortLabel()} · ${it.zoneId.id} (${it.offset})"
}

/** Display the effective settings. The initial setup describes the session only while there are no ends. */
internal fun ConfiguredSession.effectiveConfigurations(): List<EndConfiguration> =
    ends.map { it.configuration }.distinct().ifEmpty { listOf(initialConfiguration) }

internal fun ConfiguredSession.configurationLabel(): String {
    val configurations = effectiveConfigurations()
    return if (configurations.size == 1) configurations.single().shootingLabel()
    else "${configurations.size} configurations de tir · réglages affichés par volée"
}

internal fun EndConfiguration.shootingLabel(): String =
    "${distanceM} m · ${targetType.name} · ${if (isIndoor) "Salle" else "Extérieur"} · ${scoringMode.categoryLabel()}"

internal fun EndConfiguration.capacityLabel(): String = when (arrowCountMode) {
    ArrowCountMode.FIXED -> "${arrowCountPerEnd} ${if (arrowCountPerEnd == 1) "flèche" else "flèches"} par volée"
    ArrowCountMode.FREE -> "Nombre libre de flèches"
}

internal fun EndConfiguration.profileDetails(): String =
    "Profil ${targetType.uuid} · diamètre nominal ${targetType.diameterCm} cm · édition ${regulatoryEdition ?: "non renseignée"}"

internal fun scoreCountLabels(maximum: Int, maximumCount: Long, nineCount: Long, showX: Boolean,
    xCount: Long, missCount: Long): List<String> = buildList {
    add("Nombre de $maximum : $maximumCount")
    if (maximum != 9 && nineCount > 0) add("Nombre de 9 : $nineCount")
    if (showX) add("Flèches dans le X central : $xCount")
    add("Flèches notées M (0 point) : $missCount")
}

fun EndConfigurationOrigin.label(): String = when (this) {
    EndConfigurationOrigin.Initial -> "INITIAL · configuration initiale"
    is EndConfigurationOrigin.Inherited -> "INHERITED · héritée de $previousEndUuid"
    EndConfigurationOrigin.Explicit -> "EXPLICIT · choix explicite conservé"
    EndConfigurationOrigin.LegacyDerived -> "LEGACY_DERIVED · dérivée des données historiques"
}

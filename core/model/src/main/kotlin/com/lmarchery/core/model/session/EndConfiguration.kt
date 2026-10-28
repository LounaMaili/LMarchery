package com.lmarchery.core.model.session

import com.lmarchery.core.model.target.TargetType

/**
 * Standalone, immutable parameters for a future end. Not attached to Session/End or persisted yet.
 *
 * The complete target and its declared edition are preserved, never inferred from diameter/distance.
 * This is a custom shooting configuration, not a claim that a competition format is official.
 * TargetType already validates/owns its zones. Published profile identity/edition must still be
 * checked against a known registry at the future reconstruction boundary (not available in model).
 */
data class EndConfiguration(
    val targetType: TargetType,
    val regulatoryEdition: String?,
    val distanceM: Int,
    val arrowCountMode: ArrowCountMode,
    val arrowCountPerEnd: Int?,
    val isIndoor: Boolean,
    val scoringMode: ScoringMode,
) {
    init {
        val issues = validateEndConfiguration(distanceM, arrowCountMode, arrowCountPerEnd, regulatoryEdition)
        require(issues.isEmpty()) { "Invalid EndConfiguration: ${issues.joinToString()}" }
    }

    /** Convenience only: arbitrary positive fixed counts remain valid through construction/copy. */
    fun withArrowCountShortcut(shortcut: EndArrowCountShortcut): EndConfiguration = copy(
        arrowCountMode = shortcut.arrowCountMode,
        arrowCountPerEnd = shortcut.arrowCountPerEnd,
    )
}

enum class EndArrowCountShortcut(val arrowCountMode: ArrowCountMode, val arrowCountPerEnd: Int?) {
    THREE(ArrowCountMode.FIXED, 3),
    SIX(ArrowCountMode.FIXED, 6),
    FREE(ArrowCountMode.FREE, null),
}

enum class EndConfigurationField {
    TARGET_TYPE, REGULATORY_EDITION, DISTANCE_M, ARROW_COUNT_MODE, ARROW_COUNT_PER_END, IS_INDOOR, SCORING_MODE,
}

enum class EndConfigurationIssueCode {
    POSITIVE_DISTANCE_REQUIRED, FIXED_COUNT_REQUIRED, POSITIVE_FIXED_COUNT_REQUIRED,
    FREE_COUNT_MUST_BE_ABSENT, EDITION_MUST_NOT_BE_BLANK,
}

data class EndConfigurationIssue(val field: EndConfigurationField, val code: EndConfigurationIssueCode)

/**
 * Non-throwing scalar validation for future form adapters, also used by the constructor/copy.
 * TargetType and enum arguments are already typed/validated. This checks structural consistency,
 * not a published registry, legal shooting distances, or the authenticity of a regulatory edition.
 * Issues have a deterministic field order; no trimming, coercion or default is applied.
 */
fun validateEndConfiguration(
    distanceM: Int,
    arrowCountMode: ArrowCountMode,
    arrowCountPerEnd: Int?,
    regulatoryEdition: String?,
): List<EndConfigurationIssue> = buildList {
    if (distanceM <= 0) add(EndConfigurationIssue(
        EndConfigurationField.DISTANCE_M, EndConfigurationIssueCode.POSITIVE_DISTANCE_REQUIRED))
    when (arrowCountMode) {
        ArrowCountMode.FIXED -> when {
            arrowCountPerEnd == null -> add(EndConfigurationIssue(
                EndConfigurationField.ARROW_COUNT_PER_END, EndConfigurationIssueCode.FIXED_COUNT_REQUIRED))
            arrowCountPerEnd <= 0 -> add(EndConfigurationIssue(
                EndConfigurationField.ARROW_COUNT_PER_END, EndConfigurationIssueCode.POSITIVE_FIXED_COUNT_REQUIRED))
        }
        ArrowCountMode.FREE -> if (arrowCountPerEnd != null) add(EndConfigurationIssue(
            EndConfigurationField.ARROW_COUNT_PER_END, EndConfigurationIssueCode.FREE_COUNT_MUST_BE_ABSENT))
    }
    if (regulatoryEdition != null && regulatoryEdition.isBlank()) add(EndConfigurationIssue(
        EndConfigurationField.REGULATORY_EDITION, EndConfigurationIssueCode.EDITION_MUST_NOT_BE_BLANK))
}

/** Full content comparison, including profile identity, definition and edition; not an update authorization. */
fun compareEndConfigurations(previous: EndConfiguration, proposed: EndConfiguration): Set<EndConfigurationField> = buildSet {
    if (previous.targetType != proposed.targetType) add(EndConfigurationField.TARGET_TYPE)
    if (previous.regulatoryEdition != proposed.regulatoryEdition) add(EndConfigurationField.REGULATORY_EDITION)
    if (previous.distanceM != proposed.distanceM) add(EndConfigurationField.DISTANCE_M)
    if (previous.arrowCountMode != proposed.arrowCountMode) add(EndConfigurationField.ARROW_COUNT_MODE)
    if (previous.arrowCountPerEnd != proposed.arrowCountPerEnd) add(EndConfigurationField.ARROW_COUNT_PER_END)
    if (previous.isIndoor != proposed.isIndoor) add(EndConfigurationField.IS_INDOOR)
    if (previous.scoringMode != proposed.scoringMode) add(EndConfigurationField.SCORING_MODE)
}

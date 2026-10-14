package com.lmarchery.core.scoring

enum class BoundaryType {
    SCORE_ZONES,
    X_RING,
    TARGET_EDGE,
}

/** A value on one side of a boundary. X is a distinction, never an extra point. */
data class BoundaryValue(
    val score: Int,
    val isX: Boolean = false,
) {
    val isMiss: Boolean get() = score == 0

    init {
        require(score >= 0) { "BoundaryValue score must be >= 0." }
        require(!isX || score > 0) { "BoundaryValue miss cannot be X." }
    }
}

/** Geometric proximity only; neither evidence of shaft contact nor a final-score decision. */
data class BoundaryHint(
    val type: BoundaryType,
    val radiusNormalized: Float,
    val inner: BoundaryValue,
    val outer: BoundaryValue,
    val distanceToBoundaryNormalized: Double,
) {
    init {
        require(radiusNormalized.isFinite() && radiusNormalized > 0f && radiusNormalized <= 1f) {
            "BoundaryHint radius must be finite and in (0, 1]."
        }
        require(distanceToBoundaryNormalized.isFinite() && distanceToBoundaryNormalized >= 0.0) {
            "BoundaryHint distance must be finite and >= 0."
        }
    }
}

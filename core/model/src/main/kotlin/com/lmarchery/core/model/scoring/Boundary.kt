package com.lmarchery.core.model.scoring

enum class BoundaryType { SCORE_ZONES, X_RING, TARGET_EDGE }

data class BoundaryValue(val score: Int, val isX: Boolean = false) {
    val isMiss: Boolean get() = score == 0

    init {
        require(score >= 0) { "BoundaryValue score must be >= 0." }
        require(!isX || score > 0) { "BoundaryValue miss cannot be X." }
    }
}

data class BoundaryReference(val type: BoundaryType, val radiusNormalized: Float) {
    init {
        require(radiusNormalized.isFinite() && radiusNormalized > 0f && radiusNormalized <= 1f) {
            "Boundary radius must be finite and in (0, 1]."
        }
    }
}

data class BoundaryDefinition(
    val reference: BoundaryReference,
    val inner: BoundaryValue,
    val outer: BoundaryValue,
)

/** Geometric proximity only; neither shaft contact nor a final-score decision. */
data class BoundaryHint(
    val type: BoundaryType,
    val radiusNormalized: Float,
    val inner: BoundaryValue,
    val outer: BoundaryValue,
    val distanceToBoundaryNormalized: Double,
) {
    val reference: BoundaryReference get() = BoundaryReference(type, radiusNormalized)

    init {
        require(radiusNormalized.isFinite() && radiusNormalized > 0f && radiusNormalized <= 1f) {
            "BoundaryHint radius must be finite and in (0, 1]."
        }
        require(distanceToBoundaryNormalized.isFinite() && distanceToBoundaryNormalized >= 0.0) {
            "BoundaryHint distance must be finite and >= 0."
        }
    }
}

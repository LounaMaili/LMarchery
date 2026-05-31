package com.lmarchery.core.model.target

data class TargetZone(
    val score: Int,
    val innerRadiusNormalized: Float,
    val outerRadiusNormalized: Float,
    val colorName: String,
    val colorHex: String? = null,
) {
    init {
        require(score >= 0) { "TargetZone score must be >= 0." }
        require(innerRadiusNormalized >= 0f) { "TargetZone inner radius must be >= 0." }
        require(outerRadiusNormalized > innerRadiusNormalized) {
            "TargetZone outer radius must be greater than inner radius."
        }
    }
}

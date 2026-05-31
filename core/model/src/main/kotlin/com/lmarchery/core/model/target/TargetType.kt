package com.lmarchery.core.model.target

data class TargetType(
    val uuid: String,
    val name: String,
    val discipline: String,
    val diameterCm: Int,
    val maxScore: Int,
    val minScore: Int,
    val defaultArrowCount: Int,
    val defaultDistanceM: Int,
    val isBuiltIn: Boolean,
    val xRingEnabled: Boolean,
    val xRingLabel: String,
    val innerTenRadiusNormalized: Float,
    val zones: List<TargetZone>,
) {
    init {
        require(uuid.isNotBlank()) { "TargetType uuid must not be blank." }
        require(name.isNotBlank()) { "TargetType name must not be blank." }
        require(diameterCm > 0) { "TargetType diameter must be positive." }
        require(maxScore >= minScore) { "TargetType maxScore must be >= minScore." }
        require(defaultArrowCount > 0) { "TargetType defaultArrowCount must be positive." }
        require(defaultDistanceM > 0) { "TargetType defaultDistanceM must be positive." }
        require(innerTenRadiusNormalized >= 0f) { "TargetType innerTenRadiusNormalized must be >= 0." }
        require(zones.isNotEmpty()) { "TargetType must define at least one zone." }
    }
}

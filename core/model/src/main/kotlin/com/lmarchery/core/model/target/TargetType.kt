package com.lmarchery.core.model.target

import java.util.Collections

class TargetType(
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
    zones: List<TargetZone>,
) {
    val zones: List<TargetZone> = Collections.unmodifiableList(zones.toList())

    private val snapshot = Snapshot(
        uuid = this.uuid,
        name = this.name,
        discipline = this.discipline,
        diameterCm = this.diameterCm,
        maxScore = this.maxScore,
        minScore = this.minScore,
        defaultArrowCount = this.defaultArrowCount,
        defaultDistanceM = this.defaultDistanceM,
        isBuiltIn = this.isBuiltIn,
        xRingEnabled = this.xRingEnabled,
        xRingLabel = this.xRingLabel,
        innerTenRadiusNormalized = this.innerTenRadiusNormalized,
        zones = this.zones,
    )

    init {
        require(uuid.isNotBlank()) { "TargetType uuid must not be blank." }
        require(name.isNotBlank()) { "TargetType name must not be blank." }
        require(diameterCm > 0) { "TargetType diameter must be positive." }
        require(minScore >= 0) { "TargetType minScore must be >= 0." }
        require(maxScore >= minScore) { "TargetType maxScore must be >= minScore." }
        require(defaultArrowCount > 0) { "TargetType defaultArrowCount must be positive." }
        require(defaultDistanceM > 0) { "TargetType defaultDistanceM must be positive." }
        require(innerTenRadiusNormalized.isFinite()) { "TargetType innerTenRadiusNormalized must be finite." }
        require(innerTenRadiusNormalized >= 0f) { "TargetType innerTenRadiusNormalized must be >= 0." }
        require(this.zones.isNotEmpty()) { "TargetType must define at least one zone." }

        val zonesFromCenter = this.zones.sortedBy { it.outerRadiusNormalized }
        require(zonesFromCenter.first().innerRadiusNormalized == 0f) {
            "TargetType zones must cover the center (inner radius 0)."
        }
        require(zonesFromCenter.last().outerRadiusNormalized == 1f) {
            "TargetType zones must cover the full target through outer radius 1."
        }
        require(zonesFromCenter.all { it.score in minScore..maxScore }) {
            "TargetType zone scores must be within minScore..maxScore."
        }
        require(zonesFromCenter.first().score == maxScore) {
            "TargetType central zone must have maxScore."
        }
        for ((inner, outer) in zonesFromCenter.zipWithNext()) {
            require(inner.outerRadiusNormalized == outer.innerRadiusNormalized) {
                "TargetType zones must be contiguous without gaps or overlaps."
            }
            require(inner.score > outer.score) {
                "TargetType zone scores must strictly decrease from the center."
            }
        }
        require(innerTenRadiusNormalized <= zonesFromCenter.first().outerRadiusNormalized) {
            "TargetType X radius must be contained in the central maximum-score zone."
        }
        require(!xRingEnabled || innerTenRadiusNormalized > 0f) {
            "TargetType enabled X ring must have a positive radius."
        }
    }

    /** Returns a validated value with an independently owned, unmodifiable zone list. */
    fun copy(
        uuid: String = this.uuid,
        name: String = this.name,
        discipline: String = this.discipline,
        diameterCm: Int = this.diameterCm,
        maxScore: Int = this.maxScore,
        minScore: Int = this.minScore,
        defaultArrowCount: Int = this.defaultArrowCount,
        defaultDistanceM: Int = this.defaultDistanceM,
        isBuiltIn: Boolean = this.isBuiltIn,
        xRingEnabled: Boolean = this.xRingEnabled,
        xRingLabel: String = this.xRingLabel,
        innerTenRadiusNormalized: Float = this.innerTenRadiusNormalized,
        zones: List<TargetZone> = this.zones,
    ): TargetType = TargetType(
        uuid = uuid,
        name = name,
        discipline = discipline,
        diameterCm = diameterCm,
        maxScore = maxScore,
        minScore = minScore,
        defaultArrowCount = defaultArrowCount,
        defaultDistanceM = defaultDistanceM,
        isBuiltIn = isBuiltIn,
        xRingEnabled = xRingEnabled,
        xRingLabel = xRingLabel,
        innerTenRadiusNormalized = innerTenRadiusNormalized,
        zones = zones,
    )

    override fun equals(other: Any?): Boolean =
        other is TargetType && snapshot == other.snapshot

    override fun hashCode(): Int = snapshot.hashCode()

    override fun toString(): String = snapshot.toString().replaceFirst("Snapshot(", "TargetType(")

    operator fun component1(): String = uuid
    operator fun component2(): String = name
    operator fun component3(): String = discipline
    operator fun component4(): Int = diameterCm
    operator fun component5(): Int = maxScore
    operator fun component6(): Int = minScore
    operator fun component7(): Int = defaultArrowCount
    operator fun component8(): Int = defaultDistanceM
    operator fun component9(): Boolean = isBuiltIn
    operator fun component10(): Boolean = xRingEnabled
    operator fun component11(): String = xRingLabel
    operator fun component12(): Float = innerTenRadiusNormalized
    operator fun component13(): List<TargetZone> = zones

    private data class Snapshot(
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
    )
}

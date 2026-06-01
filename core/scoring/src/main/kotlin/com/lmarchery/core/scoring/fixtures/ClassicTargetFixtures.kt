package com.lmarchery.core.scoring.fixtures

import com.lmarchery.core.model.target.TargetType
import com.lmarchery.core.model.target.TargetZone

object ClassicTargetFixtures {
    val classic40: TargetType = classicTarget(
        uuid = "11111111-1111-4111-8111-111111111111",
        name = "Classique 40 cm",
        diameterCm = 40,
        discipline = "indoor",
        defaultArrowCount = 3,
        defaultDistanceM = 18,
    )

    val classic60: TargetType = classicTarget(
        uuid = "22222222-2222-4222-8222-222222222222",
        name = "Classique 60 cm",
        diameterCm = 60,
        discipline = "indoor",
        defaultArrowCount = 3,
        defaultDistanceM = 18,
    )

    val classic80: TargetType = classicTarget(
        uuid = "33333333-3333-4333-8333-333333333333",
        name = "Classique 80 cm",
        diameterCm = 80,
        discipline = "outdoor",
        defaultArrowCount = 6,
        defaultDistanceM = 30,
    )

    val classic122: TargetType = classicTarget(
        uuid = "44444444-4444-4444-8444-444444444444",
        name = "Classique 122 cm",
        diameterCm = 122,
        discipline = "outdoor",
        defaultArrowCount = 6,
        defaultDistanceM = 70,
    )

    val all: List<TargetType> = listOf(classic40, classic60, classic80, classic122)

    private fun classicTarget(
        uuid: String,
        name: String,
        diameterCm: Int,
        discipline: String,
        defaultArrowCount: Int,
        defaultDistanceM: Int,
    ): TargetType = TargetType(
        uuid = uuid,
        name = name,
        discipline = discipline,
        diameterCm = diameterCm,
        maxScore = 10,
        minScore = 1,
        defaultArrowCount = defaultArrowCount,
        defaultDistanceM = defaultDistanceM,
        isBuiltIn = true,
        xRingEnabled = true,
        xRingLabel = "X",
        innerTenRadiusNormalized = 0.05f,
        zones = classicZones(),
    )

    private fun classicZones(): List<TargetZone> = listOf(
        TargetZone(score = 10, innerRadiusNormalized = 0.0f, outerRadiusNormalized = 0.1f, colorName = "yellow", colorHex = "#FFD400"),
        TargetZone(score = 9, innerRadiusNormalized = 0.1f, outerRadiusNormalized = 0.2f, colorName = "yellow", colorHex = "#FFD400"),
        TargetZone(score = 8, innerRadiusNormalized = 0.2f, outerRadiusNormalized = 0.3f, colorName = "red", colorHex = "#E53935"),
        TargetZone(score = 7, innerRadiusNormalized = 0.3f, outerRadiusNormalized = 0.4f, colorName = "red", colorHex = "#E53935"),
        TargetZone(score = 6, innerRadiusNormalized = 0.4f, outerRadiusNormalized = 0.5f, colorName = "blue", colorHex = "#1E88E5"),
        TargetZone(score = 5, innerRadiusNormalized = 0.5f, outerRadiusNormalized = 0.6f, colorName = "blue", colorHex = "#1E88E5"),
        TargetZone(score = 4, innerRadiusNormalized = 0.6f, outerRadiusNormalized = 0.7f, colorName = "black", colorHex = "#212121"),
        TargetZone(score = 3, innerRadiusNormalized = 0.7f, outerRadiusNormalized = 0.8f, colorName = "black", colorHex = "#212121"),
        TargetZone(score = 2, innerRadiusNormalized = 0.8f, outerRadiusNormalized = 0.9f, colorName = "white", colorHex = "#FFFFFF"),
        TargetZone(score = 1, innerRadiusNormalized = 0.9f, outerRadiusNormalized = 1.0f, colorName = "white", colorHex = "#FFFFFF"),
    )
}

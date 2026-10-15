package com.lmarchery.core.scoring.profiles

import com.lmarchery.core.model.target.TargetType
import com.lmarchery.core.model.target.TargetZone
import java.util.Collections

/** Full faces only, frozen to World Archery Book 2 §7.2.2, edition 2026-01-27 (ADR 013). */
object WorldArcheryIndoorTargetProfiles {
    const val RULEBOOK_VERSION: String = "2026-01-27"

    val recurve40: TargetType = fullFace(
        uuid = "5d98a517-4b7b-4308-8f4f-af3bd992a285",
        name = "WA salle 40 cm — classique (2026-01-27)",
        diameterCm = 40,
        distanceM = 18,
        tenRadius = 0.10f,
    )

    val compound40: TargetType = fullFace(
        uuid = "142a7cf4-1bee-427d-b351-c002c7003b98",
        name = "WA salle 40 cm — poulies (2026-01-27)",
        diameterCm = 40,
        distanceM = 18,
        tenRadius = 0.05f,
    )

    val recurve60: TargetType = fullFace(
        uuid = "bb401e90-3818-4ed0-a926-06055b01ad25",
        name = "WA salle 60 cm — classique (2026-01-27)",
        diameterCm = 60,
        distanceM = 25,
        tenRadius = 0.10f,
    )

    val compound60: TargetType = fullFace(
        uuid = "39da4393-d4f9-45e4-86b7-36115ecd06ec",
        name = "WA salle 60 cm — poulies (2026-01-27)",
        diameterCm = 60,
        distanceM = 25,
        tenRadius = 0.05f,
    )

    val all: List<TargetType> = Collections.unmodifiableList(listOf(recurve40, compound40, recurve60, compound60))

    private fun fullFace(uuid: String, name: String, diameterCm: Int, distanceM: Int, tenRadius: Float): TargetType =
        TargetType(
            uuid = uuid,
            name = name,
            discipline = "indoor",
            diameterCm = diameterCm,
            maxScore = 10,
            minScore = 1,
            defaultArrowCount = 3,
            defaultDistanceM = distanceM,
            isBuiltIn = true,
            xRingEnabled = false,
            xRingLabel = "",
            // The scoring radius of 10 belongs to zones; this field configures X only.
            innerTenRadiusNormalized = 0f,
            zones = listOf(
                TargetZone(10, 0f, tenRadius, "yellow", "#FFD400"),
                TargetZone(9, tenRadius, 0.2f, "yellow", "#FFD400"),
                TargetZone(8, 0.2f, 0.3f, "red", "#E53935"),
                TargetZone(7, 0.3f, 0.4f, "red", "#E53935"),
                TargetZone(6, 0.4f, 0.5f, "blue", "#1E88E5"),
                TargetZone(5, 0.5f, 0.6f, "blue", "#1E88E5"),
                TargetZone(4, 0.6f, 0.7f, "black", "#212121"),
                TargetZone(3, 0.7f, 0.8f, "black", "#212121"),
                TargetZone(2, 0.8f, 0.9f, "white", "#FFFFFF"),
                TargetZone(1, 0.9f, 1f, "white", "#FFFFFF"),
            ),
        )
}

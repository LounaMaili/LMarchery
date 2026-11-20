package com.lmarchery.feature.configured

import com.lmarchery.core.model.session.*
import com.lmarchery.core.model.time.SessionDateTime
import com.lmarchery.core.scoring.configured.ConfiguredSessionMetadata
import com.lmarchery.core.scoring.configured.SessionPreset
import com.lmarchery.core.scoring.fixtures.ClassicTargetFixtures
import java.time.Instant
import com.lmarchery.core.scoring.profiles.BuiltInTargetRegistry
import com.lmarchery.core.scoring.profiles.WorldArcheryIndoorTargetProfiles
import com.lmarchery.feature.sessions.EquipmentSelection

/** Local text fields, never a persisted capture or a regulatory preset. No unit/edition inference. */
data class ConfigurationDraft(
    val profile: BuiltInTargetRegistry.Entry = requireNotNull(BuiltInTargetRegistry.find(WorldArcheryIndoorTargetProfiles.recurve40.uuid)),
    val distance: String = "18",
    val arrowCountMode: ArrowCountMode = ArrowCountMode.FIXED,
    val fixedCount: String = "3",
    val indoor: Boolean = true,
    val scoringMode: ScoringMode = ScoringMode.TRAINING,
) {
    internal val suggestedTitle: String?
        get() = distance.toIntOrNull()?.takeIf { it > 0 }?.let { "Séance à $it m · ${profile.definition.name}" }

    fun configuration(): EndConfiguration = EndConfiguration(profile.definition, profile.regulatoryEdition,
        positive(distance, "Distance"), arrowCountMode,
        if (arrowCountMode == ArrowCountMode.FIXED) positive(fixedCount, "Nombre de flèches") else null, indoor, scoringMode)
    companion object {
        fun from(value: EndConfiguration) = ConfigurationDraft(BuiltInTargetRegistry.Entry(value.targetType, value.regulatoryEdition),
            value.distanceM.toString(), value.arrowCountMode, value.arrowCountPerEnd?.toString().orEmpty(), value.isIndoor, value.scoringMode)
    }
}

internal enum class SessionSuggestion(
    val label: String,
    profileUuid: String,
    distanceM: Int,
    arrowsPerEnd: Int,
    indoor: Boolean,
) {
    INDOOR_RECURVE("Salle 18 m — classique", WorldArcheryIndoorTargetProfiles.recurve40.uuid, 18, 3, true),
    INDOOR_COMPOUND("Salle 18 m — poulies", WorldArcheryIndoorTargetProfiles.compound40.uuid, 18, 3, true),
    OUTDOOR_30("Extérieur 30 m", ClassicTargetFixtures.classic80.uuid, 30, 6, false),
    OUTDOOR_50("Extérieur 50 m", ClassicTargetFixtures.classic80.uuid, 50, 6, false),
    OUTDOOR_70("Extérieur 70 m", ClassicTargetFixtures.classic122.uuid, 70, 6, false);

    val configuration = ConfigurationDraft(
        profile = requireNotNull(BuiltInTargetRegistry.find(profileUuid)),
        distance = distanceM.toString(),
        arrowCountMode = ArrowCountMode.FIXED,
        fixedCount = arrowsPerEnd.toString(),
        indoor = indoor,
        scoringMode = ScoringMode.TRAINING,
    )
}

data class ConfiguredMetadataDraft(val title: String = "", val plannedEnds: String = "", val location: String? = null,
    val notes: String? = null, val equipment: EquipmentSelection = EquipmentSelection()) {
    fun metadata(date: SessionDateTime) = ConfiguredSessionMetadata(date, title,
        plannedEndCount(),
        equipment.bowUuid, equipment.arrowSetUuid, location, notes)
    fun plannedEndCount() = if (plannedEnds.isEmpty()) null else positive(plannedEnds, "Nombre de volées prévues")

    companion object {
        fun from(value: ConfiguredSessionMetadata) = ConfiguredMetadataDraft(value.title, value.plannedEndCount?.toString().orEmpty(),
            value.locationName, value.notes, EquipmentSelection(value.bowUuid, value.arrowSetUuid))
    }
}

sealed interface ConfiguredForm {
    val token: Long
    data class Creation(override val token: Long, val configuration: ConfigurationDraft = ConfigurationDraft(),
        val metadata: ConfiguredMetadataDraft = ConfiguredMetadataDraft(),
        val presetEquipment: EquipmentSelection = EquipmentSelection(),
    ) : ConfiguredForm {
        fun asPreset(uuid: String, name: String, createdAt: Instant) = SessionPreset(uuid, name.trim(),
            configuration.configuration(), metadata.plannedEndCount(),
            metadata.equipment.bowUuid, metadata.equipment.arrowSetUuid, createdAt)

        fun withPreset(preset: SessionPreset): Creation {
            val equipment = EquipmentSelection(preset.bowUuid, preset.arrowSetUuid)
            return copy(configuration = ConfigurationDraft.from(preset.configuration),
                metadata = metadata.copy(title = metadata.title.ifBlank { preset.name },
                    plannedEnds = preset.plannedEndCount?.toString().orEmpty(), equipment = equipment),
                presetEquipment = equipment)
        }
    }
    data class NextEnd(override val token: Long, val configuration: ConfigurationDraft) : ConfiguredForm
    data class Metadata(override val token: Long, val metadata: ConfiguredMetadataDraft) : ConfiguredForm
    data class PresetEdit(
        override val token: Long,
        val creation: Creation,
        val expected: SessionPreset,
        val configuration: ConfigurationDraft = ConfigurationDraft.from(expected.configuration),
        val metadata: ConfiguredMetadataDraft = ConfiguredMetadataDraft(
            title = expected.name,
            plannedEnds = expected.plannedEndCount?.toString().orEmpty(),
            equipment = EquipmentSelection(expected.bowUuid, expected.arrowSetUuid),
        ),
    ) : ConfiguredForm {
        fun proposed() = expected.copy(
            name = metadata.title.trim(),
            configuration = configuration.configuration(),
            plannedEndCount = metadata.plannedEndCount(),
            bowUuid = metadata.equipment.bowUuid,
            arrowSetUuid = metadata.equipment.arrowSetUuid,
        )
    }
    data class PresetDeletion(override val token: Long, val creation: Creation, val expected: SessionPreset) : ConfiguredForm
}

private fun positive(text: String, name: String): Int = requireNotNull(text.toIntOrNull()?.takeIf { it > 0 }) {
    "$name : entier strictement positif requis, dans la plage Int."
}

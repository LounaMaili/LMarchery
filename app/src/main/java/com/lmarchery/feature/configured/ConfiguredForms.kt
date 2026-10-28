package com.lmarchery.feature.configured

import com.lmarchery.core.model.session.*
import com.lmarchery.core.model.time.SessionDateTime
import com.lmarchery.core.scoring.configured.ConfiguredSessionMetadata
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
    fun configuration(): EndConfiguration = EndConfiguration(profile.definition, profile.regulatoryEdition,
        positive(distance, "Distance"), arrowCountMode,
        if (arrowCountMode == ArrowCountMode.FIXED) positive(fixedCount, "Nombre de flèches") else null, indoor, scoringMode)
    companion object {
        fun from(value: EndConfiguration) = ConfigurationDraft(BuiltInTargetRegistry.Entry(value.targetType, value.regulatoryEdition),
            value.distanceM.toString(), value.arrowCountMode, value.arrowCountPerEnd?.toString().orEmpty(), value.isIndoor, value.scoringMode)
    }
}

data class ConfiguredMetadataDraft(val title: String = "", val plannedEnds: String = "", val location: String? = null,
    val notes: String? = null, val equipment: EquipmentSelection = EquipmentSelection()) {
    fun metadata(date: SessionDateTime) = ConfiguredSessionMetadata(date, title,
        if (plannedEnds.isEmpty()) null else positive(plannedEnds, "Nombre de volées prévues"),
        equipment.bowUuid, equipment.arrowSetUuid, location, notes)
    companion object {
        fun from(value: ConfiguredSessionMetadata) = ConfiguredMetadataDraft(value.title, value.plannedEndCount?.toString().orEmpty(),
            value.locationName, value.notes, EquipmentSelection(value.bowUuid, value.arrowSetUuid))
    }
}

sealed interface ConfiguredForm {
    val token: Long
    data class Creation(override val token: Long, val configuration: ConfigurationDraft = ConfigurationDraft(),
        val metadata: ConfiguredMetadataDraft = ConfiguredMetadataDraft()) : ConfiguredForm
    data class NextEnd(override val token: Long, val configuration: ConfigurationDraft, val explicit: Boolean = false) : ConfiguredForm
    data class Metadata(override val token: Long, val metadata: ConfiguredMetadataDraft) : ConfiguredForm
}

private fun positive(text: String, name: String): Int = requireNotNull(text.toIntOrNull()?.takeIf { it > 0 }) {
    "$name : entier strictement positif requis, dans la plage Int."
}

package com.lmarchery.core.scoring.configured

import com.lmarchery.core.model.session.EndConfiguration
import com.lmarchery.core.scoring.profiles.BuiltInTargetRegistry
import java.time.Instant

/** Reusable defaults; a created session owns its configuration and metadata. */
data class SessionPreset(
    val uuid: String,
    val name: String,
    val configuration: EndConfiguration,
    val plannedEndCount: Int?,
    val bowUuid: String?,
    val arrowSetUuid: String?,
    val createdAt: Instant,
) {
    init {
        requireConfiguredUuid(uuid, "preset.uuid")
        require(name.isNotBlank() && name == name.trim() && name.length <= MAX_NAME_LENGTH) {
            "Nom du preset : de 1 à $MAX_NAME_LENGTH caractères, hors espaces de début et de fin."
        }
        require(plannedEndCount == null || plannedEndCount > 0) { "Nombre de volées prévues : entier strictement positif requis." }
        val published = BuiltInTargetRegistry.find(configuration.targetType.uuid)
        require(published != null && published.definition == configuration.targetType && published.regulatoryEdition == configuration.regulatoryEdition) {
            "Le preset doit reprendre la définition et l’édition exactes d’un blason du référentiel."
        }
        bowUuid?.let { requireConfiguredUuid(it, "preset.bowUuid") }
        arrowSetUuid?.let { requireConfiguredUuid(it, "preset.arrowSetUuid") }
    }

    companion object {
        const val MAX_NAME_LENGTH = 80
    }
}

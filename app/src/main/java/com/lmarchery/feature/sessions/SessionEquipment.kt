package com.lmarchery.feature.sessions

import com.lmarchery.core.database.*
import com.lmarchery.core.model.equipment.*
import com.lmarchery.data.*
import kotlinx.coroutines.CancellationException

data class EquipmentSelection(val bowUuid: String? = null, val arrowSetUuid: String? = null) {
    fun with(kind: EquipmentKind, uuid: String?) = if (kind == EquipmentKind.BOW) copy(bowUuid = uuid) else copy(arrowSetUuid = uuid)
}
data class EquipmentChoices(val bows: List<Bow> = emptyList(), val arrowSets: List<ArrowSet> = emptyList(),
    val diagnostics: List<String> = emptyList(), val loaded: Boolean = false) {
    fun values(kind: EquipmentKind): List<Equipment> = if (kind == EquipmentKind.BOW) bows else arrowSets
    fun allows(kind: EquipmentKind, uuid: String?, original: String?): Boolean =
        uuid == null || uuid == original || values(kind).any { it.uuid == uuid }
    fun label(kind: EquipmentKind, uuid: String?): String = if (uuid == null) "Aucun matériel associé"
        else values(kind).find { it.uuid == uuid }?.let { "${it.name} · $uuid" }
            ?: "$uuid — ${if (loaded) "référence non résolue" else "fiche non chargée"}"
}

/** Presentation discovery is not an archive snapshot. Failed entries never become selectable values. */
internal suspend fun loadEquipmentChoices(repository: EquipmentRepository): EquipmentChoices {
    val values = mutableListOf<Equipment>(); val diagnostics = mutableListOf<String>()
    for (kind in EquipmentKind.entries) try {
        when (val result = repository.discover(kind)) {
            is EquipmentDiscoveryResult.TechnicalFailure -> diagnostics += "$kind : découverte impossible. Associations facultatives."
            is EquipmentDiscoveryResult.Found -> for (uuid in result.uuids) when (val read = repository.read(kind, uuid)) {
                is EquipmentReadResult.Found -> if ((kind == EquipmentKind.BOW && read.value is Bow || kind == EquipmentKind.ARROW_SET && read.value is ArrowSet) && read.value.uuid == uuid)
                    values += read.value else diagnostics += "$uuid : fiche du mauvais type."
                is EquipmentReadResult.NeedsIntervention -> diagnostics += "${read.diagnostic.path} : ${read.diagnostic.detail}"
                else -> diagnostics += "$uuid : fiche non résolue, aucune réparation."
            }
        }
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { diagnostics += "$kind : lecture impossible. Associations facultatives." }
    return EquipmentChoices(values.filterIsInstance<Bow>().toList(), values.filterIsInstance<ArrowSet>().toList(), diagnostics.toList(), loaded = true)
}

internal fun equipmentAssociationLines(choices: EquipmentChoices, selection: EquipmentSelection): List<String> = listOf(
    "Arc : ${choices.label(EquipmentKind.BOW, selection.bowUuid)}",
    "Flèches : ${choices.label(EquipmentKind.ARROW_SET, selection.arrowSetUuid)}",
)

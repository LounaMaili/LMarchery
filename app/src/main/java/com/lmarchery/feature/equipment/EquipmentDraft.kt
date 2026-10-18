package com.lmarchery.feature.equipment

import com.lmarchery.core.model.equipment.*
import com.lmarchery.data.EquipmentKind
import java.time.Instant

enum class EquipmentField(val label: String) {
    NAME("Nom"), TYPE("Type d’arc"), POWER("Puissance en livres"), BRAND("Marque"), MODEL("Modèle"),
    SPINE("Spine — texte et unités libres"), LENGTH("Longueur — préciser les unités"),
    POINT("Pointe — texte et unités libres"), NOCK("Encoche"), VANES("Empennage"), MATERIAL("Matériau"), NOTES("Notes")
}

/** A null optional value is distinct from a deliberately supplied empty string. No trimming or inferred units. */
data class EquipmentDraft(val kind: EquipmentKind, val name: String = "", val type: String? = null,
    val power: String? = null, val brand: String? = null, val model: String? = null, val spine: String? = null,
    val length: String? = null, val point: String? = null, val nock: String? = null, val vanes: String? = null,
    val material: String? = null, val notes: String? = null) {
    val fields get() = listOf(EquipmentField.NAME) + (if (kind == EquipmentKind.BOW)
        listOf(EquipmentField.TYPE, EquipmentField.POWER, EquipmentField.BRAND, EquipmentField.MODEL)
        else listOf(EquipmentField.SPINE, EquipmentField.LENGTH, EquipmentField.POINT, EquipmentField.NOCK, EquipmentField.VANES, EquipmentField.MATERIAL)) + EquipmentField.NOTES
    fun value(field: EquipmentField): String? = when (field) {
        EquipmentField.NAME -> name; EquipmentField.TYPE -> type; EquipmentField.POWER -> power
        EquipmentField.BRAND -> brand; EquipmentField.MODEL -> model; EquipmentField.SPINE -> spine
        EquipmentField.LENGTH -> length; EquipmentField.POINT -> point; EquipmentField.NOCK -> nock
        EquipmentField.VANES -> vanes; EquipmentField.MATERIAL -> material; EquipmentField.NOTES -> notes
    }
    fun withField(field: EquipmentField, value: String?): EquipmentDraft {
        require(field in fields) { "Champ incompatible avec la fiche." }
        return when (field) {
            EquipmentField.NAME -> copy(name = requireNotNull(value)); EquipmentField.TYPE -> copy(type = value)
            EquipmentField.POWER -> copy(power = value); EquipmentField.BRAND -> copy(brand = value)
            EquipmentField.MODEL -> copy(model = value); EquipmentField.SPINE -> copy(spine = value)
            EquipmentField.LENGTH -> copy(length = value); EquipmentField.POINT -> copy(point = value)
            EquipmentField.NOCK -> copy(nock = value); EquipmentField.VANES -> copy(vanes = value)
            EquipmentField.MATERIAL -> copy(material = value); EquipmentField.NOTES -> copy(notes = value)
        }
    }
    fun revision(expected: Equipment?, at: Instant, newUuid: () -> String): Equipment {
        require(name.isNotBlank()) { "Le nom est obligatoire et ne peut pas être blanc." }
        if (expected != null && this == from(expected)) return expected
        val weight = power?.let { raw ->
            val value = raw.toFloatOrNull()
            require(value != null && value.isFinite() && value > 0f) { "La puissance renseignée doit être un nombre fini strictement positif, en livres." }
            value
        }
        return when (kind) {
            EquipmentKind.BOW -> {
                require(expected == null || expected is Bow)
                if (expected is Bow) expected.copy(name = name, type = type, drawWeightLbs = weight, brand = brand, model = model, notes = notes, updatedAt = at)
                else Bow(newUuid(), name, type, weight, brand, model, notes, at, at)
            }
            EquipmentKind.ARROW_SET -> {
                require(expected == null || expected is ArrowSet)
                if (expected is ArrowSet) expected.copy(name = name, spine = spine, length = length, pointWeight = point,
                    nock = nock, vanes = vanes, material = material, notes = notes, updatedAt = at)
                else ArrowSet(newUuid(), name, spine, length, point, nock, vanes, material, notes, at, at)
            }
        }
    }
    companion object {
        fun from(value: Equipment): EquipmentDraft = when (value) {
            is Bow -> EquipmentDraft(EquipmentKind.BOW, value.name, value.type, value.drawWeightLbs?.toString(), value.brand, value.model, notes = value.notes)
            is ArrowSet -> EquipmentDraft(EquipmentKind.ARROW_SET, value.name, spine = value.spine, length = value.length,
                point = value.pointWeight, nock = value.nock, vanes = value.vanes, material = value.material, notes = value.notes)
        }
    }
}

internal fun EquipmentKind.label() = if (this == EquipmentKind.BOW) "Arcs" else "Jeux de flèches"

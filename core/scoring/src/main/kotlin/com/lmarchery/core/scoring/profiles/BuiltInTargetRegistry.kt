package com.lmarchery.core.scoring.profiles

import com.lmarchery.core.model.target.TargetType
import com.lmarchery.core.scoring.fixtures.ClassicTargetFixtures
import java.util.Collections

/** Published identities and their complete definitions; no model -> scoring dependency. */
object BuiltInTargetRegistry {
    data class Entry(val definition: TargetType, val regulatoryEdition: String?)

    val all: List<Entry> = Collections.unmodifiableList(
        ClassicTargetFixtures.all.map { Entry(it, null) } +
            WorldArcheryIndoorTargetProfiles.all.map { Entry(it, WorldArcheryIndoorTargetProfiles.RULEBOOK_VERSION) },
    )

    fun find(uuid: String): Entry? = all.singleOrNull { it.definition.uuid == uuid }
}

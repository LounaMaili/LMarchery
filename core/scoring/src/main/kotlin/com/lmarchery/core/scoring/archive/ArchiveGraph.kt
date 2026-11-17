package com.lmarchery.core.scoring.archive

import com.lmarchery.core.model.equipment.ArrowSet
import com.lmarchery.core.model.equipment.Bow
import com.lmarchery.core.scoring.configured.ConfiguredSession

/** Common complete graph for audits only. It is not an encoder or a downgrade operation. */
interface ArchiveGraph {
    val profiles: List<TargetProfileSnapshot>
    val sessions: List<ConfiguredSession>
    val bows: List<Bow>
    val arrowSets: List<ArrowSet>
}

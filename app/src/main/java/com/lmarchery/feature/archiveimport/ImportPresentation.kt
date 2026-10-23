package com.lmarchery.feature.archiveimport

import com.lmarchery.core.database.ArchiveV2Counts
import com.lmarchery.core.scoring.archive.ArchiveV2ImportPlan

internal fun v2PlanLines(plan: ArchiveV2ImportPlan): List<String> = listOf(
    "Séances" to plan.sessions, "Profils" to plan.profiles, "Arcs" to plan.bows, "Jeux de flèches" to plan.arrowSets,
).map { (name, category) ->
    "$name : ${category.newUuids.size} nouveau(x), ${category.identicalUuids.size} identique(s), ${category.conflictingUuids.size} conflictuel(s)."
}

private fun ArchiveV2Counts.text() = "$sessions séance(s), $profiles profil(s), $bows arc(s), $arrowSets jeu(x) de flèches"
internal fun v2SuccessText(state: ImportState.SucceededV2): String =
    (if (state.emptyArchive) "Archive V2 vide vérifiée, aucune écriture."
    else if (state.alreadyPresent) "V2 déjà présente à l’identique, aucune écriture : ${state.identical.text()}."
    else "Import V2 confirmé. Ajoutés : ${state.added.text()}. Identiques : ${state.identical.text()}.") +
        if (state.refreshFailed) " Actualisation impossible : utilisez Actualiser la liste." else ""

internal fun v3PlanLines(plan: com.lmarchery.core.scoring.archive.v3.ArchiveV3ImportPlan): List<String> = listOf(
    "Séances" to plan.sessions, "Profils" to plan.profiles, "Volées" to plan.ends,
    "Impacts" to plan.impacts, "Arcs" to plan.bows, "Jeux de flèches" to plan.arrowSets,
).map { (name, c) -> "$name : ${c.newUuids.size} nouveau(x), ${c.identicalUuids.size} identique(s), ${c.conflictingUuids.size} conflictuel(s)." }

private fun com.lmarchery.core.database.ArchiveV3Counts.text() =
    "$sessions séance(s), $profiles profil(s), $ends volée(s), $impacts impact(s), $bows arc(s), $arrowSets jeu(x) de flèches"
internal fun v3SuccessText(state: ImportState.SucceededV3): String =
    (if (state.emptyArchive) "Archive V3 vide vérifiée ; aucune écriture ni suppression."
    else if (state.alreadyPresent) "V3 déjà présente à l’identique, aucune écriture : ${state.identical.text()}."
    else "Restauration V3 confirmée. Ajoutés : ${state.added.text()}. Identiques : ${state.identical.text()}.") +
        if (state.refreshFailed) " Actualisation impossible : utilisez Actualiser la liste." else ""

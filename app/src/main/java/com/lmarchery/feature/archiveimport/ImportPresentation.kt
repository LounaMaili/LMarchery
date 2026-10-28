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

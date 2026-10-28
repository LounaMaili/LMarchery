package com.lmarchery.feature.sessions

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.FinalScoreDecision

/** Presentation only. A null impact UUID means a new arrow, never a replacement. */
data class AimContext(val sessionUuid: String, val endUuid: String, val impactUuid: String? = null)
data class PositionCandidate(val context: AimContext, val point: NormalizedPoint, val preview: FinalScoreDecision)
data class ActiveAim(val token: Long, val candidate: PositionCandidate)

fun PositionCandidate.scoreLabel(): String = when {
    preview.isMiss -> "M"
    preview.isX -> "${preview.scoreFinal}X"
    else -> preview.scoreFinal.toString()
}

data class NewImpactNotice(val heading: String, val guidance: String)

/** A submitted/failed write may already have committed: never describe it as certainly absent from SQL. */
fun presentNewImpactNotice(state: SessionUiState): NewImpactNotice = if (state.pending != null) NewImpactNotice(
    "Nouvelle flèche · enregistrement non confirmé",
    "Action soumise : les données durables affichées restent celles du dernier acquittement. Résolvez la sauvegarde conservée avant de continuer.",
) else NewImpactNotice(
    "Nouvelle flèche · non enregistrée",
    "Vous pouvez viser à nouveau ou annuler. Aucun enregistrement n’a été demandé pour ce candidat.",
)

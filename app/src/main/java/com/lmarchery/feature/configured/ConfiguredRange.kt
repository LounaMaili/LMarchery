package com.lmarchery.feature.configured

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lmarchery.core.model.session.EndStatus
import com.lmarchery.core.scoring.configured.ConfiguredEnd
import com.lmarchery.feature.scoring.TargetCanvas
import com.lmarchery.feature.scoring.TargetInteraction
import com.lmarchery.feature.sessions.*

@Composable
internal fun ConfiguredRange(
    state: ConfiguredEditorState,
    controller: ConfiguredSessionController,
    back: () -> Unit,
    finish: () -> Unit,
    openPhoto: (String) -> Unit,
    openGrouping: (String) -> Unit,
) {
    val session = requireNotNull(state.saved)
    val captured = state.selectedEnd
    val context = state.targetContext
    val configuration = captured?.configuration ?: session.initialConfiguration
    val impacts = captured?.end?.activeImpacts.orEmpty()
    var showDetails by rememberSaveable(session.uuid) { mutableStateOf(false) }
    var showImpacts by rememberSaveable(captured?.uuid) { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()
            .preciseTouchGuard(controller, { controller.state.value.guardTargetTouches },
                { controller.state.value.activeAim?.token }, controller::cancelAim),
    ) {
        Surface(color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val colors = ButtonDefaults.textButtonColors(
                        contentColor = LocalContentColor.current,
                        disabledContentColor = LocalContentColor.current.copy(alpha = .58f),
                    )
                    TextButton(back, enabled = !state.busy,
                        colors = colors) { Text("‹ Historique") }
                    Spacer(Modifier.weight(1f))
                    if (captured != null) TextButton({ openPhoto(captured.uuid) }, enabled = state.canNavigate,
                        colors = colors) { Text("Photo") }
                    TextButton({ showDetails = true }, enabled = state.canNavigate,
                        colors = colors) { Text("Séance") }
                }
                Text(session.metadata.title, style = MaterialTheme.typography.titleLarge, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text("${configuration.distanceM} m  ·  ${configuration.targetType.name}", maxLines = 1,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 5.dp),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    RangeMetric("VOLÉE", (captured?.end?.index ?: 1).toString())
                    RangeMetric("FLÈCHES", "${impacts.size} / ${configuration.arrowCountPerEnd ?: "libre"}")
                    RangeMetric("SCORE", impacts.sumOf { it.scoreFinal.toLong() }.toString())
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            if (maxWidth > maxHeight + 110.dp) {
                Row(Modifier.fillMaxSize()) {
                    RangeTarget(state, controller, captured, context, Modifier.weight(1f).fillMaxHeight())
                    RangeDock(state, controller, context, { showImpacts = true }, finish, Modifier.widthIn(max = 310.dp).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    RangeTarget(state, controller, captured, context, Modifier.fillMaxWidth().weight(1f))
                    RangeDock(state, controller, context, { showImpacts = true }, finish, Modifier.fillMaxWidth().height(175.dp))
                }
            }
        }
    }

    if (showDetails) AlertDialog(
        onDismissRequest = { showDetails = false },
        title = { Text("Séance et volées") },
        text = {
            LazyColumn(Modifier.heightIn(max = 455.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    if (captured != null) {
                        TextButton({
                            showDetails = false
                            openGrouping(captured.uuid)
                        }, enabled = state.canNavigate) { Text("Bilan de la volée ${captured.end.index}") }
                    }
                    state.savedPlace?.let { AdoptedPlaceLabel(it) }
                    com.lmarchery.feature.weather.WeatherSection(session.uuid, session.ends.map { it.uuid to it.end.index }, state.canNavigate)
                    ConfiguredSessionSummary(session, readOnly = false)
                    TextButton({ showDetails = false; controller.editMetadata() }, enabled = state.canForm) {
                        Text("Modifier les métadonnées et le matériel")
                    }
                    TextButton({ showDetails = false; controller.nextEnd(state.epoch) }, enabled = state.canForm) {
                        Text(if (session.ends.isEmpty()) "Créer la première volée" else "Volée suivante")
                    }
                    TextButton({ showDetails = false; controller.editNextEnd(state.epoch) }, enabled = state.canForm) {
                        Text(nextEndSettingsLabel)
                    }
                    TextButton({ showDetails = false; finish() }, enabled = state.canForm) { Text("Terminer la séance") }
                }
                items(session.ends.sortedBy { it.end.index }, key = { it.uuid }) { focus ->
                    OutlinedButton({ controller.selectEnd(focus.uuid); showDetails = false },
                        Modifier.fillMaxWidth(), enabled = state.canNavigate) {
                        Text("Voir la volée ${focus.end.index} · ${if (focus.end.status == EndStatus.VALIDATED) "validée" else "en saisie"}")
                    }
                    if (focus.uuid != captured?.uuid) TextButton({
                        showDetails = false
                        openGrouping(focus.uuid)
                    }, enabled = state.canNavigate) { Text("Bilan · volée ${focus.end.index}") }
                    ConfiguredEndCard(session, focus)
                    ImpactCancellationHistory(focus.end, focus.configuration.regulatoryEdition)
                }
            }
        },
        confirmButton = { TextButton({ showDetails = false }) { Text("Fermer") } },
    )

    val picking = state.impactPicking
    if (captured != null && context != null && (showImpacts || (picking?.candidates?.size ?: 0) > 1)) {
        val choices = if (picking == null) impacts else picking.candidates.take(20).mapNotNull { id -> impacts.find { it.uuid == id } }
        val dismiss: () -> Unit = {
            showImpacts = false
            picking?.let { controller.cancelImpactPicking(it.context) }
        }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(if (picking == null) "Flèches de la volée" else "Plusieurs flèches à cet endroit") },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 310.dp)) {
                    items(choices, key = { it.uuid }) { impact ->
                        TextButton({
                            if (picking == null) controller.selectImpact(context, impact.uuid)
                            else controller.choosePickedImpact(picking.context, impact.uuid)
                            showImpacts = false
                        }, Modifier.fillMaxWidth(), enabled = if (picking == null) state.canSelectImpact else state.canPickImpact) {
                            Text("Flèche ${impact.index} · ${impact.scoreLabel()}")
                        }
                    }
                    if ((picking?.candidates?.size ?: 0) > choices.size) {
                        item { Text("20 choix affichés. Consultez la liste complète des flèches si nécessaire.") }
                    }
                }
            },
            confirmButton = { TextButton(dismiss) { Text("Fermer") } },
        )
    }

    if (context != null) {
        val edit = state.edit
        val options = state.scoreOptions
        val selected = state.selectedImpact
        if (edit != null && options != null && selected != null && state.pending == null && state.cancellation == null &&
            (edit.proposal is ImpactProposal.BoundaryJudgment || edit.proposal is ImpactProposal.ExceptionalCorrection)) {
            AlertDialog(
                onDismissRequest = { controller.closeSelection(context) },
                title = { Text("Corriger la flèche ${selected.index}") },
                text = {
                    Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                        ScoreDecisionPanel(
                            ScoreDecisionView(selected, edit, options, state.selectedBoundary,
                                state.proposedScore, state.canScoreDecision, state.canConfirmScore),
                            ScoreDecisionActions({ controller.boundary(context, it) }, { controller.scoreValue(context, it) },
                                { controller.showBoundaries(context) }, { controller.exceptional(context, true) },
                                { controller.exceptional(context, false) }, { controller.decisionReason(context, it) },
                                { controller.confirmDecision(context) }),
                        )
                    }
                },
                confirmButton = { TextButton({ controller.closeSelection(context) }, enabled = state.ready) { Text("Fermer") } },
            )
        }
    }
    if (state.pending != null && !state.busy && state.cancellation == null) AlertDialog(
        onDismissRequest = {},
        title = { Text("Écriture non confirmée") },
        text = { ConfiguredReconciliationActions(state, controller) },
        confirmButton = { TextButton(back) { Text("Revenir à l’historique") } },
    )
}

@Composable
private fun RangeMetric(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
    }
}

@Composable
private fun RangeTarget(
    state: ConfiguredEditorState,
    controller: ConfiguredSessionController,
    captured: ConfiguredEnd?,
    context: ConfiguredTargetContext?,
    modifier: Modifier,
) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        BoxWithConstraints(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
            // The target has one allocation. Transient text, receipts and editors live in the
            // fixed dock or a dialog, so showing or clearing one leaves this viewport alone.
            val diameter = minOf(maxWidth, maxHeight, 510.dp)
            if (captured != null && context != null && diameter > 32.dp) {
                key(context) {
                    TargetCanvas(
                        targetType = captured.configuration.targetType,
                        impacts = captured.end.activeImpacts,
                        interaction = when {
                            state.impactPicking != null -> TargetInteraction.SELECT_IMPACT
                            state.impactDrag != null && state.canReposition -> TargetInteraction.DRAG_IMPACT
                            state.canReposition -> TargetInteraction.REPOSITION
                            state.canAdd || state.canAim -> TargetInteraction.ADD_IMPACT
                            else -> TargetInteraction.READ_ONLY
                        },
                        endUuid = captured.uuid,
                        onPointSelected = { controller.tapped(context, it) },
                        modifier = Modifier.size(diameter),
                        selectedImpactUuid = state.selectedImpact?.uuid,
                        candidate = state.activeAim?.candidate?.point ?: state.position?.point ?: state.edit?.candidate,
                        preciseAim = state.precision && state.impactDrag == null,
                        onAimStart = { controller.beginAim(context, it) },
                        onAimMove = controller::moveAim,
                        onAimFinish = controller::finishAim,
                        onAimCancel = controller::cancelAim,
                        impactPickContext = state.impactPicking?.context,
                        onImpactPick = controller::pickImpact,
                        impactDragContext = state.impactDrag,
                        onDragStart = controller::beginImpactDrag,
                        onDragMove = controller::moveImpactDrag,
                        onDragFinish = controller::finishImpactDrag,
                        onDragCancel = controller::cancelAim,
                    )
                }
            } else if (captured == null) {
                Column(Modifier.widthIn(max = 270.dp).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Prêt à tirer", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text("Créez la première volée pour afficher le blason.")
                }
            }
        }
    }
}

@Composable
private fun RangeDock(
    state: ConfiguredEditorState,
    c: ConfiguredSessionController,
    context: ConfiguredTargetContext?,
    openImpacts: () -> Unit,
    finish: () -> Unit,
    modifier: Modifier,
) {
    val captured = state.selectedEnd
    val picking = state.impactPicking
    val selected = state.selectedImpact
    val edit = state.edit
    val drag = state.impactDrag
    var displacementMenu by remember(context) { mutableStateOf(false) }
    var reasonDialog by remember(context) { mutableStateOf(false) }
    var readProblem by rememberSaveable { mutableStateOf(false) }
    val status = when {
        state.busy -> "Enregistrement en cours…"
        state.problem != null -> state.problem.message
        state.activeAim != null -> "Relâchez pour proposer la position."
        state.position != null -> "Position proposée · ${state.position.scoreLabel()} · non enregistrée"
        state.canReposition -> edit?.preview?.let { "Position proposée · ${it.scoreFinal} pt · non enregistrée" }
            ?: if (drag != null) "Glissez le marqueur sélectionné." else "Touchez la nouvelle position."
        selected != null -> "Flèche ${selected.index} · ${selected.scoreLabel()}"
        picking?.hasTapped == true && picking.candidates.isEmpty() -> "Aucun impact ici. Touchez un marqueur."
        picking != null -> "Touchez un marqueur pour sélectionner."
        captured == null -> "La séance est en cours."
        captured.end.status == EndStatus.VALIDATED -> "Volée validée"
        !state.hasCapacity -> "Volée pleine"
        state.precision -> "Visée précise · maintenez puis relâchez."
        else -> "Saisie rapide · touchez le blason."
    }

    Surface(modifier, tonalElevation = 4.dp) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp)) {
            val problem = state.problem
            if (problem != null && !state.busy) {
                TextButton({ readProblem = true }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text(status, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.error)
                }
            } else {
                Text(status, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 5.dp, bottom = 3.dp))
            }
            when {
                context == null -> Button({ c.nextEnd(state.epoch) }, Modifier.fillMaxWidth(), enabled = state.canForm) {
                    Text("Créer la première volée")
                }
                state.position != null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ c.cancelPosition(context) }, Modifier.weight(1f), enabled = state.ready) { Text("Annuler") }
                    Button({ c.confirmPosition(context) }, Modifier.weight(1f), enabled = state.canConfirmPosition) { Text("Confirmer la flèche") }
                }
                state.canReposition -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ c.closeSelection(context) }, Modifier.weight(1f), enabled = state.ready) { Text("Annuler") }
                        Button({
                            if (drag == null) c.confirmReposition(context)
                            else edit?.candidate?.let { c.confirmImpactDrag(drag, it) }
                        }, Modifier.weight(1f), enabled = state.canConfirmReposition) { Text("Confirmer") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton({ reasonDialog = true }, enabled = state.ready) {
                            Text(if (selected?.finalScoreDecision?.provenance?.isManual == true) "Motif requis" else "Ajouter un motif")
                        }
                        if (drag == null) TextButton({ c.precision(context, !state.precision) }, enabled = state.activeAim == null) {
                            Text(if (state.precision) "Visée rapide" else "Visée précise")
                        }
                    }
                }
                selected != null -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) {
                            OutlinedButton({ displacementMenu = true }, Modifier.fillMaxWidth(), enabled = state.canSelectImpact) { Text("Déplacer") }
                            DropdownMenu(displacementMenu, { displacementMenu = false }) {
                                DropdownMenuItem({ Text("Glisser le marqueur") }, {
                                    displacementMenu = false
                                    c.beginDragReposition(context)
                                })
                                DropdownMenuItem({ Text("Toucher la nouvelle position") }, {
                                    displacementMenu = false
                                    c.beginReposition(context)
                                })
                            }
                        }
                        OutlinedButton({ c.beginDecision(context) }, Modifier.weight(1f), enabled = state.canSelectImpact) { Text("Corriger le score") }
                    }
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        TextButton({ c.beginCancellation(context) }, enabled = state.canSelectImpact) {
                            Text("Annuler la saisie", color = if (state.canSelectImpact) MaterialTheme.colorScheme.error else LocalContentColor.current)
                        }
                        TextButton({ c.closeSelection(context) }, enabled = state.ready) { Text("Fermer la sélection") }
                    }
                }
                captured?.end?.status == EndStatus.VALIDATED -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button({ c.nextEnd(state.epoch) }, Modifier.weight(1f), enabled = state.canForm) {
                            Text("Volée suivante")
                        }
                        OutlinedButton({ c.editNextEnd(state.epoch) }, Modifier.weight(1.25f), enabled = state.canForm,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
                            Text(nextEndSettingsLabel, textAlign = TextAlign.Center)
                        }
                    }
                    TextButton(finish, enabled = state.canForm) { Text("Terminer la séance") }
                }
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ c.beginImpactPicking(context) }, Modifier.weight(1f), enabled = state.canBeginImpactPicking) { Text("Sélectionner") }
                        if (picking != null) {
                            OutlinedButton({ c.cancelImpactPicking(picking.context) }, Modifier.weight(1f), enabled = state.ready) { Text("Annuler la sélection") }
                        } else {
                            OutlinedButton(openImpacts, Modifier.weight(1f), enabled = state.canSelectImpact &&
                                captured?.end?.activeImpacts?.isNotEmpty() == true) { Text("Flèches") }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ c.precision(context, !state.precision) }, Modifier.weight(1f),
                            enabled = state.canPlace && state.activeAim == null) { Text(if (state.precision) "Visée rapide" else "Visée précise") }
                        Button(c::validateEnd, Modifier.weight(1f), enabled = state.canNavigate && state.editableEnd) { Text("Valider la volée") }
                    }
                }
            }
        }
    }
    if (reasonDialog && context != null && edit != null && state.canReposition) AlertDialog(
        onDismissRequest = { reasonDialog = false },
        title = { Text("Motif du déplacement") },
        text = { OutlinedTextField(edit.reason, { c.repositionReason(context, it) }, Modifier.fillMaxWidth(), label = { Text("Motif") }) },
        confirmButton = { TextButton({ reasonDialog = false }) { Text("Terminer") } },
    )
    if (readProblem && state.problem != null) AlertDialog(
        onDismissRequest = { readProblem = false },
        title = { Text("Détail du message") },
        text = { Text(state.problem.message) },
        confirmButton = { TextButton({ readProblem = false }) { Text("Fermer") } },
    )
}

private const val nextEndSettingsLabel = "Modifier les réglages de la prochaine volée"

@Composable
internal fun ConfiguredReconciliationActions(state: ConfiguredEditorState, controller: ConfiguredSessionController) {
    Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.pending?.let { Text(it.description, style = MaterialTheme.typography.titleMedium) }
        state.problem?.let { Text(it.message) }
        Text("Relisez la séance enregistrée avant toute nouvelle confirmation.")
        OutlinedButton(controller::reconcile, enabled = !state.busy) { Text("Relire et comparer l’état enregistré") }
        Button(controller::retryExact, enabled = state.canRetryExact) { Text("Confirmer à nouveau la même intention") }
    }
}

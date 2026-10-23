package com.lmarchery.feature.configured

import com.lmarchery.core.database.*
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.*
import com.lmarchery.core.model.session.*
import com.lmarchery.core.model.time.SessionDateTime
import com.lmarchery.core.scoring.configured.*
import com.lmarchery.core.scoring.profiles.BuiltInTargetRegistry
import com.lmarchery.core.scoring.resolveFinalScore
import com.lmarchery.core.scoring.scoreArrowImpact
import com.lmarchery.data.*
import com.lmarchery.feature.sessions.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Clock
import java.time.ZoneId
import java.util.UUID

/** UI-thread commands. The editor owns the common gate for its entire lifetime, including uncertainty. */
class ConfiguredSessionController(
    private val repository: ConfiguredSessionRepository, private val scope: CoroutineScope,
    private val acquire: () -> Boolean, private val release: () -> Unit, private val refresh: () -> Unit,
    private val equipment: EquipmentRepository? = null, private val clock: Clock = Clock.systemUTC(),
    private val zone: () -> ZoneId = ZoneId::systemDefault, private val newUuid: () -> String = { UUID.randomUUID().toString() },
    private val onSaved: () -> Unit = {},
) {
    private val mutableState = MutableStateFlow(ConfiguredEditorState())
    val state = mutableState.asStateFlow()
    private var ui: ConfiguredEditorState
        get() = mutableState.value
        set(value) { mutableState.value = value }
    private var serial = 0L
    private var aimSerial = 0L
    private var ownsGate = false
    private fun epoch() = ++serial

    fun create() {
        if (ui.open || !acquire()) return
        ownsGate = true
        val ticket = epoch()
        ui = ConfiguredEditorState(open = true, form = ConfiguredForm.Creation(ticket), epoch = ticket)
        loadEquipment(ticket)
    }
    fun open(uuid: String) {
        if (ui.open || !acquire()) return
        ownsGate = true; ui = ConfiguredEditorState(open = true, epoch = epoch())
        operation { when (val read = repository.readConfiguredSession(uuid)) {
            is ConfiguredSessionReadResult.Found -> ui = ui.copy(saved = read.session, selectedEndUuid = firstEnd(read.session))
            else -> readProblem(read)
        } }
    }
    private fun firstEnd(s: ConfiguredSession) = s.ends.sortedBy { it.end.index }.firstOrNull { it.end.status == EndStatus.EDITING }?.uuid
        ?: s.ends.maxByOrNull { it.end.index }?.uuid
    private fun loadEquipment(generation: Long) {
        val repo = equipment ?: return
        scope.launch {
            val choices = loadEquipmentChoices(repo)
            if (ui.open && ui.form?.token == generation) ui = ui.copy(equipmentChoices = choices)
        }
    }
    fun selectEnd(uuid: String) {
        if (ui.canNavigate && ui.saved?.ends?.any { it.uuid == uuid } == true)
            ui = ui.copy(selectedEndUuid = uuid, epoch = epoch(), problem = null)
    }
    fun nextEnd() {
        if (!ui.canForm || !ui.sessionActive) return
        val source = ui.saved!!.ends.maxByOrNull { it.end.index }?.configuration ?: ui.saved!!.initialConfiguration
        ui = ui.copy(form = ConfiguredForm.NextEnd(epoch(), ConfigurationDraft.from(source)), epoch = serial, problem = null)
    }
    fun editMetadata() {
        if (!ui.canForm) return
        val ticket = epoch()
        ui = ui.copy(form = ConfiguredForm.Metadata(ticket, ConfiguredMetadataDraft.from(ui.saved!!.metadata)), epoch = ticket, problem = null)
        loadEquipment(ticket)
    }
    fun cancelForm(token: Long) {
        if (ui.ready && ui.form?.token == token) ui = ui.copy(form = null, epoch = epoch(), problem = null)
    }
    private fun form(token: Long, change: (ConfiguredForm) -> ConfiguredForm) {
        if (ui.ready && ui.form?.token == token) ui = ui.copy(form = change(ui.form!!), problem = null)
    }
    private fun configuration(token: Long, change: (ConfigurationDraft) -> ConfigurationDraft) = form(token) {
        when (it) {
            is ConfiguredForm.Creation -> it.copy(configuration = change(it.configuration))
            is ConfiguredForm.NextEnd -> if (it.explicit) it.copy(configuration = change(it.configuration)) else it
            else -> it
        }
    }
    private fun metadata(token: Long, change: (ConfiguredMetadataDraft) -> ConfiguredMetadataDraft) = form(token) {
        when (it) {
            is ConfiguredForm.Creation -> it.copy(metadata = change(it.metadata))
            is ConfiguredForm.Metadata -> it.copy(metadata = change(it.metadata))
            else -> it
        }
    }
    fun chooseProfile(token: Long, uuid: String) {
        val profile = BuiltInTargetRegistry.find(uuid) ?: return // No free-form profile/edition fabrication.
        configuration(token) { it.copy(profile = profile) }
    }
    fun distance(token: Long, value: String) = configuration(token) { it.copy(distance = value) }
    fun count(token: Long, value: String) = configuration(token) { it.copy(arrowCountMode = ArrowCountMode.FIXED, fixedCount = value) }
    fun shortcut(token: Long, value: EndArrowCountShortcut) = configuration(token) {
        it.copy(arrowCountMode = value.arrowCountMode, fixedCount = value.arrowCountPerEnd?.toString() ?: it.fixedCount)
    }
    fun indoor(token: Long, value: Boolean) = configuration(token) { it.copy(indoor = value) }
    fun scoring(token: Long, value: ScoringMode) = configuration(token) { it.copy(scoringMode = value) }
    fun explicitNext(token: Long, value: Boolean) = form(token) { if (it is ConfiguredForm.NextEnd) it.copy(explicit = value) else it }
    fun title(token: Long, value: String) = metadata(token) { it.copy(title = value) }
    fun planned(token: Long, value: String) = metadata(token) { it.copy(plannedEnds = value) }
    fun location(token: Long, value: String) = metadata(token) { it.copy(location = value) }
    fun notes(token: Long, value: String) = metadata(token) { it.copy(notes = value) }
    fun chooseEquipment(token: Long, kind: EquipmentKind, uuid: String?) {
        if (!ui.ready || ui.form?.token != token) return
        val original = ui.saved?.metadata?.let { if (kind == EquipmentKind.BOW) it.bowUuid else it.arrowSetUuid }
        if (!ui.equipmentChoices.allows(kind, uuid, original)) { invalid("Référence de matériel indisponible ou du mauvais type."); return }
        metadata(token) { it.copy(equipment = it.equipment.with(kind, uuid)) }
    }
    fun confirmForm(token: Long) {
        val value = ui.form?.takeIf { ui.ready && it.token == token } ?: return
        propose {
            val now = clock.instant()
            when (value) {
                is ConfiguredForm.Creation -> {
                    val config = value.configuration.configuration()
                    val metadata = value.metadata.metadata(SessionDateTime.fromInstant(now, zone()))
                    val proposed = ConfiguredSession.create(newUuid(), metadata, config, now)
                    submit("Créer le brouillon configurable", ConfiguredWriteRequest.Create(proposed), null)
                }
                is ConfiguredForm.NextEnd -> {
                    val previous = ui.saved!!
                    val choice = if (value.explicit) NextEndConfigurationChoice.Replace(value.configuration.configuration()) else NextEndConfigurationChoice.Inherit
                    val endUuid = newUuid()
                    submit("Créer la volée suivante", ConfiguredWriteRequest.Update(previous, previous.createNextEnd(endUuid, now, choice)), endUuid)
                }
                is ConfiguredForm.Metadata -> {
                    val previous = ui.saved!!
                    submit("Modifier les métadonnées", ConfiguredWriteRequest.Update(previous,
                        previous.withMetadata(value.metadata.metadata(previous.metadata.date), now)), ui.selectedEndUuid)
                }
            }
        }
    }
    fun start() {
        if (ui.canForm && ui.saved?.status == SessionStatus.DRAFT) revise("Démarrer la séance") { it.start(clock.instant()) }
    }
    fun validateEnd() {
        if (ui.canNavigate && ui.editableEnd) revise("Valider la volée") { it.validateEnd(ui.selectedEnd!!.uuid, clock.instant()) }
    }
    fun complete() {
        if (ui.canForm && ui.sessionActive) revise("Clôturer la séance") { it.complete(clock.instant()) }
    }
    private fun revise(label: String, change: (ConfiguredSession) -> ConfiguredSession) = propose {
        val previous = ui.saved ?: return@propose
        submit(label, ConfiguredWriteRequest.Update(previous, change(previous)), ui.selectedEndUuid)
    }
    private fun current(context: ConfiguredTargetContext?) = context != null && context == ui.targetContext
    fun tapped(context: ConfiguredTargetContext, point: NormalizedPoint) {
        if (!current(context) || ui.precision) return
        if (ui.edit?.proposal is ImpactProposal.Reposition) repositionPoint(context, point)
        else if (ui.canAdd) add(point)
    }
    private fun add(point: NormalizedPoint) = revise("Enregistrer la nouvelle flèche") { session ->
        val captured = ui.selectedEnd!!
        val index = Math.addExact(captured.end.impacts.maxOfOrNull { it.index } ?: 0, 1)
        val now = clock.instant()
        val arrow = scoreArrowImpact(newUuid(), index, captured.configuration.targetType, point, captured.configuration.scoringMode, createdAt = now)
        session.withImpact(captured.uuid, arrow)
    }
    fun precision(context: ConfiguredTargetContext, enabled: Boolean) {
        if (!current(context) || !ui.editableEnd || ui.position != null ||
            (ui.edit != null && ui.edit?.proposal !is ImpactProposal.Reposition)) return
        ui = ui.copy(precision = enabled, activeAim = null, epoch = epoch())
    }
    fun beginAim(context: ConfiguredTargetContext, point: NormalizedPoint): Long? {
        if (!current(context) || !ui.canAim || ui.activeAim != null) return null
        val candidate = position(context, point) ?: return null
        val token = ++aimSerial
        ui = ui.copy(activeAim = ActiveAim(token, candidate)); return token
    }
    private fun position(context: ConfiguredTargetContext, point: NormalizedPoint): PositionCandidate? = try {
        val c = ui.selectedEnd!!.configuration
        PositionCandidate(context.owner, point, resolveFinalScore(c.targetType, point, c.scoringMode))
    } catch (e: IllegalArgumentException) { invalid(e.message ?: "Position invalide."); null }
    private fun active(token: Long) = ui.activeAim?.takeIf { it.token == token && ui.canAim && it.candidate.context == ui.targetContext?.owner }
    fun moveAim(token: Long, point: NormalizedPoint) {
        val old = active(token) ?: return
        position(ui.targetContext!!, point)?.let { ui = ui.copy(activeAim = old.copy(candidate = it)) }
    }
    fun finishAim(token: Long, point: NormalizedPoint) {
        val old = active(token) ?: return
        val context = ui.targetContext!!
        val candidate = position(context, point) ?: return
        ui = ui.copy(activeAim = null)
        if (old.candidate.context.impactUuid == null) ui = ui.copy(position = candidate, epoch = epoch())
        else repositionPoint(context, point)
    }
    fun cancelAim(token: Long) { if (ui.activeAim?.token == token) ui = ui.copy(activeAim = null) }
    fun cancelPosition(context: ConfiguredTargetContext) {
        if (current(context) && ui.ready) ui = ui.copy(position = null, activeAim = null, epoch = epoch())
    }
    fun confirmPosition(context: ConfiguredTargetContext) {
        if (current(context) && ui.canConfirmPosition && ui.position!!.context == context.owner) add(ui.position!!.point)
    }
    fun selectImpact(context: ConfiguredTargetContext, uuid: String) {
        if (!current(context) || !ui.canSelectImpact || ui.selectedEnd!!.end.impacts.none { it.uuid == uuid }) return
        ui = ui.copy(edit = ImpactEdit(ui.saved!!.uuid, ui.selectedEnd!!.uuid, uuid), epoch = epoch(), problem = null)
    }
    fun closeSelection(context: ConfiguredTargetContext) {
        if (current(context) && ui.ready) ui = ui.copy(edit = null, activeAim = null, epoch = epoch(), problem = null)
    }
    fun beginReposition(context: ConfiguredTargetContext) {
        if (!current(context) || !ui.canSelectImpact || ui.selectedImpact == null) return
        ui = ui.copy(edit = ui.edit!!.copy(phase = ImpactEditPhase.AWAITING_POINT, proposal = ImpactProposal.Reposition()), epoch = epoch(), problem = null)
    }
    fun repositionPoint(context: ConfiguredTargetContext, point: NormalizedPoint) {
        if (!current(context) || !ui.canReposition || ui.activeAim != null) return
        val edit = ui.edit!!; val previous = edit.proposal as ImpactProposal.Reposition
        ui = ui.copy(edit = edit.copy(phase = ImpactEditPhase.AWAITING_POINT, proposal = previous.copy(point = null, preview = null)))
        if (point == ui.selectedImpact!!.point) { invalid("Choisissez une position différente."); return }
        position(context, point)?.let { ui = ui.copy(edit = edit.copy(phase = ImpactEditPhase.CANDIDATE,
            proposal = previous.copy(point = point, preview = it.preview)), epoch = epoch()) }
    }
    fun repositionReason(context: ConfiguredTargetContext, text: String) {
        if (current(context) && ui.canReposition) ui = ui.copy(edit = ui.edit!!.copy(proposal =
            (ui.edit!!.proposal as ImpactProposal.Reposition).copy(reason = text)), problem = null)
    }
    fun confirmReposition(context: ConfiguredTargetContext) {
        if (!current(context) || !ui.canConfirmReposition) return
        val edit = ui.edit!!
        revise("Repositionner la flèche") { it.repositionImpact(edit.endUuid, edit.impactUuid, edit.candidate!!, clock.instant(), edit.reason.ifBlank { null }) }
    }
    fun beginDecision(context: ConfiguredTargetContext) {
        if (!current(context) || !ui.canSelectImpact || ui.selectedImpact == null) return
        ui = ui.copy(edit = ui.edit!!.copy(phase = ImpactEditPhase.CANDIDATE, proposal = ImpactProposal.BoundaryJudgment()), epoch = epoch(), problem = null)
    }
    fun exceptional(context: ConfiguredTargetContext, enabled: Boolean) {
        if (current(context) && ui.canScoreDecision) ui = ui.copy(edit = ui.edit!!.copy(proposal =
            if (enabled) ImpactProposal.ExceptionalCorrection() else ImpactProposal.BoundaryJudgment()), epoch = epoch(), problem = null)
    }
    fun showBoundaries(context: ConfiguredTargetContext) {
        if (current(context) && ui.canScoreDecision) (ui.edit!!.proposal as? ImpactProposal.BoundaryJudgment)?.let {
            ui = ui.copy(edit = ui.edit!!.copy(proposal = it.copy(showOtherBoundaries = true)))
        }
    }
    fun boundary(context: ConfiguredTargetContext, boundary: BoundaryReference) {
        if (!current(context) || !ui.canScoreDecision) return
        val p = ui.edit!!.proposal as? ImpactProposal.BoundaryJudgment ?: return
        val choices = ui.scoreOptions!!.nearby + if (p.showOtherBoundaries) ui.scoreOptions!!.other else emptyList()
        // An invalid replacement must not leave an older score choice confirmable.
        ui = ui.copy(edit = ui.edit!!.copy(proposal = p.copy(boundary = null, selected = null)))
        if (choices.none { it.reference == boundary }) { invalid("Limite absente des choix de ce blason."); return }
        ui = ui.copy(edit = ui.edit!!.copy(proposal = p.copy(boundary = boundary, selected = null)), problem = null)
    }
    fun scoreValue(context: ConfiguredTargetContext, value: BoundaryValue) {
        if (!current(context) || !ui.canScoreDecision) return
        val p = ui.edit!!.proposal
        when (p) {
            is ImpactProposal.BoundaryJudgment -> {
                val b = ui.selectedBoundary
                ui = ui.copy(edit = ui.edit!!.copy(proposal = p.copy(selected = null)))
                if (b == null || value != b.inner && value != b.outer) { invalid("Choisissez un côté de la limite réelle."); return }
                ui = ui.copy(edit = ui.edit!!.copy(proposal = p.copy(selected = value)), problem = null)
            }
            is ImpactProposal.ExceptionalCorrection -> {
                ui = ui.copy(edit = ui.edit!!.copy(proposal = p.copy(selected = null)))
                if (value !in ui.scoreOptions!!.legalValues) { invalid("Valeur incompatible avec ce blason."); return }
                ui = ui.copy(edit = ui.edit!!.copy(proposal = p.copy(selected = value)), problem = null)
            }
            else -> Unit
        }
    }
    fun decisionReason(context: ConfiguredTargetContext, reason: String) {
        if (current(context) && ui.canScoreDecision) (ui.edit!!.proposal as? ImpactProposal.ExceptionalCorrection)?.let {
            ui = ui.copy(edit = ui.edit!!.copy(proposal = it.copy(reason = reason)), problem = null)
        }
    }
    fun confirmDecision(context: ConfiguredTargetContext) {
        if (!current(context) || !ui.canConfirmScore) return
        val edit = ui.edit!!; val p = edit.proposal
        revise("Enregistrer la décision de score") { s ->
            val now = clock.instant()
            val request = when (p) {
                is ImpactProposal.BoundaryJudgment -> ScoreDecisionRequest.JudgeBoundary(p.boundary!!, p.selected, now)
                is ImpactProposal.ExceptionalCorrection -> ScoreDecisionRequest.ExceptionalCorrection(p.selected, p.reason, now)
                else -> error("Incomplete decision")
            }
            s.decideImpact(edit.endUuid, edit.impactUuid, request, now)
        }
    }
    private fun submit(label: String, request: ConfiguredWriteRequest, end: String?) {
        if (!ui.ready) return
        if (request is ConfiguredWriteRequest.Update) validateConfiguredSessionUpdate(request.expected, request.proposed)
        val pending = PendingConfiguredWrite(label, request, ConfiguredWriteContext(end, ui.form, ui.edit, ui.position, ui.targetContext))
        ui = ui.copy(pending = pending, problem = null, confirmation = null, reconciliation = null, compared = null, lastResult = null)
        operation { write(pending) }
    }
    private suspend fun write(pending: PendingConfiguredWrite) {
        val request = pending.request
        val result = when (request) {
            is ConfiguredWriteRequest.Create -> repository.createConfiguredSession(request.proposed)
            is ConfiguredWriteRequest.Update -> repository.updateConfiguredSession(request.expected, request.proposed)
        }
        check(result.request == request) { "Repository acknowledged a different intent." }
        currentCoroutineContext().ensureActive()
        ui = ui.copy(lastResult = result)
        when (result) {
            is ConfiguredWriteResult.Created, is ConfiguredWriteResult.Updated, is ConfiguredWriteResult.AlreadyApplied ->
                accepted(request.proposed, pending, if (result is ConfiguredWriteResult.AlreadyApplied) "Déjà appliqué intégralement ; aucune écriture." else "Enregistrement confirmé sur cet appareil.")
            is ConfiguredWriteResult.Conflict -> ui = ui.copy(problem = ConfiguredProblem(ConfiguredProblemKind.valueOf(result.kind.name),
                "${result.diagnostic.path} : ${result.diagnostic.detail}"))
            is ConfiguredWriteResult.NeedsIntervention -> ui = ui.copy(problem = ConfiguredProblem(
                if (result.commitOutcome == ConfiguredCommitOutcome.UNCONFIRMED) ConfiguredProblemKind.UNCONFIRMED else ConfiguredProblemKind.INTERVENTION,
                "${result.diagnostic.path} : ${result.diagnostic.detail}"))
            is ConfiguredWriteResult.TechnicalFailure -> ui = ui.copy(problem = ConfiguredProblem(ConfiguredProblemKind.TECHNICAL,
                "Échec technique avant DML par cet appel. Intention exacte conservée ; relisez avant toute nouvelle confirmation."))
            is ConfiguredWriteResult.CommitUnconfirmed -> uncertain("Commit potentiellement effectué ; acquittement indisponible. Aucune répétition automatique.")
        }
    }
    private fun accepted(saved: ConfiguredSession, pending: PendingConfiguredWrite, message: String) {
        ui = ui.copy(saved = saved, pending = null, form = null, compared = null, reconciliation = null, problem = null,
            selectedEndUuid = pending.context.selectedEndUuid ?: firstEnd(saved), position = null, activeAim = null,
            edit = pending.context.edit?.copy(phase = ImpactEditPhase.CONFIRMED, proposal = ImpactProposal.Selection),
            epoch = epoch(), confirmation = message)
        try { onSaved() } catch (_: Exception) { ui = ui.copy(confirmation = "$message Actualisation secondaire impossible ; commit confirmé.") }
    }
    fun reconcile() {
        val pending = ui.pending?.takeIf { !ui.busy } ?: return
        operation {
            when (val read = repository.readConfiguredSession(pending.request.proposed.uuid)) {
                is ConfiguredSessionReadResult.Found -> when {
                    read.session == pending.request.proposed -> accepted(read.session, pending, "Proposition intégrale retrouvée en lecture ; aucune nouvelle écriture.")
                    pending.request is ConfiguredWriteRequest.Update && read.session == pending.request.expected -> ui = ui.copy(compared = read.session,
                        reconciliation = ConfiguredReconciliation.EXPECTED, problem = ConfiguredProblem(ConfiguredProblemKind.REVISION,
                            "État expected intégral retrouvé. Vous pouvez confirmer à nouveau la même intention, sans la reconstruire."))
                    else -> ui = ui.copy(compared = read.session, reconciliation = ConfiguredReconciliation.THIRD_STATE,
                        problem = ConfiguredProblem(ConfiguredProblemKind.REVISION, "Troisième état enregistré : aucun écrasement ni rebase. Intention conservée."))
                }
                ConfiguredSessionReadResult.NotFound -> if (pending.request is ConfiguredWriteRequest.Create) ui = ui.copy(reconciliation = ConfiguredReconciliation.ABSENT,
                    problem = ConfiguredProblem(ConfiguredProblemKind.NOT_FOUND, "Aucune séance sous cet UUID. Une confirmation explicite de la même création est possible."))
                    else ui = ui.copy(reconciliation = ConfiguredReconciliation.THIRD_STATE,
                        problem = ConfiguredProblem(ConfiguredProblemKind.NOT_FOUND, "Séance attendue absente ; aucune recréation automatique."))
                else -> { ui = ui.copy(reconciliation = null); readProblem(read) }
            }
        }
    }
    fun retryExact() {
        if (!ui.canRetryExact) return
        val pending = ui.pending!!
        ui = ui.copy(reconciliation = null, problem = null)
        operation { write(pending) }
    }
    fun close() {
        if (!ui.open || !ui.canNavigate) return
        ui = ConfiguredEditorState(epoch = epoch())
        if (ownsGate) { ownsGate = false; release() }
        try { refresh() } catch (_: Exception) { /* Never reinterpret a known commit as uncertain. */ }
    }
    /** UI must warn that this only discards local unsubmitted values. */
    fun abandonLocalAndClose() {
        if (!ui.ready) return
        ui = ui.copy(form = null, edit = null, position = null, activeAim = null, epoch = epoch()); close()
    }
    /** UI must separately warn that this does NOT roll back a possibly committed write. */
    fun abandonSubmittedAndClose() {
        if (ui.pending == null || ui.busy) return
        ui = ui.copy(pending = null, form = null, edit = null, position = null, activeAim = null, epoch = epoch()); close()
    }
    private fun propose(block: () -> Unit) {
        try { block() } catch (e: IllegalArgumentException) { invalid(e.message ?: "Transition invalide.") }
        catch (_: ArithmeticException) { invalid("Dépassement numérique : aucune écriture demandée.") }
    }
    private fun invalid(message: String) { ui = ui.copy(problem = ConfiguredProblem(ConfiguredProblemKind.VALIDATION, message)) }
    private fun uncertain(message: String) { ui = ui.copy(problem = ConfiguredProblem(ConfiguredProblemKind.UNCONFIRMED, message), reconciliation = null) }
    private fun operation(block: suspend () -> Unit) {
        if (ui.busy) return
        ui = ui.copy(busy = true)
        scope.launch {
            try { block() }
            catch (e: ConfiguredWriteCancelled) {
                if (e.commitOutcome == ConfiguredCommitOutcome.UNCONFIRMED) uncertain("Écriture interrompue ; résultat non confirmé.")
                else ui = ui.copy(problem = ConfiguredProblem(ConfiguredProblemKind.TECHNICAL, "Interruption avant DML ; intention conservée."))
                throw e
            } catch (e: CancellationException) {
                if (ui.pending != null) uncertain("Interruption sans preuve de rollback ; intention exacte conservée.")
                else ui = ui.copy(problem = ConfiguredProblem(ConfiguredProblemKind.TECHNICAL, "Lecture interrompue."))
                throw e
            } catch (e: Exception) {
                if (ui.pending != null) uncertain("Écriture non confirmée : ${e.javaClass.simpleName}. Intention conservée.")
                else ui = ui.copy(problem = ConfiguredProblem(ConfiguredProblemKind.TECHNICAL, "Lecture impossible."))
            } finally { ui = ui.copy(busy = false) }
        }
    }
    private fun readProblem(read: ConfiguredSessionReadResult) {
        ui = ui.copy(problem = when (read) {
            ConfiguredSessionReadResult.LegacyHomogeneous -> ConfiguredProblem(ConfiguredProblemKind.NOT_CONFIGURED, "Séance historique : utilisez exclusivement son éditeur historique.")
            ConfiguredSessionReadResult.NotFound -> ConfiguredProblem(ConfiguredProblemKind.NOT_FOUND, "Séance introuvable.")
            is ConfiguredSessionReadResult.IdentityOccupied -> ConfiguredProblem(ConfiguredProblemKind.IDENTITY_OCCUPIED, read.diagnostic.detail)
            is ConfiguredSessionReadResult.NeedsIntervention -> ConfiguredProblem(ConfiguredProblemKind.INTERVENTION, "${read.diagnostic.path} : ${read.diagnostic.detail}")
            is ConfiguredSessionReadResult.TechnicalFailure -> ConfiguredProblem(ConfiguredProblemKind.TECHNICAL, "Relecture impossible ; état précédent non démontré.")
            is ConfiguredSessionReadResult.Found -> error("Expected failed read")
        })
    }
}

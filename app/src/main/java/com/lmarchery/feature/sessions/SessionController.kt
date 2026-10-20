package com.lmarchery.feature.sessions

import com.lmarchery.core.database.*
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.BoundaryReference
import com.lmarchery.core.model.scoring.BoundaryValue
import com.lmarchery.core.model.scoring.ScoreDecisionRequest
import com.lmarchery.core.model.session.*
import com.lmarchery.core.model.time.SessionDateTime
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.core.scoring.profiles.BuiltInTargetRegistry
import com.lmarchery.core.scoring.scoreArrowImpact
import com.lmarchery.core.scoring.resolveFinalScore
import com.lmarchery.data.SessionRepository
import com.lmarchery.data.EquipmentRepository
import com.lmarchery.data.EquipmentKind
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** UI-thread entry points; one in-flight operation, no queued duplicate taps or automatic retries. */
class SessionController(
    private val repository: SessionRepository,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
    private val sessionZone: () -> ZoneId = ZoneId::systemDefault,
    private val newUuid: () -> String = { UUID.randomUUID().toString() },
    private val equipmentRepository: EquipmentRepository? = null,
) {
    private var nextAimToken = 0L
    private val mutableState = MutableStateFlow(SessionUiState())
    val state = mutableState.asStateFlow()
    private var ui: SessionUiState
        get() = mutableState.value
        set(value) { mutableState.value = value }

    private var creationEquipmentLoad: Job? = null
    private var equipmentLoadGeneration = 0

    fun acquireExport(): Boolean {
        if (!ui.ready || ui.impactEdit != null || ui.equipmentEdit != null || ui.hasAimProposal) return false
        ui = ui.copy(archiveOperationLocked = true)
        return true
    }
    fun releaseExport() = releaseArchiveOperation()
    fun acquireImport(): Boolean = ui.screen == SessionScreen.LIST && acquireExport()
    fun releaseArchiveOperation() { ui = ui.copy(archiveOperationLocked = false) }

    fun refresh() {
        if (!ui.ready || ui.screen != SessionScreen.LIST) return
        launchOperation { loadEntries() }
    }
    private suspend fun loadEntries() {
        when (val discovery = repository.discover()) {
            is SessionDiscoveryResult.TechnicalFailure -> ui = ui.copy(problem = technical(discovery.cause))
            is SessionDiscoveryResult.Found -> {
                val entries = discovery.uuids.map { uuid ->
                    when (val result = repository.read(uuid)) {
                        is SessionReadResult.Found -> SessionEntry(uuid, result.session)
                        else -> SessionEntry(uuid, problem = readProblem(result))
                    }
                }
                ui = ui.copy(entries = entries)
            }
        }
    }
    fun configure() {
        if (!ui.canNavigate) return
        ui = ui.copy(screen = SessionScreen.CREATE, problem = null, confirmation = null,
            creationEquipment = EquipmentSelection(), equipmentChoices = EquipmentChoices())
        val generation = ++equipmentLoadGeneration
        creationEquipmentLoad?.cancel()
        if (equipmentRepository != null) {
            ui = ui.copy(equipmentChoicesLoading = true)
            creationEquipmentLoad = scope.launch {
                try {
                    val choices = loadEquipmentChoices(equipmentRepository)
                    currentCoroutineContext().ensureActive()
                    if (generation == equipmentLoadGeneration && ui.screen == SessionScreen.CREATE)
                        ui = ui.copy(equipmentChoices = choices)
                } finally {
                    if (generation == equipmentLoadGeneration) ui = ui.copy(equipmentChoicesLoading = false)
                }
            }
        }
    }
    fun chooseCreationEquipment(kind: EquipmentKind, uuid: String?) {
        if (!ui.ready || ui.screen != SessionScreen.CREATE) return
        if (!ui.equipmentChoices.allows(kind, uuid, null)) { invalidAction("Choisissez une fiche disponible du bon type, ou aucun matériel."); return }
        ui = ui.copy(creationEquipment = ui.creationEquipment.with(kind, uuid), problem = null)
    }
    fun beginEquipmentEdit() {
        if (!ui.canEditEquipment) return
        ui = ui.copy(equipmentEdit = EquipmentSelection(ui.session!!.bowUuid, ui.session!!.arrowSetUuid),
            equipmentChoices = EquipmentChoices(), problem = null, confirmation = null)
        launchOperation { ui = ui.copy(equipmentChoices = equipmentRepository?.let { loadEquipmentChoices(it) }
            ?: EquipmentChoices(loaded = true, diagnostics = listOf("Catalogue indisponible. Les références existantes restent conservées."))) }
    }
    fun chooseEquipment(kind: EquipmentKind, uuid: String?) {
        val selection = ui.equipmentEdit ?: return
        if (!ui.ready) return
        val existing = if (kind == EquipmentKind.BOW) ui.session!!.bowUuid else ui.session!!.arrowSetUuid
        if (!ui.equipmentChoices.allows(kind, uuid, existing)) { invalidAction("Choisissez une fiche disponible du bon type, la référence d’origine ou aucun matériel."); return }
        ui = ui.copy(equipmentEdit = selection.with(kind, uuid), problem = null)
    }
    fun cancelEquipmentEdit() {
        if (!ui.busy && ui.pending == null) ui = ui.copy(equipmentEdit = null, problem = null)
    }
    fun confirmEquipment() {
        val selection = ui.equipmentEdit ?: return
        if (!ui.ready || ui.screen != SessionScreen.SESSION || ui.impactEdit != null) return
        if (selection == EquipmentSelection(ui.session!!.bowUuid, ui.session!!.arrowSetUuid)) {
            ui = ui.copy(equipmentEdit = null, problem = null, confirmation = "Associations inchangées ; aucune écriture."); return
        }
        mutate("Modifier uniquement le matériel associé") { session, now ->
            session.withEquipmentReferences(selection.bowUuid, selection.arrowSetUuid, now) to ui.displayedEnd?.uuid
        }
    }
    fun selectProfile(profile: IndoorProfile) { if (ui.ready) ui = ui.copy(selectedProfile = profile) }
    fun setTitle(title: String) { if (ui.ready) ui = ui.copy(title = title) }
    fun selectEnd(uuid: String) {
        if (ui.canNavigate && ui.session?.ends?.any { it.uuid == uuid } == true) ui = ui.copy(selectedEndUuid = uuid)
    }
    fun createSession() {
        if (!ui.ready || ui.screen != SessionScreen.CREATE) return
        creationEquipmentLoad?.cancel()
        launchOperation {
            val now = clock.instant()
            val target = ui.selectedProfile.target
            val first = End(newUuid(), 1, EndStatus.EDITING, createdAt = now, updatedAt = now)
            val draft = Session(newUuid(), SessionDateTime.fromInstant(now, sessionZone()), SessionStatus.DRAFT,
                title = ui.title.trim().ifBlank { "Séance salle — ${ui.selectedProfile.label}" }, targetType = target,
                distanceM = target.defaultDistanceM, arrowCountMode = ArrowCountMode.FIXED, arrowCountPerEnd = 3,
                isIndoor = true, scoringMode = ScoringMode.TRAINING, bowUuid = ui.creationEquipment.bowUuid,
                arrowSetUuid = ui.creationEquipment.arrowSetUuid, createdAt = now, updatedAt = now)
            val started = draft.start(now).withEnd(first)
            val profile = requireNotNull(BuiltInTargetRegistry.find(target.uuid))
            save(PendingSave("Créer et démarrer la séance", null,
                archive(started, TargetProfileSnapshot(profile.definition, profile.regulatoryEdition)), first.uuid))
        }
    }
    fun openSession(uuid: String) {
        if (!ui.canNavigate) return
        launchOperation { readSaved(uuid, abandonPending = false) }
    }
    fun returnToList() {
        if (!ui.canNavigate) return // Pending actions and selection require explicit abandonment.
        creationEquipmentLoad?.cancel()
        launchOperation {
            ui = ui.copy(screen = SessionScreen.LIST, saved = null, selectedEndUuid = null, precisionAimEnabled = false)
            loadEntries()
        }
    }
    fun discardAndReturnToList() {
        if (ui.busy || ui.archiveOperationLocked) return
        ui = ui.copy(pending = null, storedPreview = null, problem = null, impactEdit = null, equipmentEdit = null, newImpactCandidate = null, activeAim = null)
        returnToList()
    }
    fun setPrecisionAim(enabled: Boolean) {
        if (!ui.canChangeAimMode) return
        ui = ui.copy(precisionAimEnabled = enabled, activeAim = null)
    }
    fun beginPreciseAim(context: AimContext, point: NormalizedPoint): Long? {
        if (!ui.canAim || ui.activeAim != null || context != ui.aimContext) return null
        val candidate = positionCandidate(context, point) ?: return null
        val token = ++nextAimToken // Presentation token only: no domain UUID or timestamp.
        ui = ui.copy(activeAim = ActiveAim(token, candidate), problem = null)
        return token
    }
    fun movePreciseAim(token: Long, point: NormalizedPoint) {
        val active = currentAim(token) ?: return
        val candidate = positionCandidate(active.candidate.context, point) ?: return
        ui = ui.copy(activeAim = active.copy(candidate = candidate))
    }
    fun finishPreciseAim(token: Long, point: NormalizedPoint) {
        val active = currentAim(token) ?: return
        val candidate = positionCandidate(active.candidate.context, point) ?: return
        ui = ui.copy(activeAim = null)
        if (candidate.context.impactUuid == null) ui = ui.copy(newImpactCandidate = candidate)
        else proposeReposition(point) // Existing candidate, reason and confirmation path.
    }
    fun cancelPreciseAim(token: Long) {
        if (ui.activeAim?.token == token) ui = ui.copy(activeAim = null)
        // Preserve the previously released candidate when a new gesture is interrupted.
    }
    fun cancelNewImpactCandidate() {
        if (ui.ready) ui = ui.copy(newImpactCandidate = null, activeAim = null, problem = null)
    }
    fun confirmNewImpactCandidate() {
        if (!ui.canConfirmNewImpact) return
        saveNewImpact(ui.newImpactCandidate!!.point)
    }
    private fun currentAim(token: Long): ActiveAim? = ui.activeAim?.takeIf {
        it.token == token && ui.canAim && it.candidate.context == ui.aimContext
    }
    private fun positionCandidate(context: AimContext, point: NormalizedPoint): PositionCandidate? = try {
        PositionCandidate(context, point, resolveFinalScore(ui.session!!.targetType, point, ui.session!!.scoringMode))
    } catch (error: IllegalArgumentException) {
        ui = ui.copy(activeAim = null)
        invalidAction(error.message ?: "Position invalide.")
        null
    }
    fun selectImpact(endUuid: String, impactUuid: String) {
        if (!ui.canSelectImpact) return
        val end = ui.editingEnd!!
        if (end.uuid != endUuid || end.impacts.none { it.uuid == impactUuid }) {
            invalidAction("La flèche n’existe pas dans la volée en saisie affichée.")
            return
        }
        ui = ui.copy(impactEdit = ImpactEdit(ui.session!!.uuid, endUuid, impactUuid), problem = null, confirmation = null)
    }
    fun beginReposition() {
        if (!ui.canBeginReposition) return
        ui = ui.copy(impactEdit = ui.impactEdit!!.copy(phase = ImpactEditPhase.AWAITING_POINT,
            proposal = ImpactProposal.Reposition()), problem = null, confirmation = null)
    }
    fun targetTapped(point: NormalizedPoint) {
        if (ui.precisionAimEnabled) return
        if (ui.impactEdit == null) addImpact(point) else proposeReposition(point)
    }
    fun proposeReposition(point: NormalizedPoint) {
        if (!ui.canProposeReposition || ui.activeAim != null) return
        val selection = ui.impactEdit!!
        val proposal = selection.proposal as ImpactProposal.Reposition
        ui = ui.copy(impactEdit = selection.copy(phase = ImpactEditPhase.AWAITING_POINT,
            proposal = proposal.copy(point = null, preview = null)),
            problem = null)
        if (point == ui.selectedImpact!!.point) {
            invalidAction("Choisissez une position différente : la flèche est déjà à cet endroit.")
            return
        }
        try {
            val preview = resolveFinalScore(ui.session!!.targetType, point, ui.session!!.scoringMode)
            ui = ui.copy(impactEdit = selection.copy(phase = ImpactEditPhase.CANDIDATE,
                proposal = proposal.copy(point = point, preview = preview)))
        } catch (error: IllegalArgumentException) {
            invalidAction(error.message ?: "Position invalide.")
        }
    }
    fun setRepositionReason(reason: String) {
        if (ui.canProposeReposition) ui = ui.copy(impactEdit = ui.impactEdit!!.copy(
            proposal = (ui.impactEdit!!.proposal as ImpactProposal.Reposition).copy(reason = reason)), problem = null)
    }
    fun beginScoreCorrection() {
        if (!ui.canBeginScoreCorrection) return
        ui = ui.copy(impactEdit = ui.impactEdit!!.copy(phase = ImpactEditPhase.CANDIDATE,
            proposal = ImpactProposal.BoundaryJudgment()), problem = null, confirmation = null)
    }
    fun chooseBoundaryJudgment() {
        if (!ui.canProposeScoreDecision) return
        ui = ui.copy(impactEdit = ui.impactEdit!!.copy(proposal = ImpactProposal.BoundaryJudgment()), problem = null)
    }
    fun showOtherBoundaries() {
        if (!ui.canProposeScoreDecision) return
        val proposal = ui.impactEdit!!.proposal as? ImpactProposal.BoundaryJudgment ?: return
        ui = ui.copy(impactEdit = ui.impactEdit!!.copy(proposal = proposal.copy(showOtherBoundaries = true)), problem = null)
    }
    fun selectBoundary(reference: BoundaryReference) {
        if (!ui.canProposeScoreDecision) return
        val proposal = ui.impactEdit!!.proposal as? ImpactProposal.BoundaryJudgment ?: return
        if (ui.availableBoundaries.none { it.reference == reference }) {
            invalidAction("Choisissez une limite réelle parmi les limites affichées du profil.")
            return
        }
        ui = ui.copy(impactEdit = ui.impactEdit!!.copy(proposal = proposal.copy(boundary = reference, selected = null)), problem = null)
    }
    fun chooseExceptionalCorrection() {
        if (!ui.canProposeScoreDecision) return
        ui = ui.copy(impactEdit = ui.impactEdit!!.copy(proposal = ImpactProposal.ExceptionalCorrection()), problem = null)
    }
    fun selectScoreValue(value: BoundaryValue) {
        if (!ui.canProposeScoreDecision) return
        val proposal = when (val current = ui.impactEdit!!.proposal) {
            is ImpactProposal.BoundaryJudgment -> {
                val boundary = ui.selectedBoundary
                if (boundary == null || (value != boundary.inner && value != boundary.outer)) {
                    invalidAction("Choisissez d’abord une limite puis l’un de ses deux côtés réels.")
                    return
                }
                current.copy(selected = value)
            }
            is ImpactProposal.ExceptionalCorrection -> {
                if (value !in ui.scoreOptions!!.legalValues) {
                    invalidAction("Cette valeur n’est pas autorisée par le profil de cible.")
                    return
                }
                current.copy(selected = value)
            }
            else -> return
        }
        ui = ui.copy(impactEdit = ui.impactEdit!!.copy(proposal = proposal), problem = null)
    }
    fun setExceptionalReason(reason: String) {
        if (!ui.canProposeScoreDecision) return
        val proposal = ui.impactEdit!!.proposal as? ImpactProposal.ExceptionalCorrection ?: return
        ui = ui.copy(impactEdit = ui.impactEdit!!.copy(proposal = proposal.copy(reason = reason)), problem = null)
    }
    fun confirmScoreDecision() {
        if (!ui.canProposeScoreDecision) return
        if (!ui.canConfirmScoreDecision) {
            invalidAction("Choisissez une valeur et, pour une correction exceptionnelle, un motif non vide.")
            return
        }
        val selection = ui.impactEdit!!
        launchOperation {
            val expected = requireNotNull(ui.saved)
            val session = expected.sessions.single()
            require(session.uuid == selection.sessionUuid) { "La sélection appartient à une autre séance." }
            val end = session.ends.single { it.uuid == selection.endUuid }
            val impact = end.impacts.single { it.uuid == selection.impactUuid }
            val decidedAt = clock.instant()
            require(!decidedAt.isBefore(impact.updatedAt)) {
                "L’horloge est antérieure à la dernière modification de cette flèche. Décision non enregistrée."
            }
            val request = when (val proposal = selection.proposal) {
                is ImpactProposal.BoundaryJudgment -> ScoreDecisionRequest.JudgeBoundary(
                    requireNotNull(proposal.boundary), proposal.selected, decidedAt)
                is ImpactProposal.ExceptionalCorrection -> ScoreDecisionRequest.ExceptionalCorrection(
                    proposal.selected, proposal.reason.trim(), decidedAt)
                else -> error("Une décision de score explicite est nécessaire.")
            }
            val revised = impact.withFinalScore(session.targetType, request, decidedAt)
            val next = session.withEnd(end.withImpact(revised))
            validateSessionUpdate(session, next)
            val proposed = archive(next, expected.profiles.single())
            save(PendingSave("Décision de score de la flèche ${impact.index}", expected, proposed, end.uuid))
        }
    }
    /** Only an unsubmitted selection/candidate can be cancelled without the PendingSave policy. */
    fun cancelImpactEdit() {
        if (!ui.ready) return
        ui = ui.copy(impactEdit = null, activeAim = null, problem = null)
    }
    fun confirmReposition() {
        if (!ui.canProposeReposition || ui.activeAim != null || ui.impactEdit?.phase != ImpactEditPhase.CANDIDATE) return
        val selection = ui.impactEdit!!
        val original = ui.selectedImpact!!
        if (original.finalScoreDecision.provenance.isManual && selection.reason.isBlank()) {
            invalidAction("Un motif est obligatoire pour invalider une décision de score manuelle.")
            return
        }
        launchOperation {
            val expected = requireNotNull(ui.saved)
            val session = expected.sessions.single()
            require(session.uuid == selection.sessionUuid) { "La sélection appartient à une autre séance." }
            val end = session.ends.single { it.uuid == selection.endUuid }
            val impact = end.impacts.single { it.uuid == selection.impactUuid }
            val changedAt = clock.instant()
            require(!changedAt.isBefore(impact.updatedAt)) {
                "L’horloge est antérieure à la dernière modification de cette flèche. Correction non enregistrée."
            }
            val moved = impact.reposition(requireNotNull(selection.candidate), changedAt, selection.reason.trim().ifBlank { null })
            val next = session.withEnd(end.withImpact(moved))
            validateSessionUpdate(session, next)
            val proposed = archive(next, expected.profiles.single())
            ui = ui.copy(impactEdit = selection.copy(phase = ImpactEditPhase.SAVING))
            save(PendingSave("Repositionner la flèche ${impact.index}", expected, proposed, end.uuid))
        }
    }
    private fun invalidAction(message: String) {
        ui = ui.copy(problem = UiProblem(ProblemKind.INVALID_ACTION, message))
    }
    fun addImpact(point: NormalizedPoint) {
        if (!ui.canAddImpact) return
        saveNewImpact(point)
    }
    private fun saveNewImpact(point: NormalizedPoint) {
        mutate("Placer la flèche ${ui.editingEnd!!.impacts.size + 1} (${point.x}, ${point.y})") { s, now ->
            val end = requireNotNull(ui.editingEnd)
            val impact = scoreArrowImpact(newUuid(), end.impacts.size + 1, s.targetType, point, s.scoringMode, createdAt = now)
            s.withEnd(end.withImpact(impact)) to end.uuid
        }
    }
    fun validateEnd() {
        if (!ui.canValidate) return
        mutate("Valider la volée ${ui.editingEnd!!.index}") { s, now ->
            val end = requireNotNull(ui.editingEnd).validate(now)
            s.withEnd(end) to end.uuid
        }
    }
    fun newEnd() {
        if (!ui.canNewEnd) return
        mutate("Créer la volée suivante") { s, now ->
            val end = End(newUuid(), (s.ends.maxOfOrNull { it.index } ?: 0) + 1, EndStatus.EDITING,
                createdAt = now, updatedAt = now)
            s.withEnd(end) to end.uuid
        }
    }
    fun startSession() {
        if (!ui.ready || ui.equipmentEdit != null || !ui.supportedConfiguration || ui.session?.status != SessionStatus.DRAFT) return
        mutate("Démarrer la séance") { s, now -> s.start(now) to ui.displayedEnd?.uuid }
    }
    fun completeSession() {
        if (!ui.canComplete) return
        mutate("Terminer la séance") { s, now -> s.complete(now) to ui.displayedEnd?.uuid }
    }
    private fun mutate(description: String, revision: (Session, Instant) -> Pair<Session, String?>) {
        launchOperation {
            val expected = requireNotNull(ui.saved)
            val (session, endUuid) = revision(expected.sessions.single(), clock.instant())
            save(PendingSave(description, expected, archive(session, expected.profiles.single()), endUuid))
        }
    }
    fun retrySave() {
        val pending = ui.pending ?: return
        if (ui.busy || ui.problem?.retryable != true) return
        launchOperation { save(pending) } // Exact same expected, proposed, UUIDs and timestamps.
    }
    private suspend fun save(pending: PendingSave) {
        ui = ui.copy(pending = pending, confirmation = null,
            impactEdit = ui.impactEdit?.copy(phase = ImpactEditPhase.SAVING))
        var idempotent = false
        val problem: UiProblem? = if (pending.expected == null) {
            when (val result = repository.create(pending.proposed)) {
                is SessionCreateResult.Created -> null
                is SessionCreateResult.AlreadyExists -> { idempotent = true; null }
                is SessionCreateResult.Conflict -> diagnostic(ProblemKind.CONFLICT, result.diagnostic)
                is SessionCreateResult.NeedsIntervention -> diagnostic(ProblemKind.INTERVENTION, result.diagnostic)
                is SessionCreateResult.TechnicalFailure -> technical(result.cause)
            }
        } else {
            when (val result = repository.update(pending.expected, pending.proposed)) {
                is SessionUpdateResult.Updated -> null
                is SessionUpdateResult.Unchanged -> { idempotent = true; null }
                SessionUpdateResult.NotFound -> UiProblem(ProblemKind.NOT_FOUND, "Séance introuvable. Aucune recréation automatique.")
                is SessionUpdateResult.Conflict -> diagnostic(ProblemKind.CONFLICT, result.diagnostic)
                is SessionUpdateResult.NeedsIntervention -> diagnostic(ProblemKind.INTERVENTION, result.diagnostic)
                is SessionUpdateResult.TechnicalFailure -> technical(result.cause)
            }
        }
        if (problem != null) ui = ui.copy(problem = problem, impactEdit = ui.impactEdit?.copy(phase = ImpactEditPhase.FAILED))
        else ui = ui.copy(screen = SessionScreen.SESSION, saved = pending.proposed, pending = null, problem = null,
            selectedEndUuid = pending.selectedEndUuid, storedPreview = null, equipmentEdit = null,
            impactEdit = ui.impactEdit?.copy(phase = ImpactEditPhase.CONFIRMED, proposal = ImpactProposal.Selection),
            newImpactCandidate = null, activeAim = null,
            confirmation = "${pending.description} : ${if (idempotent) "déjà enregistré, contenu identique" else "enregistré sur cet appareil"}.")
    }
    fun previewStored() {
        val pending = ui.pending ?: return
        if (ui.busy) return
        launchOperation(clearProblem = false) {
            val result = repository.read(pending.proposed.sessions.single().uuid)
            if (result is SessionReadResult.Found) ui = ui.copy(storedPreview = archive(result.session, result.profile))
            else ui = ui.copy(problem = readProblem(result))
        }
    }
    fun closePreview() { if (!ui.busy) ui = ui.copy(storedPreview = null) }
    /** Call only after the UI explicitly warns that the unconfirmed local action will be abandoned. */
    fun reloadAndDiscard() {
        val pending = ui.pending ?: return
        if (ui.busy) return
        launchOperation { readSaved(pending.proposed.sessions.single().uuid, abandonPending = true) }
    }
    private suspend fun readSaved(uuid: String, abandonPending: Boolean) {
        when (val result = repository.read(uuid)) {
            is SessionReadResult.Found -> {
                val loaded = archive(result.session, result.profile)
                ui = ui.copy(screen = SessionScreen.SESSION, saved = loaded, storedPreview = null,
                    pending = if (abandonPending) null else ui.pending, problem = null, confirmation = null,
                    impactEdit = null, equipmentEdit = null, newImpactCandidate = null, activeAim = null,
                    precisionAimEnabled = if (ui.session?.uuid == result.session.uuid) ui.precisionAimEnabled else false,
                    selectedEndUuid = ui.selectedEndUuid?.takeIf { selected ->
                        ui.session?.uuid == result.session.uuid && result.session.ends.any { it.uuid == selected }
                    } ?: result.session.ends.firstOrNull { it.status == EndStatus.EDITING }?.uuid
                        ?: result.session.ends.lastOrNull()?.uuid)
            }
            else -> ui = ui.copy(problem = readProblem(result))
        }
    }
    private fun launchOperation(clearProblem: Boolean = true, block: suspend () -> Unit) {
        if (ui.busy) return
        ui = ui.copy(busy = true, problem = if (clearProblem) null else ui.problem)
        scope.launch {
            try { block() }
            catch (error: CancellationException) {
                ui = ui.copy(problem = UiProblem(ProblemKind.TECHNICAL, "Opération interrompue ; enregistrement non confirmé."),
                    impactEdit = failedImpactEdit())
                throw error
            }
            catch (error: IllegalArgumentException) { ui = ui.copy(problem = UiProblem(ProblemKind.INVALID_ACTION, error.message ?: "Action invalide."), impactEdit = failedImpactEdit()) }
            catch (error: Exception) { ui = ui.copy(problem = technical(error), impactEdit = failedImpactEdit()) }
            finally { ui = ui.copy(busy = false) }
        }
    }
    private fun failedImpactEdit() = ui.impactEdit?.let {
        if (ui.pending != null) it.copy(phase = ImpactEditPhase.FAILED) else it
    }
}

private fun archive(session: Session, profile: TargetProfileSnapshot): DomainArchive =
    when (val result = reconstructArchive(snapshotArchive(listOf(profile), listOf(session)))) {
        is ArchiveReconstruction.Accepted -> result.archive
        is ArchiveReconstruction.NeedsIntervention -> throw IllegalArgumentException("${result.issue.path}: ${result.issue.detail}")
    }
private fun diagnostic(kind: ProblemKind, value: StorageDiagnostic) = UiProblem(kind, "${value.path} : ${value.detail}")
private fun technical(error: Exception) = UiProblem(ProblemKind.TECHNICAL, "Erreur technique : ${error.message ?: error.javaClass.simpleName}")
private fun readProblem(result: SessionReadResult): UiProblem = when (result) {
    SessionReadResult.NotFound -> UiProblem(ProblemKind.NOT_FOUND, "Séance introuvable.")
    is SessionReadResult.NeedsIntervention -> diagnostic(ProblemKind.INTERVENTION, result.diagnostic)
    is SessionReadResult.TechnicalFailure -> technical(result.cause)
    is SessionReadResult.Found -> error("Expected a failed read.")
}

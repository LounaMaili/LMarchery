package com.lmarchery.feature.equipment

import com.lmarchery.core.database.*
import com.lmarchery.core.model.equipment.*
import com.lmarchery.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.Clock
import java.util.UUID

enum class EquipmentPage { LIST, DETAIL, FORM }
enum class EquipmentProblem { VALIDATION, CONFLICT, INTERVENTION, TECHNICAL, NOT_FOUND }
data class EquipmentEntry(val kind: EquipmentKind, val uuid: String, val value: Equipment? = null, val diagnostic: String? = null)
data class EquipmentRevision(val expected: Equipment?, val proposed: Equipment)
data class EquipmentUiState(val open: Boolean = false, val page: EquipmentPage = EquipmentPage.LIST,
    val busy: Boolean = false, val entries: List<EquipmentEntry> = emptyList(), val original: Equipment? = null,
    val draft: EquipmentDraft? = null, val pending: EquipmentRevision? = null, val stored: Equipment? = null, val storedDiagnostic: String? = null,
    val problem: EquipmentProblem? = null, val diagnostic: String? = null, val confirmation: String? = null) {
    val editable get() = open && !busy && pending == null
}

/** UI-thread commands; one catalog page holds the same gate as SAF/session operations. */
class EquipmentController(private val repository: EquipmentRepository, private val scope: CoroutineScope,
    private val acquire: () -> Boolean, private val release: () -> Unit,
    private val clock: Clock = Clock.systemUTC(), private val newUuid: () -> String = { UUID.randomUUID().toString() }) {
    private val mutableState = MutableStateFlow(EquipmentUiState())
    val state = mutableState.asStateFlow()
    private var ui: EquipmentUiState
        get() = mutableState.value
        set(value) { mutableState.value = value }

    fun open() {
        if (ui.open || !acquire()) return
        ui = EquipmentUiState(open = true)
        refresh()
    }
    fun close() {
        if (!ui.open || ui.busy || ui.pending != null || ui.draft != null) return
        ui = EquipmentUiState(); release()
    }
    fun refresh() {
        if (!ui.editable || ui.page != EquipmentPage.LIST) return
        operation { loadEntries() }
    }
    private suspend fun loadEntries() {
        val entries = mutableListOf<EquipmentEntry>()
        for (kind in EquipmentKind.entries) when (val result = repository.discover(kind)) {
            is EquipmentDiscoveryResult.TechnicalFailure -> { problem(EquipmentProblem.TECHNICAL, "Découverte du matériel impossible. Liste précédente conservée."); return }
            is EquipmentDiscoveryResult.Found -> for (uuid in result.uuids) {
                when (val read = repository.read(kind, uuid)) {
                    is EquipmentReadResult.Found -> entries += EquipmentEntry(kind, uuid, read.value)
                    else -> entries += EquipmentEntry(kind, uuid, diagnostic = readDiagnostic(read))
                }
            }
        }
        ui = ui.copy(entries = entries.toList())
    }
    fun show(kind: EquipmentKind, uuid: String) {
        if (!ui.editable || ui.draft != null) return
        operation { readDetail(kind, uuid) }
    }
    private suspend fun readDetail(kind: EquipmentKind, uuid: String): Boolean = when (val result = repository.read(kind, uuid)) {
        is EquipmentReadResult.Found -> {
            ui = ui.copy(page = EquipmentPage.DETAIL, original = result.value, draft = null, pending = null, stored = null, storedDiagnostic = null); true
        }
        else -> { problem(readProblem(result), readDiagnostic(result)); false }
    }
    fun list() {
        if (!ui.editable || ui.draft != null) return
        ui = ui.copy(page = EquipmentPage.LIST, original = null, stored = null, storedDiagnostic = null)
        refresh()
    }
    fun add(kind: EquipmentKind) {
        if (ui.editable && ui.page == EquipmentPage.LIST)
            ui = ui.copy(page = EquipmentPage.FORM, original = null, draft = EquipmentDraft(kind), problem = null, diagnostic = null, confirmation = null)
    }
    fun edit() {
        if (ui.editable && ui.page == EquipmentPage.DETAIL && ui.original != null)
            ui = ui.copy(page = EquipmentPage.FORM, draft = EquipmentDraft.from(ui.original!!), problem = null, diagnostic = null, confirmation = null)
    }
    fun setField(field: EquipmentField, value: String?) {
        if (!ui.editable || ui.page != EquipmentPage.FORM) return
        try { ui = ui.copy(draft = ui.draft!!.withField(field, value), problem = null, diagnostic = null) }
        catch (_: IllegalArgumentException) { problem(EquipmentProblem.VALIDATION, "Champ invalide pour cette fiche.") }
    }
    fun save() {
        if (!ui.editable || ui.page != EquipmentPage.FORM) return
        val proposed = try { ui.draft!!.revision(ui.original, clock.instant(), newUuid) }
        catch (e: IllegalArgumentException) { problem(EquipmentProblem.VALIDATION, e.message ?: "Valeurs ou chronologie invalides."); return }
        ui = ui.copy(pending = EquipmentRevision(ui.original, proposed))
        operation { writePending() }
    }
    fun retry() {
        if (ui.open && !ui.busy && ui.pending != null && ui.problem == EquipmentProblem.TECHNICAL) operation { writePending() }
    }
    private suspend fun writePending() {
        val revision = ui.pending ?: return
        if (revision.expected == null) when (val result = repository.create(revision.proposed)) {
            is EquipmentCreateResult.Created -> acknowledge(result.uuid, revision)
            is EquipmentCreateResult.AlreadyExists -> acknowledge(result.uuid, revision)
            is EquipmentCreateResult.Conflict -> problem(EquipmentProblem.CONFLICT, result.diagnostic.text())
            is EquipmentCreateResult.NeedsIntervention -> problem(EquipmentProblem.INTERVENTION, result.diagnostic.text())
            is EquipmentCreateResult.TechnicalFailure -> problem(EquipmentProblem.TECHNICAL, "Création non confirmée. Proposition et UUID conservés ; aucune relance automatique.")
        } else when (val result = repository.update(revision.expected, revision.proposed)) {
            is EquipmentUpdateResult.Updated -> acknowledge(result.uuid, revision)
            is EquipmentUpdateResult.Unchanged -> acknowledge(result.uuid, revision)
            EquipmentUpdateResult.NotFound -> problem(EquipmentProblem.NOT_FOUND, "La fiche attendue est introuvable. Aucune recréation.")
            is EquipmentUpdateResult.Conflict -> problem(EquipmentProblem.CONFLICT, result.diagnostic.text())
            is EquipmentUpdateResult.NeedsIntervention -> problem(EquipmentProblem.INTERVENTION, result.diagnostic.text())
            is EquipmentUpdateResult.TechnicalFailure -> problem(EquipmentProblem.TECHNICAL, "Révision non confirmée. État attendu et proposition conservés ; aucune relance automatique.")
        }
    }
    private fun acknowledge(uuid: String, revision: EquipmentRevision) {
        check(uuid == revision.proposed.uuid) { "Unexpected equipment acknowledgement." }
        ui = ui.copy(page = EquipmentPage.DETAIL, original = revision.proposed, draft = null, pending = null,
            stored = null, storedDiagnostic = null, confirmation = "Fiche enregistrée et confirmée.")
    }
    fun inspectStored() {
        val value = ui.pending?.proposed ?: return
        if (ui.busy) return
        operation(inspection = true) {
            when (val result = repository.read(value.kind(), value.uuid)) {
                is EquipmentReadResult.Found -> ui = ui.copy(stored = result.value, storedDiagnostic = null)
                else -> ui = ui.copy(stored = null, storedDiagnostic = readDiagnostic(result))
            }
        }
    }
    /** Explicit discard only. For existing equipment keep the proposal until a fresh read succeeds. */
    fun discardAndReload() {
        if (!ui.open || ui.busy || ui.page != EquipmentPage.FORM) return
        val original = ui.original
        operation {
            if (original == null) {
                ui = ui.copy(page = EquipmentPage.LIST, draft = null, pending = null, stored = null, storedDiagnostic = null)
                loadEntries()
            } else readDetail(original.kind(), original.uuid)
        }
    }
    private fun operation(inspection: Boolean = false, block: suspend () -> Unit) {
        if (ui.busy) return
        ui = ui.copy(busy = true, problem = if (inspection) ui.problem else null,
            diagnostic = if (inspection) ui.diagnostic else null, storedDiagnostic = null)
        scope.launch {
            try { block(); currentCoroutineContext().ensureActive() }
            catch (e: CancellationException) {
                if (inspection) ui = ui.copy(stored = null, storedDiagnostic = "Relecture interrompue. Le résultat de la sauvegarde reste inchangé.")
                else problem(EquipmentProblem.TECHNICAL, "Opération interrompue, non confirmée. Relire avant toute nouvelle tentative.")
                throw e
            }
            catch (_: Exception) {
                if (inspection) ui = ui.copy(stored = null, storedDiagnostic = "Relecture impossible. Le résultat de la sauvegarde reste inchangé.")
                else problem(EquipmentProblem.TECHNICAL, "Opération non confirmée. Saisie et proposition conservées.")
            }
            finally { ui = ui.copy(busy = false) }
        }
    }
    private fun problem(kind: EquipmentProblem, message: String) { ui = ui.copy(problem = kind, diagnostic = message) }
}

internal fun Equipment.kind() = if (this is Bow) EquipmentKind.BOW else EquipmentKind.ARROW_SET
private fun StorageDiagnostic.text() = "$path : $detail"
private fun readDiagnostic(result: EquipmentReadResult<*>): String = when (result) {
    EquipmentReadResult.NotFound -> "Fiche absente après découverte."
    is EquipmentReadResult.NeedsIntervention -> result.diagnostic.text()
    is EquipmentReadResult.TechnicalFailure -> "Lecture impossible, aucune réparation effectuée."
    is EquipmentReadResult.Found -> ""
}
private fun readProblem(result: EquipmentReadResult<*>) = when (result) {
    EquipmentReadResult.NotFound -> EquipmentProblem.NOT_FOUND
    is EquipmentReadResult.NeedsIntervention -> EquipmentProblem.INTERVENTION
    else -> EquipmentProblem.TECHNICAL
}

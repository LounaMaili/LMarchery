package com.lmarchery.feature.archiveimport

import com.lmarchery.core.database.*
import com.lmarchery.core.export.*
import com.lmarchery.core.scoring.archive.ArchiveImportPlan
import com.lmarchery.core.scoring.archive.ArchiveV2ImportPlan
import com.lmarchery.data.*
import com.lmarchery.data.archiveimport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

sealed interface ImportState {
    data object Available : ImportState
    data class Choosing(val requestId: String, val launched: Boolean = false) : ImportState
    data object Reading : ImportState
    data object Validating : ImportState
    data class Preview(val plan: ArchiveImportPlan) : ImportState
    data class PreviewV2(val plan: ArchiveV2ImportPlan) : ImportState
    data object Confirming : ImportState
    data object Importing : ImportState
    data class Conflict(val plan: ArchiveImportPlan?, val diagnostic: String) : ImportState
    data class ConflictV2(val plan: ArchiveV2ImportPlan?, val diagnostic: String) : ImportState
    data class SucceededV2(val added: ArchiveV2Counts, val identical: ArchiveV2Counts,
        val alreadyPresent: Boolean = false, val emptyArchive: Boolean = false, val refreshFailed: Boolean = false) : ImportState
    data class Succeeded(val added: Int, val identical: Int, val refreshFailed: Boolean = false) : ImportState
    data object Cancelled : ImportState
    data class Failed(val diagnostic: String) : ImportState
    data class Unconfirmed(val mayHaveCommitted: Boolean) : ImportState
}

val ImportState.active get() = this is ImportState.Choosing || this is ImportState.Reading ||
    this is ImportState.Validating || this is ImportState.Preview || this is ImportState.PreviewV2 || this is ImportState.Confirming || this is ImportState.Importing

/** UI-thread entry points; exact decoded candidate retained in memory, never reopened on confirmation. */
class ImportCoordinator(
    private val reader: ImportDocumentReader,
    private val repository: ArchiveImporter,
    private val scope: CoroutineScope,
    private val acquire: () -> Boolean,
    private val release: () -> Unit,
    private val refresh: () -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val codec: ArchiveJsonCodec = ArchiveJsonCodec(),
    private val v2Repository: ArchiveV2Importer? = null,
) {
    private val mutableState = MutableStateFlow<ImportState>(ImportState.Available)
    val state = mutableState.asStateFlow()
    private var candidate: ArchiveImportPreview? = null
    private var candidateV2: PreparedV2Import? = null
    private var selectedUri: String? = null
    private var selectedBytes: ByteArray? = null

    fun begin() {
        if (state.value.active) return
        if (!acquire()) {
            mutableState.value = ImportState.Failed("Revenez à la liste, terminez l’opération en cours, résolvez la sauvegarde non confirmée et fermez la correction avant d’importer.")
            return
        }
        candidate = null; candidateV2 = null; selectedUri = null; selectedBytes = null
        mutableState.value = ImportState.Choosing(UUID.randomUUID().toString())
    }
    fun claimSelection(): Boolean {
        val choosing = state.value as? ImportState.Choosing ?: return false
        if (choosing.launched) return false
        mutableState.value = choosing.copy(launched = true)
        return true
    }
    fun selected(uri: String?) {
        val choosing = state.value as? ImportState.Choosing
        if (choosing == null) {
            if (state.value == ImportState.Available && uri != null) reportInterrupted()
            return
        }
        if (!choosing.launched) return
        if (uri == null) { cancel(); return }
        selectedUri = uri
        mutableState.value = ImportState.Reading
        scope.launch {
            try {
                val bytes = withContext(dispatcher) { reader.read(uri) }
                selectedBytes = bytes
                mutableState.value = ImportState.Validating
                val decoded = withContext(dispatcher) { codec.decodeVersioned(bytes) }
                if (decoded is VersionedArchiveJsonRead.Rejected) {
                    finish(ImportState.Failed("${decoded.issue.code} · ${decoded.issue.path} : ${decoded.issue.detail}")); return@launch
                }
                if (decoded is VersionedArchiveJsonRead.V2) {
                    val importer = v2Repository ?: run {
                        finish(ImportState.Failed("Import V2 indisponible. Aucune conversion vers V1 ni écriture.")); return@launch
                    }
                    when (val result = withContext(dispatcher) { importer.previewImportV2(decoded.archive) }) {
                        is PreparedV2Result.Ready -> {
                            currentCoroutineContext().ensureActive()
                            if (!result.candidate.plan.canImport) finish(ImportState.ConflictV2(result.candidate.plan,
                                "Conflits V2 : aucune catégorie ne sera importée."))
                            else {
                                candidateV2 = result.candidate
                                mutableState.value = ImportState.PreviewV2(result.candidate.plan)
                            }
                        }
                        is PreparedV2Result.NeedsIntervention -> finish(ImportState.Failed("${result.diagnostic.path} : ${result.diagnostic.detail}"))
                        is PreparedV2Result.TechnicalFailure -> finish(ImportState.Failed("Prévisualisation V2 impossible. Aucune écriture demandée."))
                    }
                    return@launch
                }
                val archive = (decoded as VersionedArchiveJsonRead.V1).archive
                when (val result = withContext(dispatcher) { repository.previewImport(archive) }) {
                    is ArchiveImportPreviewResult.Ready -> {
                        currentCoroutineContext().ensureActive()
                        if (result.preview.plan.conflicts.isNotEmpty()) finish(ImportState.Conflict(result.preview.plan, "Le fichier ne peut pas être importé. Aucune écriture effectuée."))
                        else {
                            candidate = result.preview
                            mutableState.value = ImportState.Preview(result.preview.plan)
                        }
                    }
                    is ArchiveImportPreviewResult.NeedsIntervention -> finish(ImportState.Failed("${result.diagnostic.path} : ${result.diagnostic.detail}"))
                    is ArchiveImportPreviewResult.TechnicalFailure -> finish(ImportState.Failed("Prévisualisation impossible. Aucune écriture demandée."))
                }
            } catch (e: CancellationException) { finish(ImportState.Unconfirmed(false)); throw e }
            catch (e: ImportReadException) { finish(ImportState.Failed("${e.code} · document : fichier trop volumineux (16 MiB maximum).")) }
            catch (_: Exception) { finish(ImportState.Failed("Lecture ou fermeture du document impossible. Aucune écriture demandée.")) }
        }
    }
    fun confirm() {
        if (state.value is ImportState.PreviewV2) { confirmV2(); return }
        val preview = candidate ?: return
        if (state.value !is ImportState.Preview || !preview.plan.canImport) return
        mutableState.value = ImportState.Confirming
        scope.launch {
            try {
                mutableState.value = ImportState.Importing
                when (val result = withContext(dispatcher) { repository.importArchive(preview) }) {
                    is ArchiveImportResult.Imported -> {
                        currentCoroutineContext().ensureActive()
                        finish(ImportState.Succeeded(result.added, result.identical))
                        // Discovery/read failure does not undo a known commit or turn it into an uncertain write.
                        try { refresh() } catch (_: Exception) {
                            mutableState.value = ImportState.Succeeded(result.added, result.identical, refreshFailed = true)
                        }
                    }
                    is ArchiveImportResult.Conflict -> finish(ImportState.Conflict(null, "${result.diagnostic.path} : ${result.diagnostic.detail} Nouvelle sélection et prévisualisation nécessaires."))
                    is ArchiveImportResult.NeedsIntervention -> finish(ImportState.Failed("${result.diagnostic.path} : ${result.diagnostic.detail}"))
                    is ArchiveImportResult.TechnicalFailure -> finish(ImportState.Unconfirmed(true))
                }
            } catch (e: CancellationException) { finish(ImportState.Unconfirmed(true)); throw e }
            catch (_: Exception) { finish(ImportState.Unconfirmed(true)) }
        }
    }
    private fun confirmV2() {
        val preview = candidateV2 ?: return
        if (state.value !is ImportState.PreviewV2 || !preview.plan.canImport) return
        mutableState.value = ImportState.Confirming
        scope.launch {
            try {
                mutableState.value = ImportState.Importing
                val success = when (val result = withContext(dispatcher) { preview.commit() }) {
                    is ArchiveV2ImportResult.Imported -> ImportState.SucceededV2(result.added, result.identical)
                    is ArchiveV2ImportResult.AlreadyPresent -> ImportState.SucceededV2(ArchiveV2Counts(0, 0, 0, 0),
                        result.identical, alreadyPresent = true, emptyArchive = result.emptyArchive)
                    is ArchiveV2ImportResult.Conflict -> {
                        finish(ImportState.ConflictV2(null, "${result.diagnostic.path} : ${result.diagnostic.detail} Nouvelle sélection nécessaire.")); return@launch
                    }
                    is ArchiveV2ImportResult.NeedsIntervention -> {
                        finish(ImportState.Failed("${result.diagnostic.path} : ${result.diagnostic.detail}")); return@launch
                    }
                    is ArchiveV2ImportResult.TechnicalFailure -> { finish(ImportState.Unconfirmed(true)); return@launch }
                }
                currentCoroutineContext().ensureActive()
                finish(success)
                try { refresh() } catch (_: Exception) { mutableState.value = success.copy(refreshFailed = true) }
            } catch (e: CancellationException) { finish(ImportState.Unconfirmed(true)); throw e }
            catch (_: Exception) { finish(ImportState.Unconfirmed(true)) }
        }
    }
    fun cancel() {
        if (state.value is ImportState.Choosing || state.value is ImportState.Preview || state.value is ImportState.PreviewV2) finish(ImportState.Cancelled)
    }
    fun pickerUnavailable() { if (state.value is ImportState.Choosing) finish(ImportState.Failed("Sélecteur de documents indisponible.")) }
    fun reportInterrupted() {
        if (state.value == ImportState.Available) finish(ImportState.Unconfirmed(true))
    }
    private fun finish(next: ImportState) {
        candidate = null; candidateV2 = null; selectedUri = null; selectedBytes = null
        mutableState.value = next; release()
    }
}

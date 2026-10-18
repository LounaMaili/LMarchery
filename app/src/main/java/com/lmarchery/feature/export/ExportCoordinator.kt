package com.lmarchery.feature.export

import com.lmarchery.core.database.ArchiveReadResult
import com.lmarchery.core.database.ArchiveV2ReadResult
import com.lmarchery.data.ArchiveV2Source
import com.lmarchery.core.export.ArchiveJsonCodec
import com.lmarchery.core.export.ArchiveJsonException
import com.lmarchery.data.ArchiveSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

sealed interface ExportState {
    data object Available : ExportState
    data object Preparing : ExportState
    data object Validating : ExportState
    data class Choosing(val fileName: String, val requestId: String, val launched: Boolean = false) : ExportState
    data object Writing : ExportState
    data class Succeeded(val sessionCount: Int, val receipt: ExportReceipt, val schemaVersion: Int = 1,
        val profileCount: Int = 0, val bowCount: Int = 0, val arrowSetCount: Int = 0) : ExportState
    data object Cancelled : ExportState
    data class Failed(val diagnostic: String, val potentiallyIncomplete: Boolean = false) : ExportState
}

val ExportState.active get() = this is ExportState.Preparing || this is ExportState.Validating ||
    this is ExportState.Choosing || this is ExportState.Writing

data class ExportReceipt(val displayName: String?, val byteCount: Int, val sha256: String)
fun interface ExportDocumentWriter {
    /** Success requires output close and exact readback, not merely a completed write() call. */
    suspend fun writeAndVerify(destination: String, bytes: ByteArray): ExportReceipt
}

/** No Activity/URI API here; prepared bytes survive rotation in the owning ViewModel, not process death. */
class ExportCoordinator(
    private val source: ArchiveSource,
    private val writer: ExportDocumentWriter,
    private val scope: CoroutineScope,
    private val acquire: () -> Boolean,
    private val release: () -> Unit,
    private val clock: Clock = Clock.systemUTC(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val codec: ArchiveJsonCodec = ArchiveJsonCodec(),
    private val v2Source: ArchiveV2Source? = null,
) {
    private val mutableState = MutableStateFlow<ExportState>(ExportState.Available)
    val state = mutableState.asStateFlow()
    private var prepared: ByteArray? = null
    private var sessionCount = 0
    private var profileCount = 0
    private var bowCount = 0
    private var arrowSetCount = 0
    private var schemaVersion = 1

    fun begin() = beginVersion(1)
    fun beginV2() = beginVersion(2)

    private fun beginVersion(version: Int) {
        if (state.value.active) return
        if (!acquire()) {
            mutableState.value = ExportState.Failed("Terminez l’opération en cours, résolvez la sauvegarde non confirmée et fermez la correction avant d’exporter.")
            return
        }
        schemaVersion = version
        mutableState.value = ExportState.Preparing
        scope.launch {
            try {
                val bytes = if (version == 2) {
                    val archive = when (val result = withContext(dispatcher) {
                        (v2Source ?: throw ExportProblem("Collecte V2 indisponible ; aucune rétrogradation vers V1.")).readArchiveV2()
                    }) {
                        is ArchiveV2ReadResult.Found -> result.archive
                        is ArchiveV2ReadResult.NeedsIntervention -> throw ExportProblem("${result.diagnostic.path} : ${result.diagnostic.detail}")
                        is ArchiveV2ReadResult.TechnicalFailure -> throw ExportProblem("Lecture globale V2 impossible. Aucune sauvegarde préparée.")
                    }
                    sessionCount = archive.sessions.size; profileCount = archive.profiles.size
                    bowCount = archive.bows.size; arrowSetCount = archive.arrowSets.size
                    mutableState.value = ExportState.Validating
                    withContext(dispatcher) { codec.encode(archive) }
                } else {
                    val archive = when (val result = withContext(dispatcher) { source.readArchive() }) {
                        is ArchiveReadResult.Found -> result.archive
                        is ArchiveReadResult.NeedsIntervention -> throw ExportProblem("${result.diagnostic.path} : ${result.diagnostic.detail}")
                        is ArchiveReadResult.TechnicalFailure -> throw ExportProblem("Lecture globale impossible. Aucune sauvegarde préparée.")
                    }
                    if (archive.sessions.isEmpty()) throw ExportProblem("Aucune séance enregistrée à exporter.")
                    sessionCount = archive.sessions.size; profileCount = archive.profiles.size
                    bowCount = 0; arrowSetCount = 0
                    mutableState.value = ExportState.Validating
                    withContext(dispatcher) { codec.encode(archive) }
                }
                currentCoroutineContext().ensureActive()
                prepared = bytes
                val name = "lmarchery-${if (version == 2) "v2-" else ""}${DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(clock.instant())}.lmarchery.json"
                mutableState.value = ExportState.Choosing(name, UUID.randomUUID().toString())
            } catch (e: CancellationException) {
                fail("Préparation interrompue. Export non confirmé."); throw e
            } catch (e: ArchiveJsonException) { fail("${e.issue.code} · ${e.issue.path} : ${e.issue.detail}") }
            catch (e: ExportProblem) { fail(e.message!!) }
            catch (_: Exception) { fail("Préparation impossible. Export non confirmé.") }
        }
    }

    /** Claimed once before launching SAF; a recreated Activity keeps the existing registry request. */
    fun claimDestinationRequest(): String? {
        val choosing = state.value as? ExportState.Choosing ?: return null
        if (choosing.launched) return null
        mutableState.value = choosing.copy(launched = true)
        return choosing.fileName
    }

    fun destinationChosen(destination: String?) {
        val choosing = state.value as? ExportState.Choosing
        if (choosing == null) {
            if (state.value == ExportState.Available && destination != null) reportInterrupted()
            return
        }
        if (!choosing.launched) return
        if (destination == null) {
            clear(); mutableState.value = ExportState.Cancelled; release(); return
        }
        val bytes = prepared ?: run { reportInterrupted(); return }
        mutableState.value = ExportState.Writing
        scope.launch {
            try {
                val receipt = withContext(dispatcher) { writer.writeAndVerify(destination, bytes) }
                currentCoroutineContext().ensureActive()
                require(receipt.byteCount == bytes.size)
                mutableState.value = ExportState.Succeeded(sessionCount, receipt, schemaVersion, profileCount, bowCount, arrowSetCount)
                clear(); release()
            } catch (e: CancellationException) {
                fail("Écriture interrompue. Sauvegarde non confirmée.", true); throw e
            } catch (_: Exception) {
                fail("Écriture, fermeture ou relecture impossible. Sauvegarde non confirmée.", true)
            }
        }
    }
    fun pickerUnavailable() {
        if (state.value is ExportState.Choosing) fail("Sélecteur de documents indisponible. Aucun export écrit.")
    }
    fun reportInterrupted() {
        if (state.value == ExportState.Available || state.value is ExportState.Choosing)
            fail("Export interrompu par l’arrêt du processus. Aucun succès confirmé.", true)
    }
    private fun clear() { prepared = null; sessionCount = 0; profileCount = 0; bowCount = 0; arrowSetCount = 0 }
    private fun fail(message: String, incomplete: Boolean = false) {
        clear(); mutableState.value = ExportState.Failed(message, incomplete); release()
    }
}

private class ExportProblem(message: String) : Exception(message)

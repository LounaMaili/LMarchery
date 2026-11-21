package com.lmarchery.feature.weather

import com.lmarchery.core.model.weather.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Clock
import java.util.UUID

sealed interface EstimateStep {
    data object Consent : EstimateStep
    data object Fetching : EstimateStep
    data class Review(val candidate: SessionEstimate) : EstimateStep
    data class Saving(val candidate: SessionEstimate) : EstimateStep
    data class Unconfirmed(val candidate: SessionEstimate) : EstimateStep
    data class Reading(val candidate: SessionEstimate) : EstimateStep
    data class Message(val text: String) : EstimateStep
}

data class EstimateDialog(val session: String, val ticket: String, val step: EstimateStep)

/** Commands are serialized on the UI dispatcher. A consent authorizes exactly one call. */
class EstimateJournal(
    private val provider: WeatherProvider?, private val scope: CoroutineScope, private val clock: Clock,
    private val write: suspend (SessionEstimate) -> Unit,
    private val read: suspend (SessionEstimate) -> SessionEstimate?,
    private val refresh: (String) -> Unit,
) {
    val providerName = provider?.name
    private val dialog = MutableStateFlow<EstimateDialog?>(null)
    val state = dialog.asStateFlow()
    private var request: Job? = null

    fun open(session: String) {
        val active = dialog.value
        if (provider != null && (active == null || active.session != session && active.step is EstimateStep.Message))
            dialog.value = EstimateDialog(session, UUID.randomUUID().toString(), EstimateStep.Consent)
    }

    fun request(ticket: String, point: WeatherPoint, consent: Boolean) {
        val source = provider ?: return
        val form = dialog.value?.takeIf { it.ticket == ticket && it.step == EstimateStep.Consent } ?: return
        if (!consent) { close(ticket); return }
        val fetching = form.copy(step = EstimateStep.Fetching)
        dialog.value = fetching
        request = scope.launch {
            val next = try {
                val value = withTimeout(15_000) { withContext(Dispatchers.IO) { source.current(point) } }
                require(value.requested == point && value.provider == providerName && value.isCurrentAt(clock.instant()))
                EstimateStep.Review(SessionEstimate(form.ticket, form.session, value))
            } catch (_: TimeoutCancellationException) { EstimateStep.Message("Le fournisseur météo n’a pas répondu à temps.") }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { EstimateStep.Message("Estimation indisponible ou périmée. La saisie manuelle reste disponible.") }
            dialog.compareAndSet(fetching, form.copy(step = next))
        }
    }

    fun close(ticket: String) {
        val current = dialog.value?.takeIf { it.ticket == ticket } ?: return
        when (current.step) {
            is EstimateStep.Saving, is EstimateStep.Unconfirmed, is EstimateStep.Reading -> return
            else -> { dialog.value = null; request?.cancel() }
        }
    }

    fun save(ticket: String) {
        val current = dialog.value?.takeIf { it.ticket == ticket } ?: return
        val value = (current.step as? EstimateStep.Review)?.candidate ?: return
        if (!value.estimate.isCurrentAt(clock.instant())) {
            dialog.value = current.copy(step = EstimateStep.Message("Estimation périmée. Fermez cet aperçu."))
            return
        }
        dialog.value = current.copy(step = EstimateStep.Saving(value))
        scope.launch {
            try { write(value) }
            catch (e: CancellationException) { dialog.value = current.copy(step = EstimateStep.Unconfirmed(value)); throw e }
            catch (_: Exception) { dialog.value = current.copy(step = EstimateStep.Unconfirmed(value)); return@launch }
            dialog.value = current.copy(step = EstimateStep.Message("Estimation enregistrée."))
            refresh(value.sessionUuid)
        }
    }

    fun reconcile(ticket: String) {
        val current = dialog.value?.takeIf { it.ticket == ticket } ?: return
        val value = (current.step as? EstimateStep.Unconfirmed)?.candidate ?: return
        dialog.value = current.copy(step = EstimateStep.Reading(value))
        scope.launch {
            val stored = try { read(value) }
            catch (e: CancellationException) { dialog.value = current; throw e }
            catch (_: Exception) { dialog.value = current; return@launch }
            val outcome = when (stored) {
                value -> "Estimation confirmée par relecture."
                null -> "Estimation absente après relecture."
                else -> "Conflit de contenu pour cet UUID. Les données stockées sont conservées."
            }
            dialog.value = current.copy(step = EstimateStep.Message(outcome))
            refresh(value.sessionUuid)
        }
    }
}

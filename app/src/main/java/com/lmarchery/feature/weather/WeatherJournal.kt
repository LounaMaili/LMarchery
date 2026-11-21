package com.lmarchery.feature.weather

import com.lmarchery.core.database.RoomWeather
import com.lmarchery.core.model.weather.WeatherObservation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.Clock
import java.util.UUID

data class WeatherView(val session: String? = null, val rows: List<WeatherObservation> = emptyList(),
    val formId: String? = null, val busy: Boolean = false, val loaded: Boolean = false, val pending: WeatherObservation? = null,
    val estimates: List<com.lmarchery.core.model.weather.SessionEstimate> = emptyList(),
    val message: String? = null, val place: com.lmarchery.core.model.place.SessionPlace? = null)

/** ViewModel-owned. Only the explicit save command can append. Reading an uncertain UUID never retries it. */
class WeatherJournal(private val store: RoomWeather, private val scope: CoroutineScope,
    val clock: Clock = Clock.systemUTC(), provider: com.lmarchery.core.model.weather.WeatherProvider? = null,
    private val newUuid: () -> String = { UUID.randomUUID().toString() }) {
    private val mutable = MutableStateFlow(WeatherView())
    val state = mutable.asStateFlow()
    val estimates = EstimateJournal(provider, scope, clock, store::recordEstimate,
        { record -> store.details(record.sessionUuid).estimates.singleOrNull { it.uuid == record.uuid } },
        { session -> if (mutable.value.session == session) load(session) })

    fun load(session: String) {
        val previous = mutable.value
        if (previous.busy) return
        val pending = previous.pending
        mutable.value = WeatherView(session, busy = true, pending = pending)
        scope.launch {
            try {
                val outcome = pending?.let { p -> store.list(p.sessionUuid).find { it.uuid == p.uuid } }
                val details = store.details(session)
                val conflict = pending != null && outcome != null && outcome != pending
                val message = when {
                    conflict -> "UUID occupé par un relevé différent. Enregistrement bloqué."
                    pending != null && outcome == null -> "Le relevé précédent est absent après relecture."
                    pending != null -> "Relevé confirmé par relecture."
                    else -> null
                }
                mutable.value = WeatherView(session, details.observations, loaded = true, pending = pending.takeIf { conflict }, message = message, place = details.place, estimates = details.estimates)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = mutable.value.copy(busy = false, message = "Lecture des relevés impossible.") }
        }
    }

    fun openForm(session: String) {
        val view = mutable.value
        if (view.session == session && view.loaded && !view.busy && view.pending == null && view.formId == null)
            mutable.value = view.copy(formId = newUuid(), message = null)
    }

    fun dismissForm(id: String) {
        val view = mutable.value
        if (view.formId == id) mutable.value = view.copy(formId = null)
    }

    fun save(session: String, id: String, form: (String) -> WeatherObservation) {
        val view = mutable.value
        if (view.session != session || !view.loaded || view.busy || view.pending != null || view.formId != id) return
        val value = form(id)
        require(value.sessionUuid == session && value.uuid == id)
        mutable.value = view.copy(busy = true, pending = value, formId = null)
        scope.launch {
            try { store.record(value) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                mutable.value = view.copy(pending = value, formId = null, message = "Enregistrement non confirmé. Relisez les relevés.")
                return@launch
            }
            mutable.value = view.copy(rows = view.rows + value, formId = null, message = "Relevé enregistré.")
        }
    }
}

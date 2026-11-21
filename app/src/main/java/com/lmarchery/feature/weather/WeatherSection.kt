package com.lmarchery.feature.weather

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lmarchery.core.model.place.NorthReference
import com.lmarchery.core.model.weather.*
import java.time.*

internal val LocalWeatherJournal = staticCompositionLocalOf<WeatherJournal?> { null }

@Composable
internal fun WeatherSection(session: String, ends: List<Pair<String, Int>>, canRecord: Boolean) {
    val journal = LocalWeatherJournal.current ?: return
    val view by journal.state.collectAsState()
    var open by rememberSaveable(session) { mutableStateOf(false) }
    TextButton({ open = !open }) { Text(if (open) "Masquer les conditions de tir" else "Conditions de tir") }
    if (!open) return
    LaunchedEffect(session) {
        if (journal.state.value.session != session || journal.state.value.formId == null) journal.load(session)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (view.session != session || view.busy) Text("Lecture ou enregistrement en cours…")
        if (!view.busy && (view.session != session || !view.loaded || view.pending != null)) {
            TextButton({ journal.load(session) }) { Text("Relire les relevés") }
        }
        if (view.session == session) {
            view.place?.let { place ->
                Text("Lieu de la séance")
                com.lmarchery.feature.configured.AdoptedPlaceLabel(place)
            }
            view.message?.let { Text(it) }
            if (view.loaded && view.rows.isEmpty()) Text("Aucun relevé météo.")
            val numbers = ends.toMap()
            view.rows.sortedByDescending { it.observedAt }.forEach { o ->
                val scope = if (o.scope.endUuids.isEmpty()) "Séance" else "Volées " +
                    o.scope.endUuids.mapNotNull(numbers::get).sorted().joinToString()
                Text("$scope · " + o.validFrom.atZone(ZoneId.systemDefault()).toOffsetDateTime().toString() +
                    (o.validUntil?.let { " → " + it.atZone(ZoneId.systemDefault()).toOffsetDateTime() } ?: ""))
                if (o.observedAt != o.validFrom) Text("Saisi le " + o.observedAt.atZone(ZoneId.systemDefault()).toOffsetDateTime(),
                    style = MaterialTheme.typography.bodySmall)
                Text(listOfNotNull(o.meanMps?.let { "Vent $it m/s" }, o.gustMps?.let { "Rafales $it m/s" },
                    o.from?.let { "Depuis ${it.degrees}° · ${if (it.north == NorthReference.TRUE_NORTH) "nord géographique" else "nord magnétique"}" },
                    "Direction variable".takeIf { o.variableWind }, o.temperatureC?.let { "$it °C" },
                    o.rain?.let { if (it == RainReport.RAIN) "Pluie" else "Pas de pluie" }, o.precipitationMm?.let { "$it mm" }
                ).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
            }
            if (view.loaded) EstimateSection(journal.estimates, session, view.place?.site?.position, view.estimates,
                canRecord && !view.busy && view.pending == null && view.formId == null)
            if (canRecord && view.loaded && !view.busy && view.pending == null)
                OutlinedButton({ journal.openForm(session) }) { Text("Ajouter une observation manuelle") }
        }
    }
    view.formId?.takeIf { canRecord && view.session == session }?.let { id ->
        key(id) { WeatherDialog(session, id, ends, journal) { journal.dismissForm(id) } }
    }
}

@Composable
private fun WeatherDialog(session: String, formId: String, ends: List<Pair<String, Int>>, journal: WeatherJournal, close: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var mean by rememberSaveable { mutableStateOf("") }
    var gust by rememberSaveable { mutableStateOf("") }
    var degrees by rememberSaveable { mutableStateOf("") }
    var uncertainty by rememberSaveable { mutableStateOf("") }
    var magnetic by rememberSaveable { mutableStateOf(false) }
    var variable by rememberSaveable { mutableStateOf(false) }
    var temperature by rememberSaveable { mutableStateOf("") }
    var rain by rememberSaveable { mutableStateOf<RainReport?>(null) }
    var mm by rememberSaveable { mutableStateOf("") }
    var period by rememberSaveable { mutableStateOf(false) }
    var start by rememberSaveable { mutableStateOf("") }
    var end by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = close, title = { Text("Observation météo") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(if (selected.isEmpty()) "S’applique à toute la séance" else "S’applique aux volées cochées")
            for ((id, number) in ends) {
                Row { Checkbox(id in selected, { yes -> selected = if (yes) selected + id else selected - id })
                    Text("Volée $number") }
            }
            Text("Laissez vides les valeurs inconnues.")
            WeatherField("Vent moyen (m/s)", mean) { mean = it }
            WeatherField("Rafales (m/s)", gust) { gust = it }
            Row { Checkbox(variable, { variable = it }); Text("Direction variable") }
            if (!variable) {
                WeatherField("Direction d’origine (0 à moins de 360°)", degrees) { degrees = it }
                if (degrees.isNotBlank()) {
                    Row { Checkbox(magnetic, { magnetic = it }); Text(if (magnetic) "Nord magnétique" else "Nord géographique") }
                    WeatherField("Incertitude de direction (°, facultatif)", uncertainty) { uncertainty = it }
                }
            }
            WeatherField("Température (°C)", temperature) { temperature = it }
            Text("Pluie observée")
            Row {
                listOf(null to "Inconnue", RainReport.RAIN to "Oui", RainReport.NO_RAIN to "Non").forEach { (v, label) ->
                    FilterChip(rain == v, { rain = v }, { Text(label) })
                }
            }
            Row { Checkbox(period, { period = it }); Text("Préciser la date ou la période") }
            if (period) {
                Text("Date et heure avec décalage UTC, par exemple 2026-11-21T14:00+01:00.")
                WeatherField("Début", start) { start = it }
                WeatherField("Fin facultative", end) { end = it }
                if (end.isNotBlank()) WeatherField("Cumul mesuré sur cette période (mm)", mm) { mm = it }
            } else Text("Validité à l’instant de l’enregistrement.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton({
        try {
            fun number(text: String): Double? = text.trim().takeIf { it.isNotEmpty() }?.let {
                requireNotNull(it.replace(',', '.').toDoubleOrNull()?.takeIf(Double::isFinite)) { "Nombre fini requis." }
            }
            val now = journal.clock.instant()
            val from = if (period) com.lmarchery.core.model.time.UtcTimestamps.parse(start.trim()) else now
            val until = if (period && end.isNotBlank()) com.lmarchery.core.model.time.UtcTimestamps.parse(end.trim()) else null
            val bearing = if (variable) null else number(degrees)?.let { WindFrom(it,
                if (magnetic) NorthReference.MAGNETIC_NORTH else NorthReference.TRUE_NORTH, number(uncertainty)) }
            journal.save(session, formId) { id -> WeatherObservation(id, session, WeatherScope(selected), now, from, until,
                number(mean), number(gust), bearing, variable, number(temperature), rain, if (until != null) number(mm) else null) }
            close()
        } catch (_: DateTimeException) { error = "Vérifiez la date, l’heure et le décalage UTC." }
        catch (e: IllegalArgumentException) { error = e.message ?: "Valeur incorrecte." }
    }) { Text("Enregistrer le relevé") } }, dismissButton = { TextButton(close) { Text("Annuler") } })
}

@Composable
private fun WeatherField(label: String, value: String, changed: (String) -> Unit) {
    OutlinedTextField(value, changed, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true)
}

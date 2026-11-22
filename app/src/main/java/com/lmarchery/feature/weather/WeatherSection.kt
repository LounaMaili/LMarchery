package com.lmarchery.feature.weather

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.lmarchery.feature.sessions.SessionFormDialog
import com.lmarchery.core.model.place.NorthReference
import com.lmarchery.core.model.weather.*
import java.time.*

internal val LocalWeatherJournal = staticCompositionLocalOf<WeatherJournal?> { null }

@Composable
internal fun WeatherSection(session: String, ends: List<Pair<String, Int>>, canRecord: Boolean,
    showOnEntry: Boolean = false) {
    val journal = LocalWeatherJournal.current ?: return
    val view by journal.state.collectAsState()
    var open by rememberSaveable(session) { mutableStateOf(false) }
    var showHistory by rememberSaveable(session) { mutableStateOf(false) }
    if (!showOnEntry) {
        OutlinedButton({ open = !open }) { Text(if (open) "Masquer les conditions de tir" else "Conditions de tir") }
        if (!open) return
    } else Text("Conditions de tir", style = MaterialTheme.typography.titleMedium)
    LaunchedEffect(session) {
        if (journal.state.value.session != session || journal.state.value.formId == null) journal.load(session)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (view.session != session || view.busy) Text("Lecture ou enregistrement en cours…")
        if (!view.busy && (view.session != session || !view.loaded || view.pending != null)) {
            TextButton({ journal.load(session) }) { Text("Relire les relevés") }
        }
        if (view.session == session) {
            if (!showOnEntry) view.place?.let { place -> com.lmarchery.feature.configured.AdoptedPlaceLabel(place) }
            view.message?.let { Text(it) }
            if (canRecord && view.loaded && !view.busy && view.pending == null)
                Button({ journal.openForm(session) }) { Text("Ajouter un relevé météo") }
            if (view.loaded && view.rows.isEmpty()) Text("Aucun relevé météo.")
            val numbers = ends.toMap()
            val latest = view.rows.maxByOrNull { it.observedAt }
            latest?.let { observation ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(observation.scope.label(numbers), style = MaterialTheme.typography.titleSmall)
                        Text(observation.summary())
                    }
                }
                OutlinedButton({ showHistory = !showHistory }) {
                    Text(if (showHistory) "Masquer les relevés" else "Consulter les relevés (${view.rows.size})")
                }
            }
            if (showHistory) view.rows.sortedByDescending { it.observedAt }.forEach { observation ->
                val zone = ZoneId.systemDefault()
                Text(observation.scope.label(numbers), style = MaterialTheme.typography.titleSmall)
                Text(observation.summary())
                Text("Valide dès ${observation.validFrom.atZone(zone).toOffsetDateTime()}" +
                    (observation.validUntil?.let { " · jusqu’à ${it.atZone(zone).toOffsetDateTime()}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall)
                Text("Saisi manuellement le ${observation.observedAt.atZone(zone).toOffsetDateTime()}",
                    style = MaterialTheme.typography.bodySmall)
                val details = listOfNotNull(
                    observation.gustMps?.let { "Rafales $it m/s" },
                    observation.from?.let { from ->
                        val reference = if (from.north == NorthReference.TRUE_NORTH) "nord géographique" else "nord magnétique"
                        "Depuis ${from.degrees}° · $reference" +
                            (from.uncertaintyDegrees?.let { " · incertitude $it°" } ?: "")
                    },
                    "Direction variable".takeIf { observation.variableWind },
                    observation.precipitationMm?.let { "Cumul mesuré $it mm" },
                )
                if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
            }
            if (view.loaded) EstimateSection(journal.estimates, session, view.place?.site?.position, view.estimates,
                canRecord && !view.busy && view.pending == null && view.formId == null)
        }
    }
    view.formId?.takeIf { canRecord && view.session == session }?.let { id ->
        key(id) {
            WeatherObservationForm(session, id, ends, journal.clock::instant, { journal.dismissForm(id) }) { value ->
                journal.save(session, id) { value }
            }
        }
    }
}

private fun WeatherScope.label(numbers: Map<String, Int>) =
    if (endUuids.isEmpty()) "Toute la séance" else "Volées " + endUuids.mapNotNull(numbers::get).sorted().joinToString()

private fun WeatherObservation.summary() = listOf(
    temperatureC?.let { "$it °C" } ?: "Température inconnue",
    when (rain) { RainReport.RAIN -> "Pluie"; RainReport.NO_RAIN -> "Pas de pluie"; null -> "Pluie inconnue" },
    meanMps?.let { "Vent $it m/s" } ?: "Vent inconnu",
).joinToString(" · ")

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WeatherObservationForm(session: String, formId: String, ends: List<Pair<String, Int>>,
    instant: () -> Instant, close: () -> Unit, save: (WeatherObservation) -> Unit) {
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
    var advanced by rememberSaveable { mutableStateOf(false) }
    SessionFormDialog("Observation météo", close, "Enregistrer le relevé", submit = {
        try {
            fun number(text: String): Double? = text.trim().takeIf { it.isNotEmpty() }?.let {
                requireNotNull(it.replace(',', '.').toDoubleOrNull()?.takeIf(Double::isFinite)) { "Nombre fini requis." }
            }
            val now = instant()
            val from = if (period) com.lmarchery.core.model.time.UtcTimestamps.parse(start.trim()) else now
            val until = if (period && end.isNotBlank()) com.lmarchery.core.model.time.UtcTimestamps.parse(end.trim()) else null
            val bearing = if (variable) null else number(degrees)?.let { WindFrom(it,
                if (magnetic) NorthReference.MAGNETIC_NORTH else NorthReference.TRUE_NORTH, number(uncertainty)) }
            save(WeatherObservation(formId, session, WeatherScope(selected), now, from, until,
                number(mean), number(gust), bearing, variable, number(temperature), rain, if (until != null) number(mm) else null))
        } catch (_: DateTimeException) { error = "Vérifiez la date, l’heure et le décalage UTC." }
        catch (e: IllegalArgumentException) { error = e.message ?: "Valeur incorrecte." }
    }, page = advanced, navigation = {
        val keyboard = LocalFocusManager.current
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!advanced, { keyboard.clearFocus(); advanced = false }, { Text("Essentiel") })
            FilterChip(advanced, { keyboard.clearFocus(); advanced = true }, { Text("Détails") })
        }
    }) {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (!advanced) {
            Text("Laissez vides les valeurs inconnues.", style = MaterialTheme.typography.bodySmall)
            WeatherField("Température (°C)", temperature) { temperature = it }
            Text("Pluie observée", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(null to "Inconnue", RainReport.RAIN to "Oui", RainReport.NO_RAIN to "Non").forEach { (value, label) ->
                    FilterChip(rain == value, { rain = value }, { Text(label) })
                }
            }
            WeatherField("Vent moyen (m/s)", mean) { mean = it }
            if (!variable) WeatherField("Direction d’origine (°)", degrees) { degrees = it }
            else Text("Direction variable")
            Text(if (selected.isEmpty()) "Toute la séance" else "${selected.size} volée(s) sélectionnée(s)",
                style = MaterialTheme.typography.bodySmall)
        } else {
            WeatherField("Rafales (m/s)", gust) { gust = it }
            WeatherToggle("Direction variable", variable) { variable = it }
            if (!variable && degrees.isNotBlank()) {
                WeatherToggle("Nord magnétique", magnetic) { magnetic = it }
                if (!magnetic) Text("Référence actuelle · nord géographique", style = MaterialTheme.typography.bodySmall)
                WeatherField("Incertitude de direction (°)", uncertainty) { uncertainty = it }
            }
            Text("Période et cumul", style = MaterialTheme.typography.titleSmall)
            WeatherToggle("Préciser la date ou la période", period) { period = it }
            if (period) {
                Text("Date et heure avec décalage UTC, par exemple 2026-11-21T14:00+01:00.")
                WeatherField("Début", start, decimal = false) { start = it }
                WeatherField("Fin facultative", end, decimal = false) { end = it }
                if (end.isNotBlank()) WeatherField("Cumul mesuré (mm)", mm) { mm = it }
            }
            Text("Volées concernées", style = MaterialTheme.typography.titleSmall)
            Text(if (selected.isEmpty()) "Toute la séance" else "Uniquement les volées cochées")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ends.forEach { (id, index) ->
                    FilterChip(id in selected,
                        { selected = if (id in selected) selected - id else selected + id },
                        { Text("Volée $index") })
                }
            }
        }
    }
}

@Composable
private fun WeatherToggle(label: String, checked: Boolean, changed: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Checkbox, onValueChange = changed),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null)
        Text(label)
    }
}

@Composable
private fun WeatherField(label: String, value: String, decimal: Boolean = true, changed: (String) -> Unit) {
    OutlinedTextField(value, changed, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Text))
}

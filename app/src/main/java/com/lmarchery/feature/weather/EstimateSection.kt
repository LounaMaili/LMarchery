package com.lmarchery.feature.weather

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import com.lmarchery.core.model.place.GeoPosition
import com.lmarchery.core.model.weather.*
import java.time.ZoneId

@Composable
internal fun EstimateSection(journal: EstimateJournal, session: String, position: GeoPosition?,
    saved: List<SessionEstimate>, canRecord: Boolean) {
    saved.sortedByDescending { it.estimate.validAt }.forEach {
        EstimateLabel(it.estimate)
        HorizontalDivider()
    }
    val current by journal.state.collectAsState()
    val provider = journal.providerName
    if (canRecord && provider == null) {
        Text("Estimations en ligne indisponibles dans cette version.", style = MaterialTheme.typography.bodySmall)
    } else if (canRecord && (current == null || current?.session != session && current?.step is EstimateStep.Message)) {
        OutlinedButton({ journal.open(session) }) { Text("Récupérer une estimation météo") }
    }
    val form = current ?: return
    val step = form.step
    if (form.session != session) {
        if (step is EstimateStep.Unconfirmed) TextButton({ journal.reconcile(form.ticket) }) { Text("Relire l’enregistrement précédent") }
        return
    }
    val closable = step !is EstimateStep.Saving && step !is EstimateStep.Unconfirmed && step !is EstimateStep.Reading
    val close = { journal.close(form.ticket) }
    DisposableEffect(form.ticket) { onDispose { journal.close(form.ticket) } }
    if (!canRecord && (step is EstimateStep.Consent || step is EstimateStep.Fetching || step is EstimateStep.Review)) {
        LaunchedEffect(form.ticket) { close() }
        return
    }
    var lat by rememberSaveable(form.ticket) { mutableStateOf(position?.latitude?.toString().orEmpty()) }
    var lon by rememberSaveable(form.ticket) { mutableStateOf(position?.longitude?.toString().orEmpty()) }
    val point = runCatching { WeatherPoint(lat.trim().replace(',', '.').toDouble(), lon.trim().replace(',', '.').toDouble()) }.getOrNull()
    AlertDialog(onDismissRequest = close, title = { Text("Estimation météo") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (step) {
                EstimateStep.Consent -> {
                    Text("Pour cette demande uniquement, $provider recevra les coordonnées ci-dessous.")
                    if (position == null) {
                        OutlinedTextField(lat, { lat = it }, label = { Text("Latitude en degrés") }, singleLine = true)
                        OutlinedTextField(lon, { lon = it }, label = { Text("Longitude en degrés") }, singleLine = true)
                    } else Text("Lieu de la séance · latitude $lat°, longitude $lon°")
                }
                EstimateStep.Fetching -> Text("Demande en cours auprès de $provider…")
                is EstimateStep.Review -> EstimateLabel(step.candidate.estimate)
                is EstimateStep.Saving -> Text("Enregistrement en cours…")
                is EstimateStep.Unconfirmed -> Text("Enregistrement non confirmé. Relisez les données avant toute nouvelle demande.")
                is EstimateStep.Reading -> Text("Relecture en cours…")
                is EstimateStep.Message -> Text(step.text)
            }
        }
    }, confirmButton = {
        when (step) {
            EstimateStep.Consent -> TextButton({ point?.let { journal.request(form.ticket, it, consent = true) } },
                enabled = point != null) { Text("Autoriser et demander") }
            is EstimateStep.Review -> TextButton({ journal.save(form.ticket) }) { Text("Conserver l’estimation") }
            is EstimateStep.Unconfirmed -> TextButton({ journal.reconcile(form.ticket) }) { Text("Relire") }
            is EstimateStep.Message -> TextButton(close) { Text("Fermer") }
            else -> Unit
        }
    }, dismissButton = {
        if (closable && step !is EstimateStep.Message) TextButton(close) { Text(if (step is EstimateStep.Review) "Ignorer" else "Annuler") }
    })
}

@Composable
private fun EstimateLabel(value: WeatherEstimate) {
    val kind = if (value.kind == EstimateKind.FORECAST) "Prévision" else "Estimation de modèle"
    fun date(t: java.time.Instant) = t.atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()
    Text("$kind · ${value.provider}", style = MaterialTheme.typography.titleSmall)
    Text("Valable le ${date(value.validAt)} · récupérée le ${date(value.retrievedAt)}")
    value.issuedAt?.let { Text("Émise le ${date(it)}") }
    value.model?.let { Text("Modèle : $it") }
    val unknown = "indisponible"
    Text("Vent : ${value.windMps?.let { "$it m/s" } ?: unknown} · rafales : ${value.gustMps?.let { "$it m/s" } ?: unknown}")
    Text("Origine du vent : ${value.fromTrueDegrees?.let { "$it° du nord géographique" } ?: unknown}")
    Text("Température : ${value.temperatureC?.let { "$it °C" } ?: unknown}")
    Text("Précipitations estimées : ${value.precipitationMm?.let { "$it mm sur les ${value.intervalSeconds} secondes précédentes" } ?: unknown}")
    if (value.provider == "Open-Meteo") {
        val attribution = androidx.compose.ui.text.buildAnnotatedString {
            withLink(androidx.compose.ui.text.LinkAnnotation.Url("https://open-meteo.com/")) { append("Données météo Open-Meteo") }
            append(" · ")
            withLink(androidx.compose.ui.text.LinkAnnotation.Url("https://creativecommons.org/licenses/by/4.0/")) { append("CC BY 4.0") }
        }
        Text(attribution, style = MaterialTheme.typography.bodySmall)
    }
}

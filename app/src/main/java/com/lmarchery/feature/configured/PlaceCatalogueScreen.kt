package com.lmarchery.feature.configured

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lmarchery.core.model.place.ShootingLine
import java.util.UUID

@Composable
internal fun PlaceCatalogueScreen(form: ConfiguredForm.PlaceCatalogue, state: ConfiguredEditorState,
    controller: ConfiguredSessionController) {
    var siteUuid by rememberSaveable(form.token) { mutableStateOf<String?>(null) }
    var lineUuid by rememberSaveable(form.token) { mutableStateOf<String?>(null) }
    var editingSite by rememberSaveable(form.token) { mutableStateOf(false) }
    var editingLine by rememberSaveable(form.token) { mutableStateOf(false) }
    val catalogue = state.catalogue
    val site = catalogue?.first?.firstOrNull { it.uuid == siteUuid }
    val lines = catalogue?.second.orEmpty().filter { it.siteUuid == site?.uuid }.sortedBy { it.name }
    val back = {
        if (state.ready) {
            if (siteUuid != null) siteUuid = null else controller.closePlaceCatalogue(form.token)
        }
    }
    BackHandler(onBack = back)
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        OutlinedButton(back, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), enabled = state.ready) {
            Text(if (siteUuid == null) "‹ Accueil" else "‹ Mes lieux de tir")
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(site?.name ?: "Mes lieux de tir", style = MaterialTheme.typography.headlineSmall) }
            if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.problem?.let { problem -> item { Text(problem.message, color = MaterialTheme.colorScheme.error) } }
            if (catalogue == null) {
                if (state.ready) item {
                    OutlinedButton({ controller.loadPlaces(form.token) }) { Text("Relire les lieux") }
                }
            } else if (site == null) {
                item {
                    Button({ siteUuid = null; editingSite = true }, enabled = state.ready) { Text("Ajouter un lieu") }
                    if (catalogue.first.isEmpty()) Text("Aucun lieu enregistré.")
                }
                items(catalogue.first.sortedBy { it.name }, key = { it.uuid }) { place ->
                    OutlinedButton({ siteUuid = place.uuid }, Modifier.fillMaxWidth(), enabled = state.ready) {
                        Text(place.name, Modifier.weight(1f))
                        Text("›")
                    }
                }
            } else {
                item {
                    site.description?.let { Text(it) }
                    Text("Coordonnées", style = MaterialTheme.typography.titleMedium)
                    val position = site.position
                    if (position == null) Text("Non renseignées")
                    else {
                        Text("Latitude · ${position.latitude}°")
                        Text("Longitude · ${position.longitude}°")
                        position.accuracyMetres?.let { Text("Précision · $it m") }
                    }
                    OutlinedButton({ editingSite = true }, enabled = state.ready) { Text("Modifier le lieu") }
                }
                item {
                    Text("Lignes de tir", style = MaterialTheme.typography.titleMedium)
                    Button({ lineUuid = null; editingLine = true }, enabled = state.ready) { Text("Ajouter une ligne") }
                }
                items(lines, key = { it.uuid }) { line ->
                    OutlinedButton({ lineUuid = line.uuid; editingLine = true }, Modifier.fillMaxWidth(), enabled = state.ready) {
                        Column(Modifier.weight(1f)) {
                            Text(line.name + if (line.active) "" else " · Désactivée")
                            Text(line.orientation.label, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Modifier")
                    }
                }
            }
        }
    }
    if (editingSite) key(site?.uuid) {
        SiteDialog(site, { editingSite = false }) { proposed ->
            controller.saveSite(form.token, site, proposed)
            siteUuid = proposed.uuid
            editingSite = false
        }
    }
    if (editingLine && site != null) {
        val line = lines.firstOrNull { it.uuid == lineUuid }
        key(lineUuid) {
            BearingDialog(line, line?.orientation, false, { editingLine = false }) { name, desc, angle, north, spread, active, reading ->
                val orientation = controller.catalogueOrientation(angle, north, spread, line?.orientation, reading)
                controller.saveLine(form.token, line, ShootingLine(line?.uuid ?: UUID.randomUUID().toString(),
                    site.uuid, name, desc, orientation, active))
                editingLine = false
            }
        }
    }
}

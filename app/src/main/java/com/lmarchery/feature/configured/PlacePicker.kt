package com.lmarchery.feature.configured

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lmarchery.core.model.place.*
import java.util.UUID

private val NorthReference.label get() = if (this == NorthReference.TRUE_NORTH) "nord géographique" else "nord magnétique"
private val LineOrientation?.label get() = this?.let { "${it.degrees}° · ${it.reference.label}" + if (it.reading == null) "" else " · boussole confirmée" } ?: "Orientation non renseignée"

@Composable
internal fun AdoptedPlaceLabel(value: SessionPlace) {
    Text(listOfNotNull(value.site.name, value.line?.name, value.line?.let { value.orientation.label }).joinToString(" · "))
}

private enum class PlaceEditor { SITE, LINE, SESSION }

@Composable
internal fun PlacePicker(form: ConfiguredForm.Creation, state: ConfiguredEditorState, c: ConfiguredSessionController) {
    var expanded by rememberSaveable(form.token) { mutableStateOf(false) }
    var editor by remember(form.token) { mutableStateOf<PlaceEditor?>(null) }
    var site by remember(form.token) { mutableStateOf<ShootingSite?>(null) }
    var line by remember(form.token) { mutableStateOf<ShootingLine?>(null) }
    TextButton({
        expanded = !expanded
        if (expanded) c.loadPlaces(form.token)
    }, enabled = state.ready) { Text(form.site?.let { "Lieu · ${it.name}" } ?: "Lieu et ligne de tir (facultatif)") }
    if (!expanded) return
    val catalogue = state.catalogue
    if (catalogue == null) {
        if (state.busy) Text("Lecture du catalogue…")
        else TextButton({ c.loadPlaces(form.token) }) { Text("Relire les lieux") }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ChoiceMenu("Lieu", form.site?.name ?: "Aucun", state.ready) { close ->
            DropdownMenuItem({ Text("Aucun lieu") }, { c.choosePlace(form.token, null, null); close() })
            catalogue.first.sortedBy { it.name }.forEach { choice ->
                DropdownMenuItem({ Text(choice.name) }, { c.choosePlace(form.token, choice, null); close() })
            }
        }
        TextButton({ site = null; editor = PlaceEditor.SITE }, enabled = state.ready) { Text("Créer un lieu favori") }
        form.site?.let { selected ->
            selected.description?.let { Text(it) }
            TextButton({ site = selected; editor = PlaceEditor.SITE }, enabled = state.ready) { Text("Modifier le lieu") }
            val lines = catalogue.second.filter { it.siteUuid == selected.uuid }
            ChoiceMenu("Ligne", form.line?.name ?: "Aucune", state.ready) { close ->
                DropdownMenuItem({ Text("Aucune ligne") }, { c.choosePlace(form.token, selected, null); close() })
                lines.sortedBy { it.name }.forEach { choice ->
                    DropdownMenuItem({ Text(choice.name + if (choice.active) "" else " (désactivée)") },
                        { c.choosePlace(form.token, selected, choice); close() }, enabled = choice.active)
                }
            }
            TextButton({ line = null; editor = PlaceEditor.LINE }, enabled = state.ready) { Text("Ajouter une ligne") }
            form.line?.let { selectedLine ->
                Text(form.orientation.label)
                TextButton({ line = selectedLine; editor = PlaceEditor.SESSION }, enabled = state.ready) { Text("Orientation pour cette séance") }
                TextButton({ line = selectedLine; editor = PlaceEditor.LINE }, enabled = state.ready) { Text("Modifier ou désactiver la ligne") }
            }
        }
    }
    when (editor) {
        PlaceEditor.SITE -> SiteDialog(site, { editor = null }) { proposed ->
            c.saveSite(form.token, site, proposed)
            editor = null
        }
        PlaceEditor.LINE, PlaceEditor.SESSION -> form.site?.let { owner ->
            val sessionOnly = editor == PlaceEditor.SESSION
            key(form.token, owner.uuid, line?.uuid, sessionOnly) {
                BearingDialog(line, if (sessionOnly) form.orientation else line?.orientation, sessionOnly, { editor = null }) { name, desc, angle, ref, uncertainty, active, reading ->
                    if (sessionOnly) c.changeOrientation(form.token, requireNotNull(line), angle, ref, uncertainty, reading)
                    else {
                        val previous = line
                        val orientation = c.catalogueOrientation(angle, ref, uncertainty, previous?.orientation, reading)
                        c.saveLine(form.token, previous, ShootingLine(previous?.uuid ?: UUID.randomUUID().toString(), owner.uuid,
                            name, desc, orientation, active))
                    }
                    editor = null
                }
            }
        }
        null -> Unit
    }
}

@Composable
private fun ChoiceMenu(label: String, selected: String, enabled: Boolean, items: @Composable (() -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ open = true }, enabled = enabled) { Text("$label · $selected") }
        DropdownMenu(open, { open = false }) { items { open = false } }
    }
}

private fun optionalNumber(value: String): Double? = value.trim().takeIf { it.isNotEmpty() }?.let {
    requireNotNull(it.replace(',', '.').toDoubleOrNull()?.takeIf(Double::isFinite)) { "Nombre fini requis." }
}

@Composable
private fun SiteDialog(previous: ShootingSite?, dismiss: () -> Unit, save: (ShootingSite) -> Unit) {
    var name by remember { mutableStateOf(previous?.name.orEmpty()) }
    var description by remember { mutableStateOf(previous?.description.orEmpty()) }
    var lat by remember { mutableStateOf(previous?.position?.latitude?.toString().orEmpty()) }
    var lon by remember { mutableStateOf(previous?.position?.longitude?.toString().orEmpty()) }
    var accuracy by remember { mutableStateOf(previous?.position?.accuracyMetres?.toString().orEmpty()) }
    PlaceDialog("Lieu favori", dismiss, {
        TextField(name, { name = it }, label = { Text("Nom") })
        TextField(description, { description = it }, label = { Text("Description facultative") })
        Text("Position facultative, incluse dans une sauvegarde V5.")
        TextField(lat, { lat = it }, label = { Text("Latitude WGS84 (°)") })
        TextField(lon, { lon = it }, label = { Text("Longitude WGS84 (°)") })
        TextField(accuracy, { accuracy = it }, label = { Text("Précision horizontale (m)") })
    }) {
        val latitude = optionalNumber(lat)
        val longitude = optionalNumber(lon)
        val metres = optionalNumber(accuracy)
        val position = if (latitude == null && longitude == null && metres == null) null else {
            require(latitude != null && longitude != null && latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
                (metres == null || metres >= 0)) { "Vérifiez la paire latitude/longitude et la précision positive ou nulle." }
            val data = GeoPosition(latitude, if (longitude == 180.0) -180.0 else longitude, ManualOrigin.MANUAL, metres)
            previous?.position?.takeIf { it.copy(origin = ManualOrigin.MANUAL) == data } ?: data
        }
        save(ShootingSite(previous?.uuid ?: UUID.randomUUID().toString(), name.trim(), description.trim().ifBlank { null }, position))
    }
}

@Composable
private fun BearingDialog(line: ShootingLine?, initial: LineOrientation?, sessionOnly: Boolean, dismiss: () -> Unit,
    save: (String, String?, Double?, NorthReference, Double?, Boolean, CompassReading?) -> Unit) {
    var name by remember { mutableStateOf(line?.name.orEmpty()) }
    var desc by remember { mutableStateOf(line?.description.orEmpty()) }
    var angle by remember { mutableStateOf(initial?.degrees?.toString().orEmpty()) }
    var north by remember { mutableStateOf(initial?.reference ?: NorthReference.TRUE_NORTH) }
    var uncertainty by remember { mutableStateOf(initial?.uncertaintyDegrees?.toString().orEmpty()) }
    var reading by remember { mutableStateOf(initial?.reading) }
    var measuring by remember { mutableStateOf(false) }
    val survey = rememberCompassSurvey()
    var active by remember { mutableStateOf(line?.active ?: true) }
    PlaceDialog(if (sessionOnly) "Orientation de la séance" else "Ligne de tir", dismiss, {
        if (!sessionOnly) {
            TextField(name, { name = it }, label = { Text("Nom") })
            TextField(desc, { desc = it }, label = { Text("Description facultative") })
            if (line != null) Row { Checkbox(active, { active = it }); Text("Ligne utilisable") }
        }
        TextField(angle, { angle = it }, label = { Text("Azimut vers les cibles (°), facultatif") })
        if (line != null && survey.available) {
            Text("Avant la séance, depuis un emplacement autorisé, orientez le haut de l’écran dans le sens de la ligne.")
            TextButton({ measuring = true }) { Text("Mesurer avec le téléphone") }
        } else if (line != null) Text("Boussole indisponible sur cet appareil.")
        if (reading != null) {
            Text("Mesure magnétique brute : ${reading?.magneticDegrees}°. La valeur conservée peut être corrigée.")
            TextButton({ reading = null; angle = ""; uncertainty = "" }) { Text("Saisir une orientation indépendante") }
        } else if (angle.isNotBlank()) {
            ChoiceMenu("Référence", north.label, true) { close -> NorthReference.entries.forEach { ref ->
                DropdownMenuItem({ Text(ref.label) }, { north = ref; close() })
            } }
            TextField(uncertainty, { uncertainty = it }, label = { Text("Incertitude angulaire (°)") })
        }
    }) {
        val degrees = optionalNumber(angle)
        val spread = if (degrees != null) optionalNumber(uncertainty) else null
        require(spread == null || spread in 0.0..180.0) { "L’incertitude doit être comprise entre 0 et 180°." }
        require(reading == null || degrees != null) { "Indiquez la valeur retenue ou revenez à la saisie indépendante." }
        save(name.trim(), desc.trim().ifBlank { null }, degrees, north, spread, active, reading)
    }
    if (measuring) CompassMeasureDialog(survey, { measuring = false }) { candidate ->
        reading = candidate
        angle = candidate.magneticDegrees.toString()
        north = NorthReference.MAGNETIC_NORTH
        uncertainty = ""
        measuring = false
    }
}

@Composable
private fun PlaceDialog(title: String, dismiss: () -> Unit, fields: @Composable ColumnScope.() -> Unit, confirm: () -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            fields()
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton({
        try { confirm() } catch (e: IllegalArgumentException) { error = e.message ?: "Valeur incorrecte." }
    }) { Text("Enregistrer") } }, dismissButton = { TextButton(dismiss) { Text("Annuler") } })
}

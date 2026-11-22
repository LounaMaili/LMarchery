package com.lmarchery.feature.configured

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.lmarchery.feature.sessions.SessionFormDialog
import com.lmarchery.core.model.place.*
import java.util.UUID

private val NorthReference.label get() = if (this == NorthReference.TRUE_NORTH) "nord géographique" else "nord magnétique"
internal val LineOrientation?.label get() = this?.let { "${it.degrees}° · ${it.reference.label}" + if (it.reading == null) "" else " · boussole confirmée" } ?: "Orientation non renseignée"

@Composable
internal fun AdoptedPlaceLabel(value: SessionPlace) {
    Text(listOfNotNull(value.site.name, value.line?.name, value.line?.let { value.orientation.label }).joinToString(" · "))
}

@Composable
internal fun PlacePicker(form: ConfiguredForm.Creation, state: ConfiguredEditorState, c: ConfiguredSessionController) {
    var editingOrientation by rememberSaveable(form.token) { mutableStateOf(false) }
    LaunchedEffect(form.token) { if (state.catalogue == null) c.loadPlaces(form.token) }
    Text("Lieu et ligne de tir", style = MaterialTheme.typography.titleMedium)
    val catalogue = state.catalogue
    if (catalogue == null) {
        if (state.busy) Text("Lecture des lieux…")
        else OutlinedButton({ c.loadPlaces(form.token) }) { Text("Relire les lieux") }
        return
    }
    if (catalogue.first.isEmpty()) Text("Aucun lieu favori. Vous pouvez en ajouter depuis « Mes lieux de tir » à l’accueil.")
    ChoiceMenu("Lieu", form.site?.name ?: "Aucun lieu", state.ready) { close ->
        DropdownMenuItem({ Text("Continuer sans lieu") }, { c.choosePlace(form.token, null, null); close() })
        catalogue.first.sortedBy { it.name }.forEach { choice ->
            DropdownMenuItem({ Text(choice.name) }, { c.choosePlace(form.token, choice, null); close() })
        }
    }
    form.site?.let { selected ->
        val lines = catalogue.second.filter { it.siteUuid == selected.uuid && it.active }.sortedBy { it.name }
        ChoiceMenu("Ligne", form.line?.name ?: "Aucune ligne", state.ready) { close ->
            DropdownMenuItem({ Text("Sans ligne") }, { c.choosePlace(form.token, selected, null); close() })
            lines.forEach { choice ->
                DropdownMenuItem({ Text(choice.name) }, { c.choosePlace(form.token, selected, choice); close() })
            }
        }
    }
    form.line?.let { line ->
        Text("Orientation retenue", style = MaterialTheme.typography.titleSmall)
        Text(form.orientation.label)
        OutlinedButton({ editingOrientation = true }, enabled = state.ready) { Text("Modifier pour cette séance") }
        if (editingOrientation) key(line.uuid) {
            BearingDialog(line, form.orientation, true, { editingOrientation = false }) { _, _, angle, ref, uncertainty, _, reading ->
                c.changeOrientation(form.token, line, angle, ref, uncertainty, reading)
                editingOrientation = false
            }
        }
    }
}

@Composable
private fun ChoiceMenu(label: String, selected: String, enabled: Boolean, items: @Composable (() -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ open = true }, Modifier.fillMaxWidth(), enabled = enabled) { Text("$label · $selected") }
        DropdownMenu(open, { open = false }) { items { open = false } }
    }
}

private fun optionalNumber(value: String): Double? = value.trim().takeIf { it.isNotEmpty() }?.let {
    requireNotNull(it.replace(',', '.').toDoubleOrNull()?.takeIf(Double::isFinite)) { "Nombre fini requis." }
}

@Composable
internal fun SiteDialog(previous: ShootingSite?, dismiss: () -> Unit, save: (ShootingSite) -> Unit) {
    var name by rememberSaveable { mutableStateOf(previous?.name.orEmpty()) }
    var description by rememberSaveable { mutableStateOf(previous?.description.orEmpty()) }
    var lat by rememberSaveable { mutableStateOf(previous?.position?.latitude?.toString().orEmpty()) }
    var lon by rememberSaveable { mutableStateOf(previous?.position?.longitude?.toString().orEmpty()) }
    var accuracy by rememberSaveable { mutableStateOf(previous?.position?.accuracyMetres?.toString().orEmpty()) }
    var coordinates by rememberSaveable { mutableStateOf(previous?.position != null) }
    PlaceDialog(if (previous == null) "Nouveau lieu" else "Modifier le lieu", dismiss, {
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Nom") }, singleLine = true)
        OutlinedButton({ coordinates = !coordinates }, Modifier.fillMaxWidth()) {
            Text(if (coordinates) "Masquer les coordonnées" else "Coordonnées (facultatives)")
        }
        if (coordinates) {
            Text("Position incluse dans les sauvegardes des lieux.", style = MaterialTheme.typography.bodySmall)
            CoordinateField("Latitude WGS84 (°)", lat) { lat = it }
            CoordinateField("Longitude WGS84 (°)", lon) { lon = it }
            CoordinateField("Précision horizontale (m)", accuracy) { accuracy = it }
        }
        OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), label = { Text("Description facultative") })
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
internal fun BearingDialog(line: ShootingLine?, initial: LineOrientation?, sessionOnly: Boolean, dismiss: () -> Unit,
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
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Nom") }, singleLine = true)
            OutlinedTextField(desc, { desc = it }, Modifier.fillMaxWidth(), label = { Text("Description facultative") })
            if (line != null) FilterChip(active, { active = !active }, {
                Text(if (active) "Ligne active" else "Ligne désactivée")
            })
        }
        CoordinateField("Azimut vers les cibles (°), facultatif", angle) { angle = it }
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
            CoordinateField("Incertitude angulaire (°)", uncertainty) { uncertainty = it }
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
private fun CoordinateField(label: String, value: String, update: (String) -> Unit) {
    OutlinedTextField(value, update, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}

@Composable
private fun PlaceDialog(title: String, dismiss: () -> Unit, fields: @Composable ColumnScope.() -> Unit, confirm: () -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    SessionFormDialog(title, dismiss, "Enregistrer", submit = {
        try { confirm() } catch (e: IllegalArgumentException) { error = e.message ?: "Valeur incorrecte." }
    }) {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        fields()
    }
}

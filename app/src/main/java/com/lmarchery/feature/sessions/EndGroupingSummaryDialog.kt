package com.lmarchery.feature.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lmarchery.core.model.session.EndStatus
import com.lmarchery.core.scoring.grouping.GroupingMissPolicy
import com.lmarchery.core.scoring.grouping.GroupingUnit

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EndGroupingSummaryDialog(source: EndGroupingSummarySource, close: () -> Unit) {
    val end = source.end
    var includeMisses by rememberSaveable(end.uuid) { mutableStateOf(true) }
    var unit by rememberSaveable(end.uuid) { mutableStateOf(GroupingUnit.NORMALIZED) }
    val policy = if (includeMisses) GroupingMissPolicy.INCLUDE_MISSES else GroupingMissPolicy.EXCLUDE_MISSES
    val reader = remember(end.uuid, end.index, end.status, end.activeImpacts, source.configuration) {
        EndGroupingSummaryReader(source)
    }
    val result by key(reader, policy) {
        // A previous end or policy must not be displayed while its replacement is being read.
        produceState<Result<EndGroupingSummary>?>(initialValue = null) {
            value = try {
                Result.success(reader.read(policy))
            } catch (failure: IllegalArgumentException) {
                Result.failure(failure)
            }
        }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp)) {
                TextButton(onClick = close) { Text("‹ Retour à la volée") }
                HorizontalDivider()
                Column(
                    Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Bilan de la volée ${end.index}", style = MaterialTheme.typography.headlineSmall)
                    Text(if (end.status == EndStatus.VALIDATED) "Volée validée" else "Volée provisoire")
                    Text("${source.configuration.targetType.name} · ${source.configuration.distanceM} m")
                    Row(
                        Modifier.fillMaxWidth().toggleable(includeMisses, role = Role.Switch,
                            onValueChange = { includeMisses = it }).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Inclure les M dans le groupement", Modifier.weight(1f))
                        Switch(checked = includeMisses, onCheckedChange = null)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(unit == GroupingUnit.NORMALIZED, { unit = GroupingUnit.NORMALIZED }, { Text("Normalisé") })
                        FilterChip(unit == GroupingUnit.CENTIMETERS, { unit = GroupingUnit.CENTIMETERS }, { Text("cm") })
                        FilterChip(unit == GroupingUnit.MILLIMETERS, { unit = GroupingUnit.MILLIMETERS }, { Text("mm") })
                    }
                    val current = result
                    when {
                        current == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        current.isFailure -> Text("Bilan indisponible pour cette volée.", color = MaterialTheme.colorScheme.error)
                        else -> GroupingMeasurementsPanel(current.getOrThrow(), unit)
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupingMeasurementsPanel(summary: EndGroupingSummary, unit: GroupingUnit) {
    val grouping = summary.grouping
    val measures = remember(grouping, unit) { grouping.measurementsIn(unit) }
    val recordedCount = grouping.impactCount + grouping.excludedMissCount
    val suffix = when (unit) {
        GroupingUnit.NORMALIZED -> ""
        GroupingUnit.CENTIMETERS -> " cm"
        GroupingUnit.MILLIMETERS -> " mm"
    }
    fun length(value: Double) = groupingLengthLabel(value) + suffix

    Text(summary.score?.let { "Score de la volée : $it point(s)" } ?: "Score de la volée : — · aucun impact actif")
    Text("Impacts retenus : ${grouping.impactCount} sur $recordedCount")
    if (grouping.excludedMissCount > 0) Text("M exclus : ${grouping.excludedMissCount}")
    if (grouping.includedMissCount > 0) Text("M inclus : ${grouping.includedMissCount}")
    HorizontalDivider()
    Text("Centrage", style = MaterialTheme.typography.titleMedium)
    val center = measures.center
    if (center == null) {
        Text("Centre moyen et décalage indisponibles · aucun impact retenu.")
    } else {
        Text("Centre moyen : X ${length(center.x)} · Y ${length(center.y)}")
        Text("Décalage du centre : ${length(center.distanceFromTargetCenter)}")
        Text("X positif vers la droite · Y positif vers le bas.", style = MaterialTheme.typography.bodySmall)
    }
    Text("Dispersion", style = MaterialTheme.typography.titleMedium)
    val dispersion = measures.dispersion
    if (dispersion == null) {
        Text("Rayon moyen et diamètre indisponibles · deux impacts sont nécessaires.")
    } else {
        Text("Rayon moyen : ${length(dispersion.meanRadius)}")
        Text("Diamètre maximal : ${length(dispersion.diameter)}")
    }
    Text(
        if (unit == GroupingUnit.NORMALIZED) "En unités du rayon du blason (1 = rayon nominal)."
        else "Conversion d’après le rayon nominal du blason de cette volée.",
        style = MaterialTheme.typography.bodySmall,
    )
}

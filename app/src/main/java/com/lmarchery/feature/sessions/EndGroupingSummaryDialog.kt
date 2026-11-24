package com.lmarchery.feature.sessions

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.lmarchery.core.model.target.TargetType
import com.lmarchery.core.scoring.grouping.GroupingMissPolicy
import com.lmarchery.core.scoring.grouping.GroupingUnit
import com.lmarchery.feature.scoring.zoneColor

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EndGroupingSummaryDialog(source: EndGroupingSummarySource, close: () -> Unit) {
    val end = source.end
    var includeMisses by rememberSaveable(end.uuid) { mutableStateOf(true) }
    var unit by rememberSaveable(end.uuid) { mutableStateOf(GroupingUnit.CENTIMETERS) }
    val policy = if (includeMisses) GroupingMissPolicy.INCLUDE_MISSES else GroupingMissPolicy.EXCLUDE_MISSES
    val reader = remember(source) { EndGroupingSummaryReader(source) }
    val result by key(reader, policy) {
        // An old result must not be rendered against another end or the newly selected filter.
        produceState<Result<EndGroupingSummary>?>(initialValue = null) {
            value = try {
                Result.success(reader.read(policy))
            } catch (failure: IllegalArgumentException) {
                Result.failure(failure)
            }
        }
    }
    Dialog(onDismissRequest = close) {
        Surface(Modifier.fillMaxSize(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
                TextButton(onClick = close) { Text("‹ Retour à la volée") }
                HorizontalDivider()
                Column(
                    Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Bilan de la volée ${end.index}", style = MaterialTheme.typography.headlineSmall)
                    Text("${end.status.label()} · ${source.configuration.distanceM} m · ${source.configuration.targetType.name}")
                    val current = result
                    when {
                        current == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        current.isFailure -> Text("Bilan indisponible pour cette volée.", color = MaterialTheme.colorScheme.error)
                        else -> {
                            val summary = current.getOrThrow()
                            Text(summary.score?.let { "Score de la volée · ${it.pointsLabel()}" }
                                ?: "Aucune flèche comptabilisée", style = MaterialTheme.typography.titleMedium)
                            GroupingGraphic(source, summary)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().toggleable(includeMisses, role = Role.Switch,
                            onValueChange = { includeMisses = it }).padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Inclure les flèches notées M dans les mesures de groupement", Modifier.weight(1f))
                        Switch(checked = includeMisses, onCheckedChange = null)
                    }
                    Text("Le filtre change les mesures. Le score et les décisions restent ceux de la volée.",
                        style = MaterialTheme.typography.bodySmall)
                    if (current?.isSuccess == true) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(unit == GroupingUnit.CENTIMETERS, { unit = GroupingUnit.CENTIMETERS }, { Text("cm") })
                            FilterChip(unit == GroupingUnit.MILLIMETERS, { unit = GroupingUnit.MILLIMETERS }, { Text("mm") })
                        }
                        GroupingMeasurementsPanel(current.getOrThrow(), unit)
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupingGraphic(source: EndGroupingSummarySource, summary: EndGroupingSummary) {
    val grouping = summary.grouping
    val plot = remember(source, grouping) { groupingPlot(source, grouping) }
    if (plot.impacts.isEmpty()) {
        Text("Aucun impact enregistré pour ce bilan.")
        return
    }
    val count = grouping.impactCount
    Text("Flèches retenues pour les mesures · $count sur ${plot.impacts.size}",
        style = MaterialTheme.typography.bodySmall)
    GroupingTarget(plot, source.configuration.targetType, Modifier.widthIn(max = 232.dp).fillMaxWidth().aspectRatio(1f))
    if (count > 0) Text("● Flèche retenue", style = MaterialTheme.typography.bodySmall)
    if (grouping.excludedMissCount > 0) {
        Text("○ Flèche M (0 point) exclue des mesures", style = MaterialTheme.typography.bodySmall)
    }
    if (plot.mean != null) Text("◇ Centre moyen des flèches retenues", style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun GroupingTarget(plot: GroupingPlot, target: TargetType, modifier: Modifier = Modifier) {
    val zones = remember(target) { target.zones.sortedByDescending { it.outerRadiusNormalized } }
    val retainedColor = MaterialTheme.colorScheme.primary
    val meanColor = MaterialTheme.colorScheme.tertiary
    val description = "Blason ${target.name}, ${plot.impacts.size} positions enregistrées"
    Canvas(modifier.semantics { contentDescription = description }) {
        val padding = 12.dp.toPx()
        if (size.width <= 2 * padding || size.height <= 2 * padding) return@Canvas
        val layout = plot.layout((size.width - 2 * padding).toDouble(), (size.height - 2 * padding).toDouble())
        fun offset(point: PlotPoint): Offset = layout.position(point).let { Offset(it.x.toFloat() + padding, it.y.toFloat() + padding) }

        val origin = offset(PlotPoint(0.0, 0.0))
        zones.forEach { zone ->
            val radius = (zone.outerRadiusNormalized * layout.radius).toFloat()
            drawCircle(zoneColor(zone), radius, origin)
            drawCircle(Color.Black.copy(alpha = .52f), radius, origin, style = Stroke(1.dp.toPx()))
        }
        val cross = minOf(5.dp.toPx(), layout.radius.toFloat())
        drawLine(Color.Black.copy(alpha = .6f), origin - Offset(cross, 0f), origin + Offset(cross, 0f), 1.dp.toPx())
        drawLine(Color.Black.copy(alpha = .6f), origin - Offset(0f, cross), origin + Offset(0f, cross), 1.dp.toPx())
        val mark = 4.dp.toPx()
        plot.impacts.forEach { impact ->
            val point = offset(impact.point)
            drawCircle(Color.Black, mark + 1.dp.toPx(), point, style = Stroke(1.5.dp.toPx()))
            if (impact.retained) {
                drawCircle(Color.White, mark + 0.5.dp.toPx(), point)
                drawCircle(retainedColor, mark - 1.dp.toPx(), point)
            } else {
                drawCircle(Color.White, mark, point, style = Stroke(1.5.dp.toPx()))
            }
        }
        plot.mean?.let { mean ->
            val point = offset(mean)
            val radius = 8.dp.toPx()
            val diamond = Path().apply {
                moveTo(point.x, point.y - radius)
                lineTo(point.x + radius, point.y)
                lineTo(point.x, point.y + radius)
                lineTo(point.x - radius, point.y)
                close()
            }
            drawPath(diamond, Color.Black, style = Stroke(4.dp.toPx()))
            drawPath(diamond, Color.White, style = Stroke(2.5.dp.toPx()))
            drawPath(diamond, meanColor, style = Stroke(1.dp.toPx()))
        }
    }
}

@Composable
private fun GroupingMeasurementsPanel(summary: EndGroupingSummary, unit: GroupingUnit) {
    val grouping = summary.grouping
    val measures = remember(grouping, unit) { grouping.measurementsIn(unit) }
    val suffix = if (unit == GroupingUnit.CENTIMETERS) " cm" else " mm"
    fun length(value: Double) = groupingLengthLabel(value) + suffix

    Text("Centrage", style = MaterialTheme.typography.titleMedium)
    val center = measures.center
    val direction = grouping.normalized.center?.directionLabel().orEmpty()
    when {
        center == null -> Text("Aucune flèche retenue pour calculer le centre moyen.")
        direction.isEmpty() -> Text("Le centre moyen coïncide avec le centre du blason.")
        else -> Text("Le centre moyen est à ${length(center.distanceFromTargetCenter)} du centre du blason, vers $direction.")
    }
    Text("Dispersion", style = MaterialTheme.typography.titleMedium)
    val dispersion = measures.dispersion
    if (dispersion == null) Text("Il faut au moins deux flèches retenues pour mesurer leur écart.")
    else {
        Text("Distance moyenne des flèches à leur centre · ${length(dispersion.meanRadius)}")
        Text("Écart entre les deux flèches les plus éloignées · ${length(dispersion.diameter)}")
    }
    Text("Distances estimées à partir du diamètre nominal du blason.", style = MaterialTheme.typography.bodySmall)
    TechnicalDetails("bilan-${grouping.endUuid}") {
        Text("Coordonnées normalisées. 1 représente le rayon nominal du blason.")
        grouping.normalized.center?.let {
            Text("Centre moyen · x ${groupingLengthLabel(it.x)}, y ${groupingLengthLabel(it.y)}")
            Text("Décalage · ${groupingLengthLabel(it.distanceFromTargetCenter)}")
        }
        grouping.normalized.dispersion?.let {
            Text("Rayon moyen · ${groupingLengthLabel(it.meanRadius)}")
            Text("Diamètre maximal · ${groupingLengthLabel(it.diameter)}")
        }
    }
}

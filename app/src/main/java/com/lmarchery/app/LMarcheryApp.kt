package com.lmarchery.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.session.ArrowImpact
import com.lmarchery.core.model.target.TargetZone
import com.lmarchery.feature.scoring.CanvasPoint
import com.lmarchery.feature.scoring.CanvasSize
import com.lmarchery.feature.scoring.ScoringDemoState
import com.lmarchery.feature.scoring.canvasPointToNormalized
import com.lmarchery.feature.scoring.normalizedPointToCanvas
import java.util.Locale

@Composable
fun LMarcheryApp() {
    var demoState by remember { mutableStateOf(ScoringDemoState()) }

    Scaffold { innerPadding ->
        ScoringDemoScreen(
            state = demoState,
            onImpactAdded = { point -> demoState = demoState.addImpact(point) },
            onReset = { demoState = demoState.reset() },
            modifier = Modifier.padding(innerPadding),
        )
    }
}

@Composable
private fun ScoringDemoScreen(
    state: ScoringDemoState,
    onImpactAdded: (NormalizedPoint) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "LMarchery",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Prototype local : 3 impacts en mémoire, score calculé par core:scoring.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            TargetCard(
                state = state,
                onImpactAdded = onImpactAdded,
                modifier = Modifier.fillMaxWidth(),
            )

            ScoreSummary(state = state, onReset = onReset)
        }
    }
}

@Composable
private fun TargetCard(
    state: ScoringDemoState,
    onImpactAdded: (NormalizedPoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = state.targetType.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            TargetCanvas(
                state = state,
                onImpactAdded = onImpactAdded,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
            )

            Text(
                text = if (state.canAddImpact) {
                    "Touchez le blason pour placer la flèche ${state.impacts.size + 1}/${state.maxImpacts}."
                } else {
                    "Volée de démonstration complète : ${state.maxImpacts}/${state.maxImpacts}."
                },
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TargetCanvas(
    state: ScoringDemoState,
    onImpactAdded: (NormalizedPoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = isSystemInDarkTheme()
    val borderColor = MaterialTheme.colorScheme.outlineVariant

    Box(
        modifier = modifier
            .background(
                color = if (isDark) Color(0xFF111318) else Color(0xFFF6F7FB),
                shape = RoundedCornerShape(16.dp),
            )
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .padding(12.dp),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = "Blason interactif LMarchery" }
                .pointerInput(state.impacts.size, state.canAddImpact) {
                    detectTapGestures { offset ->
                        if (state.canAddImpact) {
                            onImpactAdded(
                                canvasPointToNormalized(
                                    point = CanvasPoint(offset.x, offset.y),
                                    canvasSize = CanvasSize(
                                        widthPx = size.width.toFloat(),
                                        heightPx = size.height.toFloat(),
                                    ),
                                ),
                            )
                        }
                    }
                },
        ) {
            val diameterPx = size.minDimension
            val center = Offset(size.width / 2f, size.height / 2f)
            val radiusPx = diameterPx / 2f

            state.targetType.zones
                .sortedByDescending(TargetZone::outerRadiusNormalized)
                .forEach { zone ->
                    drawCircle(
                        color = zoneColor(zone),
                        radius = radiusPx * zone.outerRadiusNormalized,
                        center = center,
                    )
                    drawCircle(
                        color = Color(0x66000000),
                        radius = radiusPx * zone.outerRadiusNormalized,
                        center = center,
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }

            drawLine(
                color = Color(0x55000000),
                start = Offset(center.x - radiusPx, center.y),
                end = Offset(center.x + radiusPx, center.y),
                strokeWidth = 1.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = Color(0x55000000),
                start = Offset(center.x, center.y - radiusPx),
                end = Offset(center.x, center.y + radiusPx),
                strokeWidth = 1.dp.toPx(),
                cap = StrokeCap.Round,
            )

            state.impacts.forEach { impact ->
                val point = normalizedPointToCanvas(
                    point = impact.point,
                    canvasSize = CanvasSize(widthPx = size.width, heightPx = size.height),
                )
                val centerOffset = Offset(point.x, point.y)
                val impactColor = if (impact.isMiss) Color(0xFFE53935) else Color(0xFF00C853)

                drawCircle(
                    color = Color.White,
                    radius = 9.dp.toPx(),
                    center = centerOffset,
                )
                drawCircle(
                    color = impactColor,
                    radius = 7.dp.toPx(),
                    center = centerOffset,
                )
                drawCircle(
                    color = Color.Black.copy(alpha = 0.75f),
                    radius = 9.dp.toPx(),
                    center = centerOffset,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }
    }
}

@Composable
private fun ScoreSummary(
    state: ScoringDemoState,
    onReset: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "Score total",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${state.totalScore} / ${state.maxPossibleScore}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Button(onClick = onReset, enabled = state.impacts.isNotEmpty()) {
                    Text("Réinitialiser")
                }
            }

            if (state.impacts.isEmpty()) {
                Text(
                    text = "Aucun impact saisi. Les données restent uniquement en mémoire pour cette tranche.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.impacts, key = ArrowImpact::uuid) { impact ->
                        ImpactRow(impact)
                    }
                }
            }
        }
    }
}

@Composable
private fun ImpactRow(impact: ArrowImpact) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "#${impact.index}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "x=${formatCoordinate(impact.point.x)}, y=${formatCoordinate(impact.point.y)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = impact.scoreLabel(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun ArrowImpact.scoreLabel(): String = when {
    isMiss -> "M"
    isX -> "${scoreFinal}X"
    else -> scoreFinal.toString()
}

private fun formatCoordinate(value: Float): String =
    String.format(Locale.US, "%.2f", value)

private fun zoneColor(zone: TargetZone): Color = when (zone.colorName.lowercase(Locale.US)) {
    "yellow" -> Color(0xFFFFD400)
    "red" -> Color(0xFFE53935)
    "blue" -> Color(0xFF1E88E5)
    "black" -> Color(0xFF212121)
    "white" -> Color(0xFFFFFFFF)
    else -> Color(0xFFBDBDBD)
}

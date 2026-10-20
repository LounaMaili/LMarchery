package com.lmarchery.feature.scoring

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.session.ScoringMode
import com.lmarchery.core.scoring.profiles.WorldArcheryIndoorTargetProfiles as WA
import com.lmarchery.core.scoring.scoreArrowImpact
import com.lmarchery.ui.theme.LMarcheryTheme
import java.time.Instant

@Preview(name = "Visée classique · petite dalle", widthDp = 320, heightDp = 540, showBackground = true)
@Composable
private fun RecurvePrecisionPreview() = PrecisionPreview(false)

@Preview(name = "Visée poulies · paysage", widthDp = 640, heightDp = 360, showBackground = true)
@Composable
private fun CompoundPrecisionPreview() = PrecisionPreview(true)

@Composable
private fun PrecisionPreview(compound: Boolean) {
    val target = if (compound) WA.compound40 else WA.recurve40
    val impact = scoreArrowImpact("00000000-0000-4000-8000-000000000001", 1, target,
        NormalizedPoint(.15f, 0f), ScoringMode.TRAINING, createdAt = Instant.parse("2026-10-16T10:00:00Z"))
    LMarcheryTheme {
        Surface {
            Column(Modifier.padding(12.dp)) {
                Text("Loupe ×4 · proposition non enregistrée")
                TargetCanvas(target, listOf(impact), TargetInteraction.REPOSITION, "preview", {},
                    Modifier.widthIn(max = precisionTargetMaxWidth(LocalConfiguration.current.screenHeightDp.toFloat()).dp).fillMaxWidth().aspectRatio(1f), impact.uuid,
                    NormalizedPoint(.075f, 0f), preciseAim = true)
                Text("Confirmer ou annuler avant tout enregistrement.")
            }
        }
    }
}

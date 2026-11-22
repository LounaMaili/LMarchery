package com.lmarchery.feature.sessions

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import com.lmarchery.captureUxScreen
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.session.*
import com.lmarchery.core.scoring.profiles.WorldArcheryIndoorTargetProfiles
import com.lmarchery.core.scoring.scoreArrowImpact
import java.time.Instant
import java.util.UUID
import org.junit.Rule
import org.junit.Test

class GroupingReadabilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun landscapeWithLargeTextKeepsTheReadOnlyDiagramAndTechnicalValuesAccessible() {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(8_000) {
            compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        val source = fixture()
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.35f)) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    EndGroupingSummaryDialog(source, {})
                }
            }
        }

        val description = "Blason ${source.configuration.targetType.name}, 3 positions enregistrées"
        compose.waitUntil(8_000) {
            compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(description).assertHasNoClickAction()
        compose.onNodeWithText("‹ Retour à la volée").assertIsDisplayed()
        captureUxScreen("bilan-sombre-paysage")

        compose.onNodeWithText("mm").performScrollTo().performClick()
        compose.onNodeWithText("diamètre nominal", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Coordonnées normalisées", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Informations techniques").performScrollTo().performClick()
        compose.onNodeWithText("1 représente le rayon nominal", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("‹ Retour à la volée").assertIsDisplayed()
    }

    private fun fixture(): EndGroupingSummarySource {
        val now = Instant.parse("2026-11-20T12:00:00Z")
        val target = WorldArcheryIndoorTargetProfiles.compound60
        val configuration = EndConfiguration(target, "2026-01-27", 18, ArrowCountMode.FREE, null, true, ScoringMode.TRAINING)
        val points = listOf(NormalizedPoint(-.25f, -.25f), NormalizedPoint(.1f, -.4f), NormalizedPoint(1.1f, .3f))
        val impacts = points.mapIndexed { index, point ->
            scoreArrowImpact(UUID.randomUUID().toString(), index + 1, target, point, ScoringMode.TRAINING, createdAt = now)
        }
        return EndGroupingSummarySource(
            End(UUID.randomUUID().toString(), 1, EndStatus.EDITING, impacts, createdAt = now, updatedAt = now),
            configuration,
        )
    }
}

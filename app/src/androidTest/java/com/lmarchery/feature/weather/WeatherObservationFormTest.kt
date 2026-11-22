package com.lmarchery.feature.weather

import android.content.pm.ActivityInfo
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import com.lmarchery.captureUxScreen
import com.lmarchery.core.model.weather.RainReport
import com.lmarchery.core.model.weather.WeatherObservation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import android.os.ParcelFileDescriptor
import java.time.Instant

@SdkSuppress(minSdkVersion = 30)
class WeatherObservationFormTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val session = "de000000-0000-4000-8000-000000000001"
    private val form = "de000000-0000-4000-8000-000000000002"
    private val first = "de000000-0000-4000-8000-000000000003"
    private val second = "de000000-0000-4000-8000-000000000004"
    private val now = Instant.parse("2026-11-20T08:00:00Z")

    @Test fun essentialFieldsStayVisibleAndKeepUnknownValuesDistinct() {
        var observed: WeatherObservation? = null
        form { observed = it }
        compose.onNodeWithText("Température (°C)").assertIsDisplayed()
        compose.onNodeWithText("Rafales (m/s)").assertDoesNotExist()
        field("Température (°C)").performClick()
        compose.waitUntil(6_000) {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("dumpsys input_method"))
                .bufferedReader().useLines { lines ->
                    lines.any { "mInputShown=true" in it || "mIsInputViewShown=true" in it }
                }
        }
        assertSubmitAboveSystemBars()
        captureUxScreen("meteo-clavier")
        field("Température (°C)").performTextInput("12")
        compose.onNodeWithText("Oui").performScrollTo().performClick()
        compose.onNodeWithText("Enregistrer le relevé").assertIsDisplayed().performClick()
        compose.runOnIdle {
            val saved = requireNotNull(observed)
            assertEquals(12.0, saved.temperatureC!!, 0.0)
            assertEquals(RainReport.RAIN, saved.rain)
            assertNull(saved.precipitationMm)
            assertNull(saved.meanMps)
            assertTrue(saved.scope.endUuids.isEmpty())
        }
    }

    @Test fun advancedScopeAndDecimalInputSurviveSwitchingPagesInLandscape() {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(8_000) {
            compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        }
        var observed: WeatherObservation? = null
        form { observed = it }
        assertSubmitAboveSystemBars()
        captureUxScreen("meteo-paysage")
        field("Température (°C)").performScrollTo().performTextInput("12,5")
        compose.onNodeWithText("Détails").performClick()
        compose.onNodeWithText("Volée 1").performScrollTo().performClick()
        compose.onNodeWithText("Volée 2").performScrollTo().performClick()
        field("Rafales (m/s)").performScrollTo().performTextInput("0")
        compose.onNodeWithText("Essentiel").performClick()
        field("Température (°C)").assertTextContains("12,5")
        compose.onNodeWithText("Enregistrer le relevé").assertIsDisplayed().performClick()
        compose.runOnIdle {
            val saved = requireNotNull(observed)
            assertEquals(12.5, saved.temperatureC!!, 0.0)
            assertEquals(0.0, saved.gustMps!!, 0.0)
            assertEquals(listOf(first, second), saved.scope.endUuids)
            assertNull(saved.rain)
        }
    }

    private fun form(save: (WeatherObservation) -> Unit) {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density * 1.15f, 1.35f)) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    WeatherObservationForm(session, form, listOf(first to 1, second to 2), { now }, {}, save)
                }
            }
        }
    }

    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label))

    private fun assertSubmitAboveSystemBars() {
        val submit = compose.onNode(hasText("Enregistrer le relevé") and hasClickAction())
            .assertIsDisplayed().fetchSemanticsNode()
        compose.runOnIdle {
            val coordinates = submit.layoutInfo.coordinates
            val buttonBottom = coordinates.positionOnScreen().y + coordinates.size.height
            val metrics = compose.activity.windowManager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            val usableBottom = metrics.bounds.bottom - insets.bottom
            assertTrue("Bouton jusqu’à $buttonBottom px, zone utile jusqu’à $usableBottom px",
                buttonBottom <= usableBottom + 1f)
        }
    }
}

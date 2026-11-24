package com.lmarchery.feature.configured

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import android.hardware.SensorManager
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lmarchery.core.model.place.CompassQuality
import com.lmarchery.core.model.place.CompassReading

@Composable
internal fun rememberCompassSurvey(): CompassSurvey {
    val context = LocalContext.current
    val view = LocalView.current
    return remember(context, view) {
        AndroidCompassSurvey(context.getSystemService(SensorManager::class.java), { view.display?.rotation })
    }
}

@Composable
internal fun CompassMeasureDialog(survey: CompassSurvey, dismiss: () -> Unit, adopt: (CompassReading) -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var reading by remember { mutableStateOf<CompassReading?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val onDismiss by rememberUpdatedState(dismiss)
    val scope = rememberCoroutineScope()
    DisposableEffect(survey, lifecycle) {
        val job = scope.launch {
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                onDismiss()
                return@launch
            }
            try { reading = survey.measure() }
            catch (e: CancellationException) { throw e }
            catch (e: SurveyUnavailable) { problem = e.message }
            catch (_: RuntimeException) { problem = "La mesure est indisponible." }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                job.cancel()
                onDismiss()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            job.cancel()
            lifecycle.removeObserver(observer)
        }
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Orientation de la ligne") }, text = {
        val value = reading
        Text(when {
            problem != null -> requireNotNull(problem)
            value != null -> "Proposition · ${java.text.DecimalFormat("#0.0").format(value.magneticDegrees)}° nord magnétique. Qualité signalée par le capteur : " +
                (if (value.quality == CompassQuality.HIGH) "élevée." else "moyenne.") + " Précision angulaire inconnue."
            else -> "Gardez le téléphone immobile un instant, le haut de l’écran dans le sens de la ligne."
        })
    }, confirmButton = {
        reading?.let { value ->
            TextButton({ if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) adopt(value) }) { Text("Utiliser dans le formulaire") }
        }
    }, dismissButton = { TextButton(dismiss) { Text("Fermer sans utiliser") } })
}

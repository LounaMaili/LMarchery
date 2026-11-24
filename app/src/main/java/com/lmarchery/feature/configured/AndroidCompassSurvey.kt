package com.lmarchery.feature.configured

import android.hardware.*
import android.os.SystemClock
import java.time.Clock
import com.lmarchery.core.model.place.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlin.math.sqrt

internal class AndroidCompassSurvey(
    private val manager: SensorManager?,
    private val screenRotation: () -> Int?,
    private val clock: Clock = Clock.systemUTC(),
) : CompassSurvey {
    private val rotation = manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: manager?.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
    private val field = manager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    override val available get() = rotation != null && field != null && screenRotation() in 0..3

    override suspend fun measure(): CompassReading = readCompassOnce(available) {
        callbackFlow {
            val service = manager ?: throw SurveyUnavailable("Capteurs indisponibles.")
            val vector = rotation ?: throw SurveyUnavailable("Orientation indisponible.")
            val magnet = field ?: throw SurveyUnavailable("Champ magnétique indisponible.")
            val display = screenRotation() ?: throw SurveyUnavailable("Écran indisponible.")
            val start = SystemClock.elapsedRealtimeNanos()
            val method = if (vector.type == Sensor.TYPE_ROTATION_VECTOR) CompassMethod.ROTATION_VECTOR
                else CompassMethod.GEOMAGNETIC_ROTATION_VECTOR
            val window = CompassWindow(method)
            var fieldTime = -1L
            var quality: CompassQuality? = null
            val matrix = FloatArray(9)
            val listener = object : SensorEventListener {
                override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                    if (sensor == magnet && accuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) {
                        quality = null
                        if (fieldTime >= 0) close(SurveyUnavailable("Fiabilité magnétique insuffisante."))
                    }
                }

                override fun onSensorChanged(event: SensorEvent) {
                    if (!isActive || event.timestamp < start) return
                    if (screenRotation() != display) {
                        close(SurveyUnavailable("L’orientation de l’écran a changé."))
                        return
                    }
                    if (SystemClock.elapsedRealtimeNanos() - event.timestamp !in 0..500_000_000L) return
                    if (event.sensor == magnet) {
                        val v = event.values
                        if (v.size < 3) { close(SurveyUnavailable("Champ magnétique illisible.")); return }
                        val strength = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble()
                        fieldTime = event.timestamp
                        quality = fieldQuality(strength, event.accuracy)
                        if (quality == null) close(SurveyUnavailable("Champ magnétique perturbé ou capteur peu fiable."))
                        return
                    }
                    val measuredQuality = quality ?: return
                    if (event.sensor != vector || event.timestamp - fieldTime !in 0..350_000_000L) return
                    if (event.accuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) {
                        close(SurveyUnavailable("Fiabilité du vecteur de rotation insuffisante.")); return
                    }
                    if (!validRotationVector(event.values)) {
                        close(SurveyUnavailable("Vecteur de rotation illisible.")); return
                    }
                    SensorManager.getRotationMatrixFromVector(matrix, event.values)
                    val bearing = screenTopBearing(matrix, display)
                    if (bearing == null) { close(SurveyUnavailable("Le haut de l’écran est trop incliné.")); return }
                    try {
                        val weakest = if (event.accuracy == SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM)
                            CompassQuality.MEDIUM else measuredQuality
                        window.accept(bearing, event.timestamp, weakest, clock.instant())?.let {
                            trySend(it)
                            close()
                        }
                    } catch (e: SurveyUnavailable) { close(e) }
                }
            }
            try {
                if (!service.registerListener(listener, magnet, SensorManager.SENSOR_DELAY_UI) ||
                    !service.registerListener(listener, vector, SensorManager.SENSOR_DELAY_UI))
                    throw SurveyUnavailable("Les capteurs ne sont pas disponibles.")
                awaitClose()
            } finally {
                service.unregisterListener(listener)
            }
        }
    }
}

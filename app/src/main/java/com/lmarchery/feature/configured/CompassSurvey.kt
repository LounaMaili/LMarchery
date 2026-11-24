package com.lmarchery.feature.configured

import com.lmarchery.core.model.place.*
import java.time.Instant
import kotlin.math.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** One request for a catalogue entry. No subscriptions are created by capability discovery. */
internal interface CompassSurvey {
    val available: Boolean
    suspend fun measure(): CompassReading
}

internal suspend fun readCompassOnce(available: Boolean, readings: () -> Flow<CompassReading>): CompassReading {
    if (!available) throw SurveyUnavailable("Les capteurs requis sont indisponibles.")
    return withTimeoutOrNull(5_000L) { readings().first() }
        ?: throw SurveyUnavailable("Mesure incomplète. La saisie manuelle reste disponible.")
}

internal class SurveyUnavailable(message: String) : Exception(message)

/** Android may omit the scalar component or append a fifth heading-accuracy value. */
internal fun validRotationVector(values: FloatArray): Boolean {
    if (values.size < 3) return false
    var squaredNorm = 0.0
    for (index in 0 until minOf(values.size, 4)) {
        val component = values[index].toDouble()
        squaredNorm += component * component
    }
    return if (values.size == 3) squaredNorm <= 1.001 else squaredNorm in 0.999..1.001
}

/** The rotation matrix maps device axes into East, magnetic North and Up. */
internal fun screenTopBearing(matrix: FloatArray, quarterTurns: Int): Double? {
    if (matrix.size != 9 || matrix.any { !it.isFinite() }) return null
    val column = when (quarterTurns) {
        0, 2 -> 1
        1, 3 -> 0
        else -> return null
    }
    val sign = if (quarterTurns < 2) 1 else -1
    val east = sign * matrix[column].toDouble()
    val north = sign * matrix[3 + column].toDouble()
    if (hypot(east, north) < 0.5) return null
    return positiveDegrees(Math.toDegrees(atan2(east, north)))
}

private fun positiveDegrees(angle: Double): Double {
    val remainder = angle % 360.0
    return ((remainder + 360.0) % 360.0)
}

internal class CompassWindow(private val method: CompassMethod) {
    private val samples = mutableListOf<Double>()
    private var firstNanos = 0L
    private var lastNanos = -1L
    private var quality = CompassQuality.HIGH

    fun accept(bearing: Double, timestampNanos: Long, accuracy: CompassQuality, instant: Instant): CompassReading? {
        if (bearing !in 0.0..<360.0) throw SurveyUnavailable("Azimut invalide.")
        if (timestampNanos <= lastNanos) return null
        if (samples.isEmpty()) firstNanos = timestampNanos
        else if (timestampNanos - lastNanos > 500_000_000L)
            throw SurveyUnavailable("Les mesures ont été interrompues.")
        lastNanos = timestampNanos
        if (accuracy == CompassQuality.MEDIUM) quality = accuracy
        samples.add(bearing)
        if (samples.size < 8 || timestampNanos - firstNanos < 750_000_000L) return null
        val mean = positiveDegrees(Math.toDegrees(atan2(samples.sumOf { sin(Math.toRadians(it)) },
            samples.sumOf { cos(Math.toRadians(it)) })))
        if (samples.any { abs(Math.IEEEremainder(it - mean, 360.0)) > 5.0 })
            throw SurveyUnavailable("Orientation instable pendant la mesure.")
        return CompassReading(mean, instant, method, quality)
    }
}

internal fun fieldQuality(strengthMicrotesla: Double, status: Int): CompassQuality? {
    if (strengthMicrotesla !in 20.0..75.0) return null
    return when (status) {
        3 -> CompassQuality.HIGH
        2 -> CompassQuality.MEDIUM
        else -> null
    }
}

package com.lmarchery.feature.configured

import com.lmarchery.core.model.place.*
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import org.junit.jupiter.api.Test
import kotlin.test.*

class CompassSurveyTest {
    private val method = CompassMethod.ROTATION_VECTOR

    @Test fun missingSensorsCancelAndSuccessReleaseTheSimulatedSubscription() = runBlocking<Unit> {
        var starts = 0
        var releases = 0
        val waiting = CompletableDeferred<Unit>()
        val value = CompassReading(90.0, Instant.EPOCH, method, CompassQuality.HIGH)
        suspend fun request(available: Boolean, produce: Boolean) = readCompassOnce(available) {
            flow {
                starts++
                try {
                    waiting.complete(Unit)
                    if (produce) emit(value)
                    awaitCancellation()
                } finally { releases++ }
            }
        }
        assertFailsWith<SurveyUnavailable> { request(false, false) }
        assertEquals(0, starts)
        val job = launch { request(true, false) }
        waiting.await()
        job.cancelAndJoin()
        assertEquals(1, releases)
        assertEquals(value, request(true, true))
        assertEquals(2, starts)
        assertEquals(2, releases)
    }

    @Test fun absentMalformedOrZeroQuaternionCannotInventNorth() {
        assertFalse(validRotationVector(floatArrayOf()))
        assertFalse(validRotationVector(floatArrayOf(0f, 0f, 0f, 0f)))
        assertFalse(validRotationVector(floatArrayOf(Float.NaN, 0f, 0f)))
        assertFalse(validRotationVector(floatArrayOf(1f, 1f, 0f)))
        assertTrue(validRotationVector(floatArrayOf(0f, 0f, 0f)))
        assertTrue(validRotationVector(floatArrayOf(0f, 0f, 0f, 1f, -1f)))
    }

    @Test fun displayedTopUsesAllFourRotationsAndTheHorizontalProjection() {
        val level = floatArrayOf(1f,0f,0f, 0f,1f,0f, 0f,0f,1f)
        assertEquals(listOf(0.0,90.0,180.0,270.0), (0..3).map { screenTopBearing(level, it) })
        val tilted = floatArrayOf(1f,0f,0f, 0f,0.8f,-0.6f, 0f,0.6f,0.8f)
        assertEquals(0.0, screenTopBearing(tilted, 0))
        val east = floatArrayOf(0f,0.8f,-0.6f, -1f,0f,0f, 0f,0.6f,0.8f)
        assertEquals(90.0, screenTopBearing(east, 0))
        assertNull(screenTopBearing(floatArrayOf(1f,0f,0f, 0f,0f,-1f, 0f,1f,0f), 0))
        assertNull(screenTopBearing(level, 4))
        assertNull(screenTopBearing(FloatArray(9) { Float.NaN }, 0))
    }

    @Test fun stableNorthAcrossZeroKeepsWeakestQualityAndLastObservationTime() {
        val window = CompassWindow(method)
        val at = Instant.parse("2026-01-01T10:00:00Z")
        var reading: CompassReading? = null
        for (i in 0..7) {
            assertNull(reading)
            reading = window.accept(if (i % 2 == 0) 359.0 else 1.0, i * 110_000_000L,
                if (i == 2) CompassQuality.MEDIUM else CompassQuality.HIGH, at.plusMillis(i * 110L))
        }
        assertEquals(CompassReading(0.0, at.plusMillis(770), method, CompassQuality.MEDIUM), reading)
    }

    @Test fun repeatedEventsGapsAndMovingSamplesCannotProduceAReading() {
        val window = CompassWindow(method)
        repeat(20) { assertNull(window.accept(90.0, 125L, CompassQuality.HIGH, Instant.EPOCH)) }
        assertFailsWith<SurveyUnavailable> { window.accept(90.0, 900_000_000L, CompassQuality.HIGH, Instant.EPOCH) }
        val moving = CompassWindow(method)
        assertFailsWith<SurveyUnavailable> {
            repeat(8) { moving.accept(it * 7.0, it * 120_000_000L, CompassQuality.HIGH, Instant.EPOCH) }
        }
    }

    @Test fun magneticDisturbanceAndUncalibratedSensorsHaveNoUsableQuality() {
        for (strength in listOf(0.0, 150.0, Double.NaN)) assertNull(fieldQuality(strength, 3))
        for (status in listOf(-1,0,1)) assertNull(fieldQuality(45.0, status))
        assertEquals(CompassQuality.MEDIUM, fieldQuality(45.0, 2))
        assertEquals(CompassQuality.HIGH, fieldQuality(45.0, 3))
    }
}

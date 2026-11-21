package com.lmarchery.core.database

import com.lmarchery.core.export.OpenMeteoProvider
import com.lmarchery.core.model.weather.*
import com.lmarchery.feature.weather.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class WeatherEstimateProviderTest {
    @TempDir lateinit var directory: Path
    private val epoch = 1_700_000_000L
    private val clock = Clock.fixed(Instant.ofEpochSecond(epoch + 90), ZoneId.of("Pacific/Auckland"))
    private val point = WeatherPoint(1.25, -2.5)
    private val response = """{
      "latitude":1.251,"longitude":-2.49,"utc_offset_seconds":0,"generationtime_ms":12.5,
      "current_units":{"time":"unixtime","interval":"seconds","wind_speed_10m":"m/s","wind_gusts_10m":"m/s",
        "wind_direction_10m":"°","temperature_2m":"°C","precipitation":"mm"},
      "current":{"time":1700000000,"interval":900,"wind_speed_10m":2.5,"wind_gusts_10m":null,
        "wind_direction_10m":360,"temperature_2m":-1.5,"precipitation":0}
    }"""
    private fun adapter(payload: String) = OpenMeteoProvider(clock) { payload.encodeToByteArray() }

    @Test fun forecastEndpointAndUnitsAreExplicitAndUnknownsStayUnknown() = runBlocking<Unit> {
        val source = OpenMeteoProvider(clock) { uri ->
            assertEquals("https", uri.scheme)
            assertEquals("api.open-meteo.com", uri.host)
            val query = uri.query.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
            assertEquals(setOf("latitude","longitude","current","wind_speed_unit","temperature_unit","precipitation_unit",
                "models","timezone","timeformat"), query.keys)
            assertEquals("GMT", query["timezone"]); assertEquals("ms", query["wind_speed_unit"])
            assertEquals("1.25", query["latitude"]); assertEquals("-2.5", query["longitude"])
            response.encodeToByteArray()
        }
        val result = source.current(point)
        assertEquals(WeatherEstimate("Open-Meteo", EstimateKind.PROVIDER_ESTIMATE, "best_match", null, clock.instant(), null,
            Instant.ofEpochSecond(epoch), 900, point, WeatherPoint(1.251,-2.49), 10.0, 2.0,
            2.5, null, 0.0, -1.5, 0.0), result)
        val missingRain = adapter(response.replace("\"precipitation\":0", "\"precipitation\":null")).current(point)
        assertNull(missingRain.precipitationMm)
        assertNull(adapter(response.replace("\"wind_speed_10m\":2.5", "\"wind_speed_10m\":0")).current(point).fromTrueDegrees)
    }

    @Test fun invalidUnitsDatesMagnitudesGridAndIncompleteResponsesFailClosed() = runBlocking<Unit> {
        for ((old, new) in listOf("1700000000" to "1699998000", "1700000000" to "1700000900",
            "\"m/s\"" to "\"km/h\"", "seconds" to "minutes", "\"generationtime_ms\":12.5" to "\"error\":true", "\"interval\":900" to "\"interval\":0",
            "1.251" to "51.0", "\"utc_offset_seconds\":0" to "\"utc_offset_seconds\":3600",
            "\"precipitation\":0" to "\"precipitation_probability\":80", "\"wind_direction_10m\":360" to "\"wind_direction_10m\":361",
            "\"precipitation\":0" to "\"precipitation\":-2", "\"wind_speed_10m\":2.5" to "\"wind_speed_10m\":\"2.5\"")) {
            assertFails(old) { adapter(response.replace(old, new)).current(point) }
        }
    }

    @Test fun absentProviderDeclineFailureAndRevocationNeverWrite() = runBlocking<Unit> {
        AppIntegrationHarness(directory.resolve("consent.db")).use { h ->
            val session = EquipmentTestData.id(8600)
            fun journal(provider: WeatherProvider?) = EstimateJournal(provider, h.scope, clock,
                { error("Unexpected write") }, { error("Unexpected database read") }, {})
            val absent = journal(null)
            h.onUi { absent.open(session); assertNull(absent.state.value) }
            val calls = AtomicInteger()
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val source = OpenMeteoProvider(clock) {
                if (calls.incrementAndGet() == 1) error("Synthetic outage")
                started.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
            val j = journal(source)
            fun ticket() = j.state.value!!.ticket
            h.onUi {
                j.open(session)
                j.save(ticket()); j.request("obsolete", point, true); j.request(ticket(), point, false)
                assertNull(j.state.value); assertEquals(0, calls.get())
                j.open(session); j.request(ticket(), point, true); j.request(ticket(), point, true)
            }
            h.awaitUiState(j.state) { it?.step is EstimateStep.Message }
            val revoked = h.onUi {
                j.close(ticket()); j.open(session); ticket().also { j.request(it, point, true) }
            }
            withTimeout(5000) { started.await() }
            h.onUi { j.close(revoked); j.save(revoked) }
            withTimeout(5000) { cancelled.await() }
            assertNull(j.state.value)
            assertEquals(2, calls.get())
        }
    }

    @Test fun independentProviderMustEchoConsentedPointAndNameAndStillBeFreshAtSave() = runBlocking<Unit> {
        val sourceValue = adapter(response).current(point)
        class FixtureProvider : WeatherProvider {
            override val name = "Fixture"
            var answer = sourceValue.copy(provider = name)
            override suspend fun current(point: WeatherPoint) = answer
        }
        val fixture = FixtureProvider()
        var instant = clock.instant()
        val timeline = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: ZoneId) = this
            override fun instant() = instant
        }
        AppIntegrationHarness(directory.resolve("freshness.db")).use { h ->
            val j = EstimateJournal(fixture, h.scope, timeline, { error("Unexpected write") }, { null }, {})
            suspend fun ask(): String = h.onUi {
                j.open(EquipmentTestData.id(8601))
                j.state.value!!.ticket.also { j.request(it, point, true) }
            }
            for (invalid in listOf(sourceValue, sourceValue.copy(provider = fixture.name, requested = WeatherPoint(10.0,10.0)))) {
                fixture.answer = invalid
                val ticket = ask()
                h.awaitUiState(j.state) { it?.step is EstimateStep.Message }
                h.onUi { j.close(ticket) }
            }
            fixture.answer = sourceValue.copy(provider = fixture.name)
            val ticket = ask()
            h.awaitUiState(j.state) { it?.step is EstimateStep.Review }
            h.onUi {
                instant = instant.plusSeconds(1801)
                j.save(ticket)
                assertTrue((j.state.value!!.step as EstimateStep.Message).text.contains("périmée"))
            }
        }
    }

    @Test fun reviewingAndSavingAreSeparateAndUncertainSaveOnlyRereadsOriginalRecord() = runBlocking<Unit> {
        AppIntegrationHarness(directory.resolve("save.db")).use { h ->
            val session = ConfiguredWriteData.rich()
            assertIs<ConfiguredWriteResult.Created>(h.store.createConfiguredSession(session))
            var writes = 0
            val j = EstimateJournal(adapter(response), h.scope, clock, { value ->
                writes++; h.store.weather.recordEstimate(value); error("Lost acknowledgement")
            }, { value -> h.store.weather.details(value.sessionUuid).estimates.singleOrNull { it.uuid == value.uuid } }, {})
            suspend fun preview(): EstimateDialog {
                h.onUi { j.open(session.uuid); j.request(j.state.value!!.ticket, point, true) }
                return h.awaitUiState(j.state) { it?.step is EstimateStep.Review }!!
            }
            val discarded = preview()
            h.onUi { j.close(discarded.ticket) }
            val confirmed = preview()
            assertTrue(h.store.readArchiveV7().estimates.isEmpty())
            h.onUi { j.save(discarded.ticket); j.save(confirmed.ticket); j.save(confirmed.ticket) }
            val pending = h.awaitUiState(j.state) { it?.step is EstimateStep.Unconfirmed }!!
            assertEquals((confirmed.step as EstimateStep.Review).candidate, (pending.step as EstimateStep.Unconfirmed).candidate)
            h.onUi { j.save(confirmed.ticket); j.reconcile(confirmed.ticket); j.reconcile(confirmed.ticket) }
            h.awaitUiState(j.state) { it?.step is EstimateStep.Message }
            assertEquals(1, writes)
            assertEquals(listOf((pending.step as EstimateStep.Unconfirmed).candidate), h.store.readArchiveV7().estimates)
        }
    }
}

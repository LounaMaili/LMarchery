package com.lmarchery.core.export

import com.google.gson.JsonObject
import com.lmarchery.core.model.weather.*
import java.net.URI
import java.time.Clock
import java.time.Instant
import kotlin.math.*

/** Experimental adapter. Distribution supplies no transport, credentials or Internet permission. */
class OpenMeteoProvider(private val clock: Clock, private val fetch: suspend (URI) -> ByteArray) : WeatherProvider {
    override val name = "Open-Meteo"

    override suspend fun current(point: WeatherPoint): WeatherEstimate {
        val variables = units.keys.filter { it != "time" && it != "interval" }.joinToString(",")
        val uri = URI("https://api.open-meteo.com/v1/forecast?latitude=${point.latitude}&longitude=${point.longitude}" +
            "&current=$variables&wind_speed_unit=ms&temperature_unit=celsius&precipitation_unit=mm" +
            "&models=best_match&timezone=GMT&timeformat=unixtime")
        val root = parseStrict(fetch(uri), JsonLimits(maxBytes = 16_384, maxDepth = 5, maxValues = 128)).asJsonObject
        val now = clock.instant()
        fun fields(o: JsonObject) = JsonFields(o, "open-meteo")
        val header = fields(root)
        require(!root.has("error") || !header.boolean("error"))
        require(header.int("utc_offset_seconds") == 0)
        val grid = WeatherPoint(header.double("latitude"), header.double("longitude"))
        // A wrong grid cell can still contain entirely plausible meteorological values.
        require(distanceKm(point, grid) <= 100.0) { "Maille trop éloignée du lieu demandé." }
        val meta = fields(root.getAsJsonObject("current_units"))
        for ((key, unit) in units) require(meta.string(key) == unit) { "Unité inattendue pour $key." }
        return checkedObject(root["current"], "current", *units.keys.toTypedArray()) {
            fun value(key: String) = nullable(key) { double(key) }
            val time = Instant.ofEpochSecond(child("time") { e, _ ->
                require(e.isJsonPrimitive && e.asJsonPrimitive.isNumber)
                e.asString.toLong()
            })
            val interval = int("interval").also { require(it in 1..3600) }
            val wind = value("wind_speed_10m")
            val from = value("wind_direction_10m")?.also { require(it in 0.0..360.0) }
            WeatherEstimate(name, EstimateKind.PROVIDER_ESTIMATE, "best_match", null, now, null, time, interval,
                point, grid, 10.0, 2.0, wind, value("wind_gusts_10m"),
                from?.takeUnless { wind == 0.0 }?.let { if (it == 360.0) 0.0 else it },
                value("temperature_2m"), value("precipitation")).also { require(it.isCurrentAt(now)) }
        }
    }

    private fun distanceKm(a: WeatherPoint, b: WeatherPoint): Double {
        fun rad(x: Double) = Math.toRadians(x)
        val h = sin(rad(b.latitude - a.latitude) / 2).pow(2) + cos(rad(a.latitude)) *
            cos(rad(b.latitude)) * sin(rad(b.longitude - a.longitude) / 2).pow(2)
        return 12_742 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    private companion object {
        val units = mapOf("time" to "unixtime", "interval" to "seconds", "wind_speed_10m" to "m/s",
            "wind_gusts_10m" to "m/s", "wind_direction_10m" to "°", "temperature_2m" to "°C", "precipitation" to "mm")
    }
}

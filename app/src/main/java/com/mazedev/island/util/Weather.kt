package com.mazedev.island.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** Current weather + the next hours, from Open-Meteo (free, no API key). */
data class WeatherInfo(
    val place: String,
    val temp: Int,
    val code: Int,
    val isDay: Boolean,
    val high: Int,
    val low: Int,
    val hours: List<Hour>,
    val fetchedAt: Long,
) {
    data class Hour(val label: String, val temp: Int, val code: Int)
}

object Weather {
    private const val MAX_AGE_MS = 20 * 60_000L
    @Volatile private var cached: WeatherInfo? = null
    @Volatile private var cachedKey: String? = null

    sealed interface Result {
        data class Ok(val info: WeatherInfo) : Result
        data class Problem(val message: String) : Result
    }

    fun hasLocationPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Call from a background thread. [city] overrides the phone's location when not blank. */
    fun load(context: Context, city: String): Result {
        val place = resolvePlace(context, city)
            ?: return Result.Problem(
                if (city.isNotBlank()) "Couldn't find \"$city\""
                else "Allow location or set a city in Maze Island settings"
            )
        val key = "%.2f,%.2f".format(Locale.US, place.lat, place.lon)
        cached?.let { if (cachedKey == key && System.currentTimeMillis() - it.fetchedAt < MAX_AGE_MS) return Result.Ok(it) }
        return try {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}" +
                "&current=temperature_2m,weather_code,is_day" +
                "&hourly=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min" +
                "&timezone=auto&forecast_days=2"
            val json = JSONObject(get(url))
            val current = json.getJSONObject("current")
            val daily = json.getJSONObject("daily")
            val hourly = json.getJSONObject("hourly")
            val times = hourly.getJSONArray("time")
            val temps = hourly.getJSONArray("temperature_2m")
            val codes = hourly.getJSONArray("weather_code")
            val nowIso = current.getString("time").take(13) // "2026-10-20T19"
            var start = 0
            for (i in 0 until times.length()) if (times.getString(i).take(13) >= nowIso) { start = i; break }
            val hours = (start + 1 until minOf(start + 7, times.length())).map { i ->
                WeatherInfo.Hour(times.getString(i).substring(11, 13), temps.getDouble(i).round(), codes.getInt(i))
            }
            val info = WeatherInfo(
                place = place.name,
                temp = current.getDouble("temperature_2m").round(),
                code = current.getInt("weather_code"),
                isDay = current.optInt("is_day", 1) == 1,
                high = daily.getJSONArray("temperature_2m_max").getDouble(0).round(),
                low = daily.getJSONArray("temperature_2m_min").getDouble(0).round(),
                hours = hours,
                fetchedAt = System.currentTimeMillis(),
            )
            cached = info
            cachedKey = key
            Result.Ok(info)
        } catch (e: Exception) {
            cached?.let { Result.Ok(it) } ?: Result.Problem("Couldn't load the weather")
        }
    }

    private data class Place(val name: String, val lat: Double, val lon: Double)

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun resolvePlace(context: Context, city: String): Place? {
        if (city.isNotBlank()) {
            return runCatching {
                val json = JSONObject(
                    get("https://geocoding-api.open-meteo.com/v1/search?count=1&name=" + URLEncoder.encode(city, "UTF-8"))
                )
                val r = json.getJSONArray("results").getJSONObject(0)
                Place(r.getString("name"), r.getDouble("latitude"), r.getDouble("longitude"))
            }.getOrNull()
        }
        if (!hasLocationPermission(context)) return null
        val lm = context.getSystemService(LocationManager::class.java)
        val loc = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time } ?: return null
        val name = runCatching {
            Geocoder(context).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()?.let {
                it.locality ?: it.subAdminArea ?: it.adminArea
            }
        }.getOrNull() ?: "Here"
        return Place(name, loc.latitude, loc.longitude)
    }

    private fun Double.round() = Math.round(this).toInt()

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        try {
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** WMO weather code → (label, emoji). */
    fun describe(code: Int, isDay: Boolean = true): Pair<String, String> = when (code) {
        0 -> "Clear" to if (isDay) "☀️" else "🌙"
        1 -> "Mostly clear" to if (isDay) "🌤️" else "🌙"
        2 -> "Partly cloudy" to "⛅"
        3 -> "Cloudy" to "☁️"
        45, 48 -> "Fog" to "🌫️"
        51, 53, 55, 56, 57 -> "Drizzle" to "🌦️"
        61, 63, 65, 66, 67 -> "Rain" to "🌧️"
        71, 73, 75, 77 -> "Snow" to "🌨️"
        80, 81, 82 -> "Showers" to "🌦️"
        85, 86 -> "Snow showers" to "🌨️"
        95, 96, 99 -> "Thunderstorm" to "⛈️"
        else -> "—" to "🌡️"
    }
}

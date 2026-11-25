package dev.veil.launcher.data

import android.content.Context
import dev.veil.launcher.BuildConfig
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.roundToInt

/**
 * Current weather from Open-Meteo: free, no account, no API key. The location is either a city picked on
 * the TV (saved in app storage) or the default city from keys.properties. No device location or IP
 * geolocation is used.
 */
class Weather(context: Context) {
    /** Ravine: Fahrenheit where that's the norm (TV region US, Liberia, Myanmar), Celsius elsewhere. */
    private val fahrenheit: Boolean = java.util.Locale.getDefault().country in setOf("US", "LR", "MM")

    enum class Kind { CLEAR, PARTLY, CLOUDY, FOG, DRIZZLE, RAIN, SNOW, THUNDER }
    /** [tempC] is in the TV region's unit (°F in the US), despite the name kept from Veil. */
    data class Now(val tempC: Int, val kind: Kind, val isDay: Boolean)
    data class Place(val name: String, val region: String, val lat: Double, val lon: Double)

    private val prefs = context.getSharedPreferences("weather", Context.MODE_PRIVATE)
    private val defaultCity = BuildConfig.WEATHER_CITY.trim()

    val hasOverride: Boolean get() = prefs.contains("override_name")
    val enabled: Boolean get() = hasOverride || defaultCity.isNotEmpty()
    val defaultName: String get() = defaultCity

    /** Name of the place the weather is for. */
    val placeName: String?
        get() = prefs.getString("override_name", null) ?: defaultCity.takeIf { it.isNotEmpty() }

    fun setOverride(place: Place) {
        prefs.edit().putString("override_name", place.name)
            .putFloat("override_lat", place.lat.toFloat()).putFloat("override_lon", place.lon.toFloat()).apply()
    }

    fun clearOverride() {
        prefs.edit().remove("override_name").remove("override_lat").remove("override_lon").apply()
    }

    fun current(): Now? {
        if (!enabled) return null
        val (lat, lon) = location() ?: return null
        val (code, text) = runCatching {
            Http.call(
                "GET",
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code,is_day&temperature_unit=${if (fahrenheit) "fahrenheit" else "celsius"}",
            )
        }.getOrNull() ?: return null
        return if (code == 200) parseCurrent(text) else null
    }

    private fun location(): Pair<Double, Double>? {
        if (hasOverride) return prefs.getFloat("override_lat", 0f).toDouble() to prefs.getFloat("override_lon", 0f).toDouble()
        // Default city → coordinates, cached so the lookup only happens once per city.
        if (prefs.getString("city", null) == defaultCity) {
            return prefs.getFloat("lat", 0f).toDouble() to prefs.getFloat("lon", 0f).toDouble()
        }
        val p = search(defaultCity, 1).firstOrNull() ?: return null
        prefs.edit().putString("city", defaultCity).putFloat("lat", p.lat.toFloat()).putFloat("lon", p.lon.toFloat()).apply()
        return p.lat to p.lon
    }

    companion object {
        internal fun parseCurrent(text: String): Now? = runCatching {
            val current = JSONObject(text).obj("current") ?: return@runCatching null
            val temperature = current.optDouble("temperature_2m")
            if (!temperature.isFinite()) return@runCatching null
            Now(temperature.roundToInt(), kindOf(current.optInt("weather_code")), current.optInt("is_day", 1) == 1)
        }.getOrNull()

        /** City search via Open-Meteo's geocoding API. */
        fun search(query: String, count: Int = 6): List<Place> {
            if (query.isBlank()) return emptyList()
            val q = URLEncoder.encode(query.trim(), "UTF-8")
            val (code, text) = runCatching {
                Http.call("GET", "https://geocoding-api.open-meteo.com/v1/search?name=$q&count=$count&language=en&format=json")
            }.getOrNull() ?: return emptyList()
            if (code != 200) return emptyList()
            return runCatching {
                val results = JSONObject(text).optJSONArray("results") ?: return@runCatching emptyList()
                (0 until results.length()).mapNotNull { i ->
                    val place = results.optJSONObject(i) ?: return@mapNotNull null
                    val lat = place.optDouble("latitude")
                    val lon = place.optDouble("longitude")
                    if (!lat.isFinite() || !lon.isFinite()) return@mapNotNull null
                    Place(
                        name = place.str("name") ?: "?",
                        region = listOfNotNull(place.str("admin1"), place.str("country")).distinct().joinToString(", "),
                        lat = lat,
                        lon = lon,
                    )
                }
            }.getOrDefault(emptyList())
        }

        /** WMO weather interpretation codes. */
        private fun kindOf(code: Int) = when (code) {
            0 -> Kind.CLEAR
            1, 2 -> Kind.PARTLY
            3 -> Kind.CLOUDY
            45, 48 -> Kind.FOG
            in 51..57 -> Kind.DRIZZLE
            in 61..67, in 80..82 -> Kind.RAIN
            in 71..77, 85, 86 -> Kind.SNOW
            in 95..99 -> Kind.THUNDER
            else -> Kind.CLOUDY
        }
    }
}

package dev.veil.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherTest {
    @Test fun malformedOrIncompleteResponseIsIgnored() {
        assertNull(Weather.parseCurrent("<html>temporarily unavailable</html>"))
        assertNull(Weather.parseCurrent("""{"current":{"temperature_2m":"invalid"}}"""))
        assertNull(Weather.parseCurrent("""{"current":[]}"""))
    }

    @Test fun validWeatherStillParses() {
        assertEquals(
            Weather.Now(58, Weather.Kind.RAIN, false),
            Weather.parseCurrent("""{"current":{"temperature_2m":58.2,"weather_code":63,"is_day":0}}"""),
        )
    }
}

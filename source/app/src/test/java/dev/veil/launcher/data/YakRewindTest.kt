package dev.veil.launcher.data

import dev.veil.launcher.data.YakRewind.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class YakRewindTest {
    private val la = ZoneId.of("America/Los_Angeles")
    private fun at(y: Int, m: Int, d: Int, h: Int) = ZonedDateTime.of(y, m, d, h, 0, 0, 0, la).toInstant().toEpochMilli()

    @Test fun schedule() {
        val sat = at(2026, 11, 21, 10); val friNight = at(2026, 11, 20, 19); val friDay = at(2026, 11, 20, 14); val tueNight = at(2026, 11, 24, 20)
        assertFalse(YakRewind.allowed(Schedule.OFF, sat))
        assertTrue(YakRewind.allowed(Schedule.WEEKENDS, sat))
        assertFalse(YakRewind.allowed(Schedule.WEEKENDS, friNight))
        assertTrue(YakRewind.allowed(Schedule.WEEKENDS_FRIDAY, friNight))
        assertFalse(YakRewind.allowed(Schedule.WEEKENDS_FRIDAY, friDay))
        assertFalse(YakRewind.allowed(Schedule.WEEKENDS_FRIDAY, tueNight))
        assertTrue(YakRewind.allowed(Schedule.EVERY_NIGHT, tueNight))
        assertFalse(YakRewind.allowed(Schedule.EVERY_NIGHT, friDay))
    }

    @Test fun parse() {
        val p = YakRewind.parse("""{"pick":{"id":"5xyQY2sbEXE","t":"The Episode That Saved Season 7 | The Yak 3-4-22","d":"2022-03-04","m":112,"v":289069},"left":597}""")!!
        assertEquals("5xyQY2sbEXE", p.id)
        assertEquals(597, p.left)
        assertNull(YakRewind.parse("""{"pick":null,"left":0}"""))
        val item = YakRewind.toItem(p)
        assertEquals("3-4-22 · The Episode That Saved Season 7", item.subtitle)
        assertTrue(item.rewind)
    }

    @Test fun titles() {
        assertEquals("Nick's LGBT Glasses", YakRewind.shortTitle("Nick's LGBT Glasses | The Yak Full Episode 2-4-21"))
        assertEquals("The Yak PB&J Draft", YakRewind.shortTitle("The Yak PB&J Draft | Full Episode 3-26-21"))
        assertEquals("The BLT Draft & Lil Sas's Blind Taste Test", YakRewind.shortTitle("The BLT Draft & Lil Sas's Blind Taste Test"))
    }
}

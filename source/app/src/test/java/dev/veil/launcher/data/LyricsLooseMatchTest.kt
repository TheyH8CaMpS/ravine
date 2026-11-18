package dev.veil.launcher.data

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * "the cops are coming" (Junior Mesa) on LRCLIB exists at 194 s and 173 s. A Spotify cut of another length
 * matched neither within 6 s, so no lyrics showed; the loose pass (25 s) should find the closest one.
 */
class LyricsLooseMatchTest {
    private val results = JSONArray(
        """[
          {"id":38034585,"trackName":"the cops are coming","artistName":"Junior Mesa","duration":194.0,"hasSyncedLyrics":true,"hasPlainLyrics":true},
          {"id":39880888,"trackName":"the cops are coming","artistName":"Junior Mesa","duration":173.0,"hasSyncedLyrics":true,"hasPlainLyrics":true},
          {"id":1,"trackName":"The Cops Are Coming","artistName":"The Outcasts","duration":195.2,"hasPlainLyrics":true}
        ]""",
    )

    @Test fun strictPassRejectsOtherCuts() {
        assertNull(Lyrics.pickBest(results, "the cops are coming", "Junior Mesa", wantSec = 184))
    }

    @Test fun loosePassFindsClosestCutOfTheRightArtist() {
        val near = Lyrics.pickBest(results, "the cops are coming", "Junior Mesa", wantSec = 184, maxOff = 25.0)
        assertNotNull(near)
        assertEquals("Junior Mesa", near!!.getString("artistName"))
    }

    @Test fun stripsLrcTimeTags() {
        assertEquals("Stop! Can i look at you?\nWhat's in my way?", Lyrics.stripLrc("[00:12.34] Stop! Can i look at you?\n[00:15.80]What's in my way?"))
    }
}

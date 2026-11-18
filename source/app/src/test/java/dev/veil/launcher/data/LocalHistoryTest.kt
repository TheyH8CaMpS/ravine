package dev.veil.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nuvio's media-session subtitle formats (PlayerMediaSessionMetadata.kt in NuvioMedia/NuvioTV). */
class LocalHistoryTest {
    @Test fun parsesNuvioEpisodeSubtitle() {
        assertEquals(Triple(1, 6, "Hangover"), LocalHistory.parseEpisode("S1:E6 – Hangover"))
        assertEquals(Triple(12, 3, null), LocalHistory.parseEpisode("S12:E3"))
        assertEquals(Triple(2, 10, "Part - Two"), LocalHistory.parseEpisode("S2:E10 - Part - Two"))
    }

    @Test fun filmSubtitlesAreNotEpisodes() {
        assertNull(LocalHistory.parseEpisode("2024"))
        assertNull(LocalHistory.parseEpisode(null))
        assertNull(LocalHistory.parseEpisode(""))
    }

    @Test fun jsonRoundTrip() {
        val e = LocalHistory.Entry(
            title = "High Potential", isMovie = false, season = 1, episode = 6, episodeTitle = "Hangover",
            positionMs = 1_800_000, durationMs = 2_400_000, lastPlayed = 42, tmdb = 1, imdb = "tt1",
        )
        val back = LocalHistory.fromJson(LocalHistory.toJson(listOf(e))).single()
        assertEquals(e, back)
        assertFalse(back.finished)
        assertTrue(back.copy(positionMs = 2_300_000).finished)
    }
}

package dev.veil.launcher.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.OffsetDateTime

class ShowsAndUpdatesTest {
    private val feed = javaClass.getResourceAsStream("/shows/yak-feed-sample.xml")!!.bufferedReader().readText()
    private fun at(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    // Real Barstool Yak feed (trimmed): full shows are "… | The Yak 11-18-26"; "Best of The Yak …" and clips are not.
    @Test fun picksTodaysFullShowNotBestOfOrClips() {
        val e = YouTubeShows.latestEpisode(YouTubeShows.THE_YAK, feed, now = at("2026-11-19T01:00:00Z"))!!
        assertEquals("ezKrO5NNDuE", e.videoId)
        assertEquals("11-18-26", e.dateLabel)
        assertEquals("Brandon Is Sick And Tired Of Being Ignored In The Yak Group Chat", e.title)
    }

    @Test fun olderThanTwoDaysIsNotShown() {
        assertNull(YouTubeShows.latestEpisode(YouTubeShows.THE_YAK, feed, now = at("2026-11-21T12:00:00Z")))
    }

    @Test fun cardOpensTheVideo() {
        val e = YouTubeShows.latestEpisode(YouTubeShows.THE_YAK, feed, now = at("2026-11-19T01:00:00Z"))!!
        val item = YouTubeShows.toItem(e, isNew = true)
        assertEquals("yt:ezKrO5NNDuE", item.key)
        assertEquals("11-18-26 · Brandon Is Sick And Tired Of Being Ignored In The Yak Group Chat", item.episodeLine)
    }

    @Test fun releaseBuildNumbers() {
        assertEquals(20, Updates.buildOf("v0.1.0-build20"))
        assertNull(Updates.buildOf("v0.1.0"))
        assertEquals(19, Updates.currentBuild(119))
        val r = Updates.parse(JSONObject("""{"tag_name":"v0.1.0-build20","name":"Ravine 0.1.0 (build 20)",
            "assets":[{"name":"ravine.apk","browser_download_url":"https://github.com/TheyH8CaMpS/ravine/releases/download/v0.1.0-build20/ravine.apk"}]}"""))!!
        assertEquals(20, r.build)
        assertNull(Updates.parse(JSONObject("""{"tag_name":"v0.1.0-build20","assets":[]}""")))
    }
}

package dev.veil.launcher.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private fun release(tag: String, name: String = "") = JSONObject()
        .put("tag_name", tag).put("name", name)
        .put("assets", JSONArray().put(JSONObject()
            .put("name", "ravine.apk").put("browser_download_url", "https://example.invalid/ravine.apk")))

    @Test fun plainVersionReleaseIsRecognized() {
        val latest = Updates.parse(release("v0.1.1", "Ravine 0.1.1 (build 53)"))!!
        assertTrue(latest.isNewerThan("0.1.0", 147))
        assertTrue(latest.isNewerThan("0.1.1", 152))
        assertFalse(latest.isNewerThan("0.1.1", 153))
        assertFalse(latest.isNewerThan("0.1.1", 154))
        assertEquals("Build 53", latest.label)
    }

    @Test fun buildTagsCompareVersionFirstThenBuild() {
        val latest = Updates.parse(release("v0.10.0-build20", "Ravine 0.10.0 (build 99)"))!!
        assertTrue(latest.isNewerThan("0.9.9", 199))
        assertTrue(latest.isNewerThan("0.10.0", 119))
        assertFalse(latest.isNewerThan("0.10.0", 120))
        assertFalse(latest.isNewerThan("0.10.1", 101))
    }

    @Test fun plainTagWithoutBuildStillUpdatesAnOlderVersion() {
        val latest = Updates.parse(release("v1.0.0"))!!
        assertTrue(latest.isNewerThan("0.12.9", 199))
        assertFalse(latest.isNewerThan("1.0.0", 147))
        assertEquals("v1.0.0", latest.label)
    }

    @Test fun updateRequiresAValidTagAndApk() {
        assertNull(Updates.parse(release("v0.1.1-beta-build54")))
        assertNull(Updates.parse(JSONObject("""{"tag_name":"v0.1.0-build20","assets":[]}""")))
    }
}

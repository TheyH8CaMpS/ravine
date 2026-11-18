package dev.veil.launcher.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Courtside's GET /api/tv/games response (TheyH8CaMpS/courtside#201) → the strip's channel label. */
class CourtsideGuideTest {
    private val body = JSONObject(
        """{"date":"2026-11-18","zone":"America/Los_Angeles","guide":"fresh","games":[
          {"eventId":"401","league":"NBA","team":"lakers","name":"Utah Jazz at Los Angeles Lakers","date":"2026-11-19T03:00Z",
           "state":"pre","status":"11/18 - 10:00 PM EST","channels":[
             {"name":"US: Spectrum SportsNet LA FHD","number":"512","group":"USA Sports","quality":"FHD","match":"guide"},
             {"name":"US: NBA TV","number":"","group":"USA Sports","quality":"HD","match":"network"}]},
          {"eventId":"402","league":"NBA","team":"lakers","name":"x","date":"2026-11-21T03:00Z","state":"pre","status":"","channels":[]}
        ]}""",
    )

    @Test fun picksTopChannelForTheEvent() = assertEquals("512 · Spectrum SportsNet LA", CourtsideGuide.bestChannel(body, "401"))

    @Test fun noChannelsOrOtherEvent() {
        assertNull(CourtsideGuide.bestChannel(body, "402"))
        assertNull(CourtsideGuide.bestChannel(body, "999"))
    }

    @Test fun cleansProviderPrefixesAndQuality() {
        assertEquals("NBA TV", CourtsideGuide.cleanName("US: NBA TV"))
        assertEquals("FOX 11 Los Angeles", CourtsideGuide.cleanName("USA | FOX 11 Los Angeles HD Backup"))
        assertEquals("ESPN", CourtsideGuide.cleanName("ESPN"))
    }
}

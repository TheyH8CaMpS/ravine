package dev.veil.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Courtside's TV scores format (courtside lib/tv-scores.ts), parsed as the Scores panel reads it. */
class CourtsideScoresTest {
    private val scores = """{"games":[
      {"id":"NFL-1","eventId":"1","feed":"NFL","league":"NFL","date":"2026-11-22T21:25Z","state":"in","status":"4:12 - 3rd",
       "favorite":true,"pick":"Close game","situation":"DEN ball, 2nd & 6 at LV 34",
       "channel":{"name":"US: CBS Los Angeles HD","number":"2"},
       "teams":[{"id":"13","abbr":"LV","name":"Raiders","logo":"https://a/lv.png","color":"#a5acaf","score":"17","record":"4-6"},
                {"id":"7","abbr":"DEN","name":"Broncos","logo":null,"color":"bad","score":"20","record":"8-2"}]},
      {"id":"NFL-5","eventId":"5","feed":"NFL","league":"NFL","date":"2026-11-22T18:00:00Z","state":"post","status":"Final","favorite":false,"channel":null,
       "teams":[{"id":"3","abbr":"CHI","name":"Bears","color":"#c83803","score":"27","result":"win"},{"id":"9","abbr":"GB","name":"Packers","color":"#ffb612","score":"23","result":"loss"}]}]}"""

    @Test fun parsesGames() {
        val (live, done) = CourtsideScores.parseScores(scores)
        assertTrue(live.live && live.favorite)
        assertEquals("2 · CBS Los Angeles", live.channel)
        assertEquals(java.time.Instant.parse("2026-11-22T21:25:00Z").toEpochMilli(), live.startMs)
        assertEquals(0xFFA5ACAF, live.teams[0].color)
        assertEquals(0xFFD8D8DC, live.teams[1].color) // unreadable colour → neutral
        assertNull(live.teams[1].logo)
        assertTrue(done.final)
        assertNull(done.channel)
        assertEquals("loss", done.teams[1].result)
        assertEquals("Raiders", live.team("13")?.name)
    }

    @Test fun parsesDetailAndStandings() {
        val d = CourtsideScores.parseDetail(
            """{"teamIds":["13","7"],"compare":[{"label":"Total Yards","values":["288","311"],"bars":[93,100],"winner":"home"},{"label":"Possession","values":["19:02","20:46"]}],
               "performers":[{"teamId":"13","label":"Passing Yards","athlete":"G. Smith","line":"17/24"}],
               "moments":[{"teamId":"7","kind":"score","period":"3","clock":"8:51","text":"TD","score":"17–20"},{"kind":"turnover","period":"2","clock":"6:10","text":"INT","score":null}]}""",
        )
        assertEquals(listOf(93, 100), d.compare[0].bars)
        assertNull(d.compare[1].bars)
        assertEquals("G. Smith", d.performers[0].athlete)
        assertNull(d.moments[1].teamId)
        assertNull(d.moments[1].score)
        val s = CourtsideScores.parseStandings("""{"league":"NFL","standings":{"season":"2026","groups":[{"name":"AFC West","rows":[{"abbr":"DEN","name":"Broncos","record":"8-2","gamesBack":0,"streak":"W3"}]}]}}""")
        assertEquals("Broncos", s?.groups?.first()?.rows?.first()?.name)
        assertNull(CourtsideScores.parseStandings("""{"league":"NFL","standings":null}"""))
    }

    @Test fun periodLabels() {
        assertEquals("Q3", CourtsideScores.periodLabel("NFL", "3"))
        assertEquals("OT", CourtsideScores.periodLabel("NBA", "5"))
        assertEquals("P2", CourtsideScores.periodLabel("NHL", "2"))
        assertEquals("Bot 7", CourtsideScores.periodLabel("MLB", "Bottom 7"))
    }
}

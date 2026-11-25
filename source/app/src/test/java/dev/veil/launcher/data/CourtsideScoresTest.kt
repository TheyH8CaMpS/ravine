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

    // ravine#20: every colour in a list row comes from the API's per-team `color` (away first, home second).
    private val rows = """{"games":[
      {"id":"NBA-1","eventId":"1","feed":"NBA","league":"NBA","state":"in","status":"5:02 - 3rd","favorite":true,
       "teams":[{"id":"13","abbr":"LAL","name":"Lakers","color":"#8f6ac7","score":"88"},{"id":"26","abbr":"UTAH","name":"Jazz","color":"#4a7bd0","score":"81"}]},
      {"id":"NBA-2","eventId":"2","feed":"NBA","league":"NBA","state":"post","status":"Final","favorite":false,
       "teams":[{"id":"9","abbr":"GS","name":"Warriors","color":"#3f6fd1","score":"104","result":"loss"},{"id":"24","abbr":"SA","name":"Spurs","color":"#c4ced4","score":"110","result":"win"}]},
      {"id":"NBA-3","eventId":"3","feed":"NBA","league":"NBA","state":"pre","status":"7:30 PM","favorite":false,
       "teams":[{"id":"2","abbr":"BOS","name":"Celtics","color":"#3aa35b","score":"0"},{"id":"18","abbr":"NY","name":"Knicks","color":"#2f6bc8","score":"0"}]}]}"""

    @Test fun rowColoursComeFromTheApiColour() {
        val (live, final, later) = CourtsideScores.parseScores(rows)

        val l = CourtsideScores.rowColors(live)
        assertEquals(listOf(0xFF8F6AC7 to 1f, 0xFF4A7BD0 to 1f), l.bars)
        assertEquals(listOf(0xFF8F6AC7, null), l.scores) // the leader's score in its colour
        assertEquals(0xFF4A7BD0, l.focusTint) // home team (second)

        val f = CourtsideScores.rowColors(final)
        assertEquals(listOf(0xFF3F6FD1 to 0.4f, 0xFFC4CED4 to 1f), f.bars) // loser's bar at 40%
        assertEquals(listOf<Long?>(null, null), f.scores) // only live games tint a score
        assertEquals(0xFFC4CED4, f.focusTint)

        val p = CourtsideScores.rowColors(later)
        assertEquals(listOf(0xFF3AA35B to 1f, 0xFF2F6BC8 to 1f), p.bars)
        assertEquals(listOf<Long?>(null, null), p.scores)
    }

    @Test fun tiedLiveGameTintsNoScore() {
        val tied = CourtsideScores.parseScores(rows).first().let { g -> g.copy(teams = g.teams.map { it.copy(score = "90") }) }
        assertEquals(listOf<Long?>(null, null), CourtsideScores.rowColors(tied).scores)
    }

    // ravine#22: Courtside's GET /api/tv/notable (courtside#249), from a fixture in its response shape.
    @Test fun parsesNotableLines() {
        val text = javaClass.getResourceAsStream("/courtside/tv-notable.json")!!.bufferedReader().readText()
        val rows = CourtsideScores.parseNotable(text)
        assertEquals(listOf("Luka Doncic", "Brock Bowers", "Cam Thomas", "Kirill Kaprizov"), rows.map { it.name })
        val luka = rows[0]
        assertEquals("LAL", luka.team)
        assertEquals(0xFF8F6AC7, luka.teamColor)
        assertEquals("Triple-double", luka.label)
        assertEquals("achievement", luka.type)
        assertEquals("34 PTS · 12 REB · 11 AST", luka.statLine)
        assertEquals("W 121–110 vs UTAH", luka.context)
        assertTrue(luka.onRoster)
        assertEquals("NBA" to "401810101", luka.feed to luka.eventId)
        assertEquals("rough", rows[2].type)
        assertNull(rows[2].context) // empty context → none
        assertEquals(0xFFD8D8DC, rows[3].teamColor) // unreadable colour → neutral
        assertTrue(!rows[3].onRoster) // missing → false
        assertTrue(CourtsideScores.parseNotable("[]").isEmpty())
    }
}

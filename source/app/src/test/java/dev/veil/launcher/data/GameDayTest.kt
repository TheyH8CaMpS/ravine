package dev.veil.launcher.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

/** Game-day parsing and selection, against trimmed real ESPN responses (src/test/resources/gameday). */
class GameDayTest {
    private fun json(name: String) = JSONObject(javaClass.getResourceAsStream("/gameday/$name")!!.bufferedReader().readText())
    private fun at(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    @Test fun parsesScheduleWithObjectScores() {
        val games = EspnParse.events(json("lakers-schedule-sample.json"), MyTeams.LAKERS)
        assertEquals(3, games.size)
        val final = games[0]
        assertEquals(Game.State.FINAL, final.state)
        assertEquals("Final/OT", final.detail)
        assertTrue(final.home)
        assertTrue(final.ourScore != null && final.theirScore != null)
        val next = games[1]
        assertEquals(Game.State.PRE, next.state)
        assertEquals("Jazz", next.opponent)
        assertEquals("Lakers vs Jazz", next.matchup)
        assertEquals(at("2026-11-19T03:00Z"), next.startMs)
        assertEquals("Spectrum Sports Net", next.broadcast)
    }

    @Test fun parsesLiveScoreboardWithStringScores() {
        val live = EspnParse.events(json("nba-scoreboard-live-sample.json"), MyTeams.LAKERS).single()
        assertEquals(Game.State.LIVE, live.state)
        assertEquals(54, live.ourScore)
        assertEquals(50, live.theirScore)
        assertEquals("4:12 - 3rd", live.detail)
    }

    @Test fun homeGameIsOnlyLiveWhileItsScoreIsFresh() {
        val live = EspnParse.events(json("nba-scoreboard-live-sample.json"), MyTeams.LAKERS).single()
        val refreshed = live.copy(refreshedAt = 1_000_000)
        assertFalse(live.showsLive(1_000_000))
        assertTrue(refreshed.showsLive(1_090_000))
        assertFalse(refreshed.showsLive(1_090_001))
        assertFalse(refreshed.copy(state = Game.State.FINAL).showsLive(1_000_001))
    }

    @Test fun picksLiveThenRecentFinalThenUpcoming() {
        val games = EspnParse.events(json("lakers-schedule-sample.json"), MyTeams.LAKERS)
        // The morning of Nov 18 (Pacific): last night's final is over, tonight's game is "up next".
        val morning = at("2026-11-18T16:00Z")
        assertEquals("Jazz", GameDay.pick(games, morning)?.opponent)
        // Right after last night's game ended, its final still shows.
        val afterLastNight = at("2026-11-18T06:30Z")
        assertEquals(Game.State.FINAL, GameDay.pick(games, afterLastNight)?.state)
        // A live game beats everything.
        val live = EspnParse.events(json("nba-scoreboard-live-sample.json"), MyTeams.LAKERS)
        assertEquals(Game.State.LIVE, GameDay.pick(games + live, morning)?.state)
        // Nothing within 18 hours: no strip.
        assertNull(GameDay.pick(games.filter { it.state != Game.State.FINAL }, at("2026-11-17T12:00Z")))
    }

    @Test fun gameDayWindow() {
        val next = EspnParse.events(json("lakers-schedule-sample.json"), MyTeams.LAKERS)[1]
        assertFalse(next.inWindow(next.startMs - 4 * 60 * 60_000L))
        assertTrue(next.inWindow(next.startMs - 2 * 60 * 60_000L))
    }

    // ESPN ids repeat across leagues (Lakers and Raiders are both 13), so responses must be parsed only for
    // the league they were fetched for; within a league, other teams' games are ignored.
    @Test fun ignoresEventsWithoutOurTeam() {
        assertTrue(EspnParse.events(json("nba-scoreboard-live-sample.json"), MyTeams.DODGERS).isEmpty())
    }
}

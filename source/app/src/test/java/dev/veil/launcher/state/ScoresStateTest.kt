package dev.veil.launcher.state

import dev.veil.launcher.data.CourtsideScores
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** ravine#19: the Scores panel shows today's last scoreboard at once, never an earlier day's. */
class ScoresStateTest {
    @get:Rule val tmp = TemporaryFolder()

    private val today = LocalDate.parse("2026-11-25")
    private fun board(vararg ids: String) = ids.joinToString(",", """{"games":[""", "]}") { id ->
        """{"id":"$id","eventId":"$id","feed":"NBA","league":"NBA","date":"2026-11-25T19:00:00Z","state":"pre","status":"7:00 PM",
           "favorite":true,"teams":[{"id":"13","abbr":"LAL","name":"Lakers","score":"0"},{"id":"26","abbr":"UTAH","name":"Jazz","score":"0"}]}"""
    }

    /** A state whose network call blocks until [gate] opens, so we can look at it before Courtside answers. */
    private fun state(file: File, gate: CountDownLatch, fresh: String = board("NBA-2")) =
        ScoresState(
            CoroutineScope(Dispatchers.Unconfined),
            fetcher = { gate.await(5, TimeUnit.SECONDS); fresh },
            configured = { true },
            cache = ScoresCache(file),
            today = { today },
        )

    private fun waitUntil(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(10)
    }

    @Test fun todaysCacheShowsBeforeTheNetworkAnswers() {
        val file = tmp.newFile("scores.json")
        ScoresCache(file).save(today, board("NBA-1"))
        val gate = CountDownLatch(1)
        val s = state(file, gate)

        s.show()
        assertEquals(listOf("NBA-1"), s.games.map { it.id })
        assertTrue(s.updating)
        assertFalse(s.failed)

        gate.countDown()
        waitUntil { !s.loading }
        assertEquals(listOf("NBA-2"), s.games.map { it.id })
        assertFalse(s.updating)
        assertEquals(board("NBA-2"), ScoresCache(file).load(today))
    }

    @Test fun yesterdaysCacheIsIgnored() {
        val file = tmp.newFile("scores.json")
        ScoresCache(file).save(today.minusDays(1), board("NBA-0"))
        val gate = CountDownLatch(1)
        val s = state(file, gate)

        s.show()
        assertTrue(s.games.isEmpty())
        assertFalse(s.updating)
        gate.countDown()
        waitUntil { !s.loading }
        assertEquals(listOf("NBA-2"), s.games.map { it.id })
    }

    @Test fun cacheIsTaggedWithItsDay() {
        val c = ScoresCache(File(tmp.root, "scores.json"))
        assertNull(c.load(today))
        c.save(today, "{\"games\":[]}")
        assertEquals("{\"games\":[]}", c.load(today))
        assertNull(c.load(today.plusDays(1)))
    }

    @Test fun legacyScoreboardsUseTheirExistingFileTime() {
        val legacy = tmp.newFile("legacy-scores.json").apply {
            writeText("$today\n${board("NBA-1")}")
        }
        val cache = ScoresCache(legacy)
        assertEquals(board("NBA-1"), cache.load(today))
        assertEquals(legacy.lastModified(), cache.read(today)?.fetchedAt)
    }

    @Test fun unreachableRefreshKeepsItsAsOfTimeAndStopsHighlightingLiveScores() {
        val savedAt = 2_000_000L
        val cache = ScoresCache(tmp.newFile("stale.json"))
        val live = board("NBA-1").replace("\"pre\"", "\"in\"").replaceFirst("\"score\":\"0\"", "\"score\":\"10\"")
        cache.save(today, live, savedAt)
        val s = ScoresState(CoroutineScope(Dispatchers.Unconfined), { null }, { true }, cache, { today })

        s.show()
        waitUntil { !s.loading }
        val game = s.games.single()
        assertNull(s.asOfLabel(savedAt + 90_000) { "7:42 PM" })
        assertEquals("As of 7:42 PM", s.asOfLabel(savedAt + 90_001) { stamp ->
            assertEquals(savedAt, stamp)
            "7:42 PM"
        })
        assertTrue(CourtsideScores.rowColors(game).scores.any { it != null })
        assertEquals(listOf<Long?>(null, null), CourtsideScores.rowColors(game, stale = true).scores)
        assertTrue(game.showsLive(stale = false))
        assertFalse(game.showsLive(stale = true))
        assertTrue(game.canWatch)
        assertFalse(game.copy(state = "post").canWatch)
    }

    @Test fun detailTimesOutEvenWhileTheNetworkCallIsStillBlocked() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val s = ScoresState(
            CoroutineScope(Dispatchers.Unconfined),
            fetcher = { started.countDown(); release.await(5, TimeUnit.SECONDS); null },
            configured = { true },
            cache = ScoresCache(tmp.newFile("detail.json")),
            today = { today },
            pageTimeoutMs = 80,
        )
        try {
            s.openGame(CourtsideScores.parseScores(board("NBA-1")).single())
            assertTrue(started.await(5, TimeUnit.SECONDS))
            waitUntil { s.detailFailed }
            assertTrue(s.detailFailed)
            assertNull(s.detail)
            assertEquals(1L, release.count)
        } finally {
            release.countDown()
        }
    }

    @Test fun standingsFailureCanBeRetried() {
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val online = java.util.concurrent.atomic.AtomicBoolean()
        val s = ScoresState(
            CoroutineScope(Dispatchers.Unconfined),
            fetcher = {
                attempts.incrementAndGet()
                if (online.get()) """{"standings":{"groups":[{"name":"Fixture","rows":[{"name":"Fixture team"}]}]}}""" else null
            },
            configured = { true },
            cache = ScoresCache(tmp.newFile("standings.json")),
            today = { today },
        )
        s.selectTab(ScoresState.STANDINGS)
        waitUntil { s.standingsFailed }
        assertTrue(s.standingsFailed)
        online.set(true)
        s.loadStandings()
        waitUntil { s.standings.isNotEmpty() }
        assertFalse(s.standingsFailed)
        assertTrue(s.standings.isNotEmpty())
        assertTrue(attempts.get() > 4)
    }

    @Test fun standingsUsesTheFixedLeagueOrderAndRequiresAnInSeasonMlbResponse() {
        for (inSeason in listOf(false, null, true)) {
            val asked = mutableListOf<String>()
            val s = ScoresState(
                CoroutineScope(Dispatchers.Unconfined),
                fetcher = { path ->
                    when {
                        path.startsWith("/api/tv/standings") -> {
                            asked += path.substringAfter("league=")
                            val seasonFlag = inSeason?.let { """"inSeason":$it,""" }.orEmpty()
                            """{$seasonFlag"standings":{"groups":[{"name":"Fixture","rows":[{"name":"Fixture team"}]}]}}"""
                        }
                        path.startsWith("/api/tv/scores") -> board("NHL-1").replace("\"NBA\"", "\"NHL\"")
                        else -> "[]"
                    }
                },
                configured = { true },
                cache = ScoresCache(File(tmp.newFolder(), "scores.json")),
                today = { today },
            )
            s.show()
            waitUntil { !s.loading }
            assertEquals("NHL", s.games.single().league)
            s.selectTab(ScoresState.STANDINGS)
            waitUntil { s.standings.isNotEmpty() }

            assertEquals(listOf("NFL", "NBA", "NCAAF", "MLB"), asked)
            val expected = listOf("NFL", "NBA", "NCAAF") + listOf("MLB").filter { inSeason == true }
            assertEquals(expected, s.standings.map { it.first })
        }
    }

    // ravine#22: Notable lines are today's, or last night's while today has none; the tab shows only with lines.
    private fun notableState(byDate: Map<String, String>, asked: MutableList<String>) = ScoresState(
        CoroutineScope(Dispatchers.Unconfined),
        fetcher = { path ->
            asked += path
            if (path.startsWith("/api/tv/notable")) byDate[path.substringAfter("date=").substringBefore('&')] else board("NBA-1")
        },
        configured = { true },
        cache = ScoresCache(File(tmp.root, "scores.json")),
        today = { today },
    )

    private val line = """[{"league":"NBA","name":"Luka Doncic","team":"LAL","teamColor":"#8F6AC7","label":"Triple-double",
        "type":"achievement","statLine":"34 PTS · 12 REB · 11 AST","context":"","onRoster":false,"feed":"NBA","eventId":"1"}]"""

    @Test fun notableFallsBackToLastNightWhenTodayIsEmpty() {
        val asked = mutableListOf<String>()
        val s = notableState(mapOf("2026-11-25" to "[]", "2026-11-24" to line), asked)
        s.show()
        waitUntil { s.notableLabel == "Last night" && s.games.isNotEmpty() }
        assertEquals("Last night", s.notableLabel)
        assertEquals(listOf("Luka Doncic"), s.notable.map { it.name })
        assertEquals(
            listOf("/api/tv/notable?date=2026-11-25&zone=America/Los_Angeles", "/api/tv/notable?date=2026-11-24&zone=America/Los_Angeles"),
            asked.filter { it.startsWith("/api/tv/notable") },
        )
        assertEquals(listOf(ScoresState.YOURS, "NBA", ScoresState.NOTABLE, ScoresState.STANDINGS), s.tabs)
    }

    @Test fun notableUsesTodayWhenItHasLines() {
        val asked = mutableListOf<String>()
        val s = notableState(mapOf("2026-11-25" to line), asked)
        s.show()
        waitUntil { s.notable.isNotEmpty() }
        assertEquals("Today", s.notableLabel)
        assertEquals(1, asked.count { it.startsWith("/api/tv/notable") })
    }

    @Test fun noNotableTabWithoutLines() {
        val s = notableState(mapOf("2026-11-25" to "[]", "2026-11-24" to "[]"), mutableListOf())
        s.show()
        waitUntil { !s.loading }
        Thread.sleep(50)
        assertTrue(s.notable.isEmpty())
        assertFalse(ScoresState.NOTABLE in s.tabs)
    }
}

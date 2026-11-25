package dev.veil.launcher.state

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
}

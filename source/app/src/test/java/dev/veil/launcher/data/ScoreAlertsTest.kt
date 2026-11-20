package dev.veil.launcher.data

import dev.veil.launcher.alerts.ScoreAlerts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreAlertsTest {
    private fun team(id: String, name: String, score: String, result: String? = null) =
        CourtsideScores.Team(id, name.take(3).uppercase(), name, null, 0xFF999999, score, "6-4", result)

    private fun game(
        league: String, a: Int, b: Int, favorite: Boolean = true, state: String = "in", status: String = "Q2 5:00",
        period: Int = 2, clutch: Boolean = false, notable: Pair<String, String>? = null, result: Pair<String?, String?> = null to null,
        names: Pair<String, String> = "Raiders" to "Broncos",
    ) = CourtsideScores.Score(
        "G", "1", league, league, 0, state, status, favorite, null, null, null,
        listOf(team("1", names.first, "$a", result.first), team("2", names.second, "$b", result.second)), period, clutch, notable,
    )

    private fun alerts(p: CourtsideScores.Score, c: CourtsideScores.Score, big: Boolean = true) =
        ScoreAlerts.detect(mapOf(p.id to p), listOf(c), big)

    @Test fun footballScores() {
        assertEquals("TOUCHDOWN · RAIDERS", alerts(game("NFL", 10, 13), game("NFL", 17, 13)).single().eyebrow)
        assertEquals("FIELD GOAL · BRONCOS", alerts(game("NFL", 10, 13), game("NFL", 10, 16)).single().eyebrow)
        assertTrue(alerts(game("NFL", 16, 13), game("NFL", 17, 13)).isEmpty()) // extra point alone
        assertEquals("Raiders 17 · Broncos 13", alerts(game("NFL", 10, 13), game("NFL", 17, 13)).single().line)
    }

    @Test fun basketballNeverEveryBasket() {
        assertTrue(alerts(game("NBA", 50, 48), game("NBA", 52, 48)).isEmpty())
        assertEquals("HALFTIME", alerts(game("NBA", 50, 48), game("NBA", 55, 50, status = "Halftime")).single().eyebrow)
        assertEquals("CLOSE FINISH", alerts(game("NBA", 90, 88, period = 4), game("NBA", 95, 93, period = 4, clutch = true)).single().eyebrow)
    }

    @Test fun finals() {
        val a = alerts(game("NBA", 110, 100, names = "Lakers" to "Celtics"),
            game("NBA", 118, 112, state = "post", status = "Final", names = "Lakers" to "Celtics", result = "win" to "loss")).single()
        assertEquals("FINAL · LAKERS WIN", a.eyebrow)
    }

    @Test fun otherGamesOnlyWhenNotableAndBigGamesOn() {
        val p = game("NFL", 10, 13, favorite = false, names = "Jets" to "Bills")
        assertTrue(alerts(p, game("NFL", 17, 13, favorite = false, names = "Jets" to "Bills")).isEmpty())
        val upset = game("NFL", 20, 17, favorite = false, state = "post", status = "Final", notable = "upset" to "Upset · 2-8 Jets beat 9-1 Bills",
            names = "Jets" to "Bills", result = "win" to "loss")
        assertEquals("UPSET · NFL", alerts(p, upset).single().eyebrow)
        assertTrue(alerts(p, upset, big = false).isEmpty())
        assertTrue(alerts(upset, upset).isEmpty()) // once
    }

    @Test fun firstSightIsSilent() {
        assertTrue(ScoreAlerts.detect(emptyMap(), listOf(game("NFL", 17, 13)), true).isEmpty())
    }
}

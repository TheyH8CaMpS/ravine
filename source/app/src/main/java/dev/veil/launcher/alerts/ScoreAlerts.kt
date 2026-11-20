package dev.veil.launcher.alerts

import dev.veil.launcher.data.CourtsideScores.Score
import dev.veil.launcher.data.CourtsideScores.Team
import dev.veil.launcher.data.MyTeams

/**
 * What deserves an alert (PROJECT.md D52). Pure: compares two Courtside snapshots, unit-tested.
 *
 * Your teams (Raiders, Dodgers, Lakers):
 *  - football: touchdowns, field goals, safeties (extra points fold into the touchdown);
 *  - baseball: runs; hockey: goals;
 *  - basketball: never every basket, only halftime, a close finish (Courtside's close-and-late) and the final;
 *  - every sport: the final, won or lost.
 * Other games (only if "big games" is on): Courtside's `notable`, i.e. real stakes late or at the end, or an upset.
 */
object ScoreAlerts {
    data class Alert(
        /** Each moment alerts once. */
        val key: String,
        val gameId: String,
        val favorite: Boolean,
        val eyebrow: String,
        val line: String,
        val color: Long,
        val logo: String?,
    )

    fun favoriteTeam(g: Score): Team? =
        g.teams.firstOrNull { t -> MyTeams.all.any { it.name.equals(t.name, true) } }

    fun line(g: Score) = g.teams.joinToString(" · ") { "${it.name} ${it.score}" }

    fun detect(prev: Map<String, Score>, cur: List<Score>, bigGames: Boolean): List<Alert> = cur.flatMap { g ->
        val p = prev[g.id] ?: return@flatMap emptyList() // first sight of a game: nothing to compare yet
        if (g.favorite) favorite(p, g) else if (bigGames) notable(p, g) else emptyList()
    }

    private fun favorite(p: Score, g: Score): List<Alert> {
        val mine = favoriteTeam(g)
        fun alert(kind: String, eyebrow: String, color: Long, logo: String?) =
            Alert("${g.id}:$kind", g.id, true, eyebrow, line(g), color, logo)
        if (g.final && !p.final) {
            val won = mine?.result == "win"
            val e = mine?.let { "FINAL · ${it.name.uppercase()} ${if (won) "WIN" else if (it.result == "loss") "LOSE" else ""}".trim() } ?: "FINAL"
            return listOf(alert("final", e, mine?.color ?: 0xFFD8D8DC, mine?.logo))
        }
        if (!g.live) return emptyList()
        return when (g.league) {
            "NBA", "WNBA" -> listOfNotNull(
                if (g.status.contains("half", true) && !p.status.contains("half", true))
                    alert("half", "HALFTIME", mine?.color ?: 0xFFD8D8DC, mine?.logo) else null,
                if (g.clutch && !p.clutch && g.period >= 4)
                    alert("clutch", "CLOSE FINISH", mine?.color ?: 0xFFD8D8DC, mine?.logo) else null,
            )
            else -> g.teams.mapNotNull { t ->
                val before = p.team(t.id)?.score?.toIntOrNull() ?: return@mapNotNull null
                val now = t.score.toIntOrNull() ?: return@mapNotNull null
                val d = now - before
                val what = scoreWord(g.league, d) ?: return@mapNotNull null
                Alert("${g.id}:${t.id}:$now", g.id, true, "$what · ${t.name.uppercase()}", line(g), t.color, t.logo)
            }
        }
    }

    /** "TOUCHDOWN", "2 RUNS", … for a score change of [d]; null when it isn't worth a note. */
    internal fun scoreWord(league: String, d: Int): String? = when {
        d <= 0 -> null
        league == "NFL" || league == "NCAAF" -> when (d) {
            1 -> null // the extra point after a touchdown already announced
            2 -> "SAFETY"
            3 -> "FIELD GOAL"
            in 6..8 -> "TOUCHDOWN"
            else -> "SCORE"
        }
        league == "MLB" -> if (d == 1) "RUN" else "$d RUNS"
        league == "NHL" || league == "EPL" || league == "MLS" -> if (d == 1) "GOAL" else "$d GOALS"
        else -> null
    }

    private fun notable(p: Score, g: Score): List<Alert> {
        val (kind, text) = g.notable ?: return emptyList()
        if (p.notable?.first == kind) return emptyList()
        val head = text.substringBefore(" · ").uppercase()
        val winner = g.teams.firstOrNull { it.result == "win" } ?: g.teams.firstOrNull()
        return listOf(
            Alert("${g.id}:$kind", g.id, false, "$head · ${dev.veil.launcher.data.CourtsideScores.leagueLabel(g.league).uppercase()}",
                line(g), winner?.color ?: 0xFFD8D8DC, winner?.logo),
        )
    }
}

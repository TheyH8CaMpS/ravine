package dev.veil.launcher.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Courtside's scores for the Scores panel (PROJECT.md D49): the day's games, one game's page and standings,
 * from Courtside's TV routes (/api/tv/scores, /api/tv/game, /api/tv/standings). Parsing is pure and unit-tested.
 */
object CourtsideScores {
    data class Team(
        val id: String, val abbr: String, val name: String, val logo: String?, val color: Long,
        val score: String, val record: String, val result: String?,
    )

    data class Score(
        val id: String, val eventId: String, val feed: String, val league: String, val startMs: Long,
        val state: String, val status: String, val favorite: Boolean, val pick: String?, val situation: String?,
        /** "2 · CBS Los Angeles", from the provider guide TiviMate uses. */
        val channel: String?, val teams: List<Team>,
    ) {
        val live get() = state == "in"
        val final get() = state == "post"
        fun team(id: String?) = teams.firstOrNull { it.id == id }
    }

    data class Compare(val label: String, val values: List<String>, val bars: List<Int>?, val winner: String?)
    data class Performer(val teamId: String, val label: String, val athlete: String, val line: String)
    data class Moment(val teamId: String?, val period: String, val clock: String, val text: String, val score: String?)
    data class Detail(val teamIds: List<String>, val compare: List<Compare>, val performers: List<Performer>, val moments: List<Moment>)

    data class StandingRow(val abbr: String, val name: String, val record: String, val gamesBack: String?, val points: String?, val streak: String?)
    data class StandingGroup(val name: String, val rows: List<StandingRow>)
    data class Standings(val season: String, val groups: List<StandingGroup>)

    /** Leagues in the order the tabs show them. */
    val LEAGUE_ORDER = listOf("NFL", "NBA", "NHL", "NCAAF", "MLB", "WNBA", "EPL", "MLS", "UFC")
    fun leagueLabel(league: String) = when (league) { "NCAAF" -> "College"; "EPL" -> "Premier League"; else -> league }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONObject.str(key: String): String? = optString(key, "").takeIf { has(key) && !isNull(key) && it.isNotBlank() }

    fun color(hex: String?): Long =
        hex?.removePrefix("#")?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { 0xFF000000 or it } ?: 0xFFD8D8DC

    fun startMs(iso: String?): Long = runCatching {
        // ESPN times can omit seconds ("2026-11-22T21:25Z").
        java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    }.getOrDefault(0L)

    fun parseScores(text: String): List<Score> = JSONObject(text).optJSONArray("games").objects().map { g ->
        val ch = g.optJSONObject("channel")
        Score(
            id = g.optString("id"), eventId = g.optString("eventId"), feed = g.optString("feed"),
            league = g.optString("league"), startMs = startMs(g.str("date")), state = g.optString("state"),
            status = g.optString("status"), favorite = g.optBoolean("favorite"), pick = g.str("pick"),
            situation = g.str("situation"),
            channel = ch?.let { c ->
                val name = CourtsideGuide.cleanName(c.optString("name"))
                val number = c.optString("number").trim()
                name.takeIf { it.isNotBlank() }?.let { if (number.isNotEmpty()) "$number · $it" else it }
            },
            teams = g.optJSONArray("teams").objects().map { t ->
                Team(
                    t.optString("id"), t.optString("abbr"), t.optString("name"), t.str("logo"), color(t.str("color")),
                    t.optString("score"), t.optString("record"), t.str("result"),
                )
            },
        )
    }

    fun parseDetail(text: String): Detail {
        val o = JSONObject(text)
        val ids = o.optJSONArray("teamIds")
        return Detail(
            teamIds = (0 until (ids?.length() ?: 0)).map { ids!!.optString(it) },
            compare = o.optJSONArray("compare").objects().map { c ->
                val v = c.optJSONArray("values"); val b = c.optJSONArray("bars")
                Compare(
                    c.optString("label"), (0 until (v?.length() ?: 0)).map { v!!.optString(it) },
                    b?.takeIf { it.length() == 2 }?.let { listOf(it.optInt(0), it.optInt(1)) }, c.str("winner"),
                )
            },
            performers = o.optJSONArray("performers").objects().map {
                Performer(it.optString("teamId"), it.optString("label"), it.optString("athlete"), it.optString("line"))
            },
            moments = o.optJSONArray("moments").objects().map {
                Moment(it.str("teamId"), it.optString("period"), it.optString("clock"), it.optString("text"), it.str("score"))
            },
        )
    }

    fun parseStandings(text: String): Standings? {
        val s = JSONObject(text).optJSONObject("standings") ?: return null
        return Standings(
            s.optString("season"),
            s.optJSONArray("groups").objects().map { g ->
                StandingGroup(g.optString("name"), g.optJSONArray("rows").objects().map { r ->
                    StandingRow(r.optString("abbr"), r.optString("name"), r.optString("record"), r.str("gamesBack"), r.str("points"), r.str("streak"))
                })
            },
        )
    }

    /** "Q3" style period labels for moments: football and basketball quarters, hockey periods, baseball halves. */
    fun periodLabel(feed: String, period: String): String = when {
        period.startsWith("Top") || period.startsWith("Bottom") -> period.replace("Bottom", "Bot")
        feed in setOf("NFL", "NCAAF", "NBA", "WNBA") -> period.toIntOrNull()?.let { if (it > 4) "OT" else "Q$it" } ?: period
        feed == "NHL" -> period.toIntOrNull()?.let { if (it > 3) "OT" else "P$it" } ?: period
        else -> period
    }
}

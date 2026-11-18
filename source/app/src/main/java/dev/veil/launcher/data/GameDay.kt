package dev.veil.launcher.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime

/**
 * Ravine game-day mode (PROJECT.md D27): on days the owner's teams play, Home shows the game (start time,
 * then the live score, then the final), wallpapers switch to the team's photos, and Ambient shows the score.
 *
 * Data comes from a [GameSource]. Today that's [EspnSource] (ESPN's public site API: free, no key, but
 * unofficial, so it may change). Courtside is meant to plug in here as another source: see docs/courtside.md.
 */
enum class League(val path: String, val label: String, /** Typical game length, for how long a final stays up. */ val lengthMs: Long) {
    MLB("baseball/mlb", "MLB", 3 * 60 * 60_000L),
    NBA("basketball/nba", "NBA", 150 * 60_000L),
    NFL("football/nfl", "NFL", 200 * 60_000L),
}

data class Team(
    val key: String,
    val league: League,
    /** ESPN's team id within its league. */
    val espnId: String,
    val name: String,
    /** The wallpaper feed's "location" for this team's photos. */
    val collection: String,
    /** Team colour (ARGB) for the dot on the game strip: the team's most readable colour on a dark photo. */
    val color: Long,
)

object MyTeams {
    val DODGERS = Team("dodgers", League.MLB, "19", "Dodgers", "Dodgers", 0xFF3E8EDE) // Dodger blue, lifted for dark screens
    val LAKERS = Team("lakers", League.NBA, "13", "Lakers", "Lakers", 0xFFFDB927) // gold (the purple vanishes on dark)
    val RAIDERS = Team("raiders", League.NFL, "13", "Raiders", "Raiders", 0xFFA5ACAF)
    val all = listOf(DODGERS, LAKERS, RAIDERS)
}

data class Game(
    val id: String,
    val team: Team,
    val opponent: String,
    val opponentAbbr: String,
    val home: Boolean,
    val startMs: Long,
    val state: State,
    /** ESPN's short status, e.g. "4:12 - 3rd", "Top 5th", "Final/OT". */
    val detail: String,
    val ourScore: Int?,
    val theirScore: Int?,
    val broadcast: String?,
) {
    enum class State { PRE, LIVE, FINAL }

    /** "Lakers vs Jazz" at home, "Raiders at Broncos" away. */
    val matchup: String get() = "${team.name} ${if (home) "vs" else "at"} $opponent"

    /** Game day for wallpapers and the strip: 3 h before the start until about an hour after the end. */
    fun inWindow(now: Long): Boolean = when (state) {
        State.LIVE -> true
        State.PRE -> now >= startMs - 3 * 60 * 60_000L
        State.FINAL -> now <= startMs + team.league.lengthMs + 60 * 60_000L
    }

    val won: Boolean? get() = if (state == State.FINAL && ourScore != null && theirScore != null) ourScore > theirScore else null
}

/** Where games come from. ESPN today; Courtside later (docs/courtside.md). */
interface GameSource {
    /** This team's games (any order); network, call off the main thread. */
    fun schedule(team: Team): List<Game>
    /** A fresher copy of [game] (live score), or null if unavailable. */
    fun refresh(game: Game): Game?
}

class EspnSource : GameSource {
    override fun schedule(team: Team): List<Game> {
        val (code, text) = Http.call("GET", "$API/${team.league.path}/teams/${team.espnId}/schedule")
        return if (code == 200) EspnParse.events(JSONObject(text), team) else emptyList()
    }

    override fun refresh(game: Game): Game? {
        val day = java.time.Instant.ofEpochMilli(game.startMs).atZone(java.time.ZoneId.of("America/New_York")).toLocalDate()
        val date = "%04d%02d%02d".format(day.year, day.monthValue, day.dayOfMonth)
        val (code, text) = Http.call("GET", "$API/${game.team.league.path}/scoreboard?dates=$date")
        if (code != 200) return null
        return EspnParse.events(JSONObject(text), game.team).firstOrNull { it.id == game.id }
    }

    private companion object { const val API = "https://site.api.espn.com/apis/site/v2/sports" }
}

/**
 * ESPN JSON → [Game]. Pure, so it's unit-tested against saved responses.
 * ESPN team ids are only unique within a league (Lakers and Raiders are both "13"): only pass a response
 * fetched for [Team.league] (EspnSource always does).
 */
object EspnParse {
    fun events(root: JSONObject, team: Team): List<Game> {
        val events = root.optJSONArray("events") ?: return emptyList()
        return (0 until events.length()).mapNotNull { i -> runCatching { event(events.getJSONObject(i), team) }.getOrNull() }
    }

    private fun event(e: JSONObject, team: Team): Game? {
        val c = e.optJSONArray("competitions")?.optJSONObject(0) ?: return null
        val competitors = c.optJSONArray("competitors") ?: return null
        val sides = (0 until competitors.length()).map { competitors.getJSONObject(it) }
        val us = sides.firstOrNull { it.optJSONObject("team")?.optString("id") == team.espnId } ?: return null
        val them = sides.firstOrNull { it !== us } ?: return null
        val type = c.optJSONObject("status")?.optJSONObject("type")
        val state = when (type?.optString("state")) {
            "in" -> Game.State.LIVE
            "post" -> Game.State.FINAL
            else -> Game.State.PRE
        }
        val opp = them.getJSONObject("team")
        return Game(
            id = e.getString("id"),
            team = team,
            opponent = opp.str("shortDisplayName") ?: opp.str("displayName") ?: opp.optString("abbreviation"),
            opponentAbbr = opp.optString("abbreviation"),
            home = us.optString("homeAway") == "home",
            startMs = OffsetDateTime.parse(e.getString("date")).toInstant().toEpochMilli(),
            state = state,
            detail = type?.str("shortDetail") ?: "",
            ourScore = score(us),
            theirScore = score(them),
            broadcast = c.optJSONArray("broadcasts")?.optJSONObject(0)?.optJSONObject("media")?.str("shortName"),
        )
    }

    /** Scoreboards give "54"; schedules give {"value":54.0,"displayValue":"54"}. */
    private fun score(side: JSONObject): Int? {
        if (!side.has("score") || side.isNull("score")) return null
        return when (val s = side.get("score")) {
            is JSONObject -> s.optString("displayValue").toIntOrNull() ?: s.optDouble("value").takeIf { !it.isNaN() }?.toInt()
            else -> s.toString().toIntOrNull()
        }
    }
}

/**
 * The current game across the owner's teams, shared by Home and the screensaver (same process). Call
 * [tick] about once a minute: schedules refresh every 3 h; a game in progress refreshes every minute,
 * one about to start every 5 minutes. Network: call off the main thread.
 */
object GameDay {
    var source: GameSource = EspnSource()

    @Volatile var current: Game? = null
        private set

    private val schedules = HashMap<String, List<Game>>()
    private var lastSchedule = 0L
    private var lastRefresh = 0L
    private var scheduleTeams: Set<String> = emptySet()

    fun enabledTeams(context: Context): Set<String> =
        context.getSharedPreferences("veil", Context.MODE_PRIVATE)
            .getStringSet("game_teams", null) ?: MyTeams.all.map { it.key }.toSet()

    /** Tests and previews: forget cached schedules. */
    @Synchronized internal fun reset() { schedules.clear(); lastSchedule = 0L; lastRefresh = 0L; scheduleTeams = emptySet(); current = null }

    @Synchronized
    fun tick(context: Context, now: Long = System.currentTimeMillis()): Game? {
        val teams = MyTeams.all.filter { it.key in enabledTeams(context) }
        val keys = teams.map { it.key }.toSet()
        if (keys != scheduleTeams || now - lastSchedule > 3 * 60 * 60_000L) {
            teams.forEach { t -> runCatching { source.schedule(t) }.getOrNull()?.let { schedules[t.key] = it } }
            schedules.keys.retainAll(keys)
            scheduleTeams = keys
            lastSchedule = now
            lastRefresh = now
        }
        var pick = pick(schedules.values.flatten(), now)
        if (pick != null) {
            val soon = pick.state == Game.State.PRE && now >= pick.startMs - 15 * 60_000L
            val every = when {
                pick.state == Game.State.LIVE || soon -> 60_000L
                pick.state == Game.State.PRE -> 5 * 60_000L
                else -> 30 * 60_000L
            }
            if (now - lastRefresh >= every) {
                lastRefresh = now
                runCatching { source.refresh(pick!!) }.getOrNull()?.let { fresh ->
                    schedules[fresh.team.key] = schedules[fresh.team.key].orEmpty().map { if (it.id == fresh.id) fresh else it }
                    pick = fresh
                }
            }
        }
        current = pick?.takeIf { it.inWindow(now) || it.state == Game.State.PRE }
        return current
    }

    /**
     * The game to show: one in progress first, then a final still in its window, then the next start within
     * 18 hours (so "Tonight · 7:00 PM" appears in the morning). Null otherwise.
     */
    fun pick(games: List<Game>, now: Long): Game? {
        games.filter { it.state == Game.State.LIVE }.minByOrNull { it.startMs }?.let { return it }
        games.filter { it.state == Game.State.FINAL && it.inWindow(now) }.maxByOrNull { it.startMs }?.let { return it }
        // ESPN can lag a few minutes flipping a game to "in"; keep a started game until it shows up as live.
        return games.filter { it.state == Game.State.PRE && it.startMs in (now - 4 * 60 * 60_000L)..(now + 18 * 60 * 60_000L) }
            .minByOrNull { it.startMs }
    }
}

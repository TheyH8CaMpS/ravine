package dev.veil.launcher.state

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.veil.launcher.data.CourtsideGuide
import dev.veil.launcher.data.CourtsideScores
import dev.veil.launcher.data.CourtsideScores.Score
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Scores panel (PROJECT.md D49): Courtside's scoreboard, a game's page and standings. */
class ScoresState(private val context: Context, private val scope: CoroutineScope) {
    /** Courtside GET; replaced in previews and tests. Returns the body, or null on any failure. */
    internal var fetcher: (String) -> String? = { path -> CourtsideGuide.get(context, path) }

    /** The Scores button shows once Courtside is set up on this TV and its scores route has answered. */
    val available: Boolean get() = forceAvailable ?: reachable
    internal var forceAvailable: Boolean? = null // previews
    private var reachable by mutableStateOf(false)
    private var lastProbe = 0L

    /** Minute tick: until Courtside's scores route answers, ask again every 15 minutes. */
    fun tick(now: Long) {
        if (reachable || now - lastProbe < 15 * 60_000L) return
        lastProbe = now
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { CourtsideGuide.configured(context) && fetcher(scoresPath())?.let { CourtsideScores.parseScores(it) } != null }.getOrDefault(false)
            }
            if (ok) reachable = true
        }
    }

    private fun scoresPath(): String {
        val zone = "America/Los_Angeles"
        return "/api/tv/scores?date=${java.time.LocalDate.now(java.time.ZoneId.of(zone))}&zone=$zone"
    }

    var open by mutableStateOf(false)
        private set
    var games by mutableStateOf<List<Score>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set

    /** Tabs: Your teams, each league playing today, Standings. */
    val tabs: List<String> get() =
        listOf(YOURS) + CourtsideScores.LEAGUE_ORDER.filter { l -> games.any { it.league == l } } + STANDINGS
    var tab by mutableStateOf(YOURS)

    fun gamesFor(tab: String): List<Score> = if (tab == YOURS) games.filter { it.favorite } else games.filter { it.league == tab }

    /** The game page, while one is open. */
    var game by mutableStateOf<Score?>(null)
        private set
    var detail by mutableStateOf<CourtsideScores.Detail?>(null)
        private set

    /** Standings by league, in tab order (leagues playing today, plus the owner's: NFL, NBA, MLB). */
    var standings by mutableStateOf<List<Pair<String, CourtsideScores.Standings>>>(emptyList())
        private set
    private var standingsLoaded = 0L

    fun show() { open = true; game = null; detail = null; refresh() }
    fun close() { if (open) closedTick++; open = false; game = null; detail = null }

    /** Bumped on each close, so the top bar can put focus back on the Scores button. */
    var closedTick by mutableStateOf(0)
        private set

    /** Back: the game page goes back to the list; the list closes the panel. */
    fun back() { if (game != null) { game = null; detail = null } else close() }

    fun refresh() {
        if (loading) return
        loading = true
        scope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { fetcher(scoresPath()) }.getOrNull() }
            val parsed = text?.let { runCatching { CourtsideScores.parseScores(it) }.getOrNull() }
            loading = false
            failed = parsed == null && games.isEmpty()
            if (parsed != null) {
                games = parsed
                game = game?.let { g -> parsed.firstOrNull { it.id == g.id } ?: g }
            }
            if (tab !in tabs) tab = YOURS
            if (tab == STANDINGS) loadStandings()
        }
    }

    fun openGame(g: Score) {
        game = g
        detail = null
        loadDetail(g)
    }

    fun refreshDetail() { game?.let { loadDetail(it) } }

    private fun loadDetail(g: Score) {
        scope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { fetcher("/api/tv/game?feed=${g.feed}&id=${g.eventId}") }.getOrNull() }
            val d = text?.let { runCatching { CourtsideScores.parseDetail(it) }.getOrNull() }
            if (game?.id == g.id && d != null) detail = d
        }
    }

    fun selectTab(t: String) {
        tab = t
        if (t == STANDINGS) loadStandings()
    }

    /** Standings change slowly: loaded at most every 10 minutes. */
    fun loadStandings() {
        val now = System.currentTimeMillis()
        if (now - standingsLoaded < 10 * 60_000L && standings.isNotEmpty()) return
        standingsLoaded = now
        val leagues = CourtsideScores.LEAGUE_ORDER.filter { l -> l != "UFC" && (l in listOf("NFL", "NBA", "MLB") || games.any { it.league == l }) }
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                leagues.mapNotNull { l ->
                    runCatching { fetcher("/api/tv/standings?league=$l")?.let { CourtsideScores.parseStandings(it) } }.getOrNull()
                        ?.takeIf { it.groups.isNotEmpty() }?.let { l to it }
                }
            }
            standings = loaded
            if (loaded.isEmpty()) standingsLoaded = 0L
        }
    }

    companion object {
        const val YOURS = "Your teams"
        const val STANDINGS = "Standings"
    }
}

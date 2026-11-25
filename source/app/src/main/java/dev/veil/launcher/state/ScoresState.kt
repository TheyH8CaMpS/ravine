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
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * The Scores panel (PROJECT.md D49): Courtside's scoreboard, a game's page and standings.
 * The last good scoreboard is kept in memory and on disk ([cache]) for the same Pacific day, so the panel shows
 * it the moment it opens, with "Updating…", while the fresh one loads (ravine#19). An earlier day's is never shown.
 */
class ScoresState internal constructor(
    private val scope: CoroutineScope,
    /** Courtside GET; replaced in previews and tests. Returns the body, or null on any failure. */
    internal var fetcher: (String) -> String?,
    private val configured: () -> Boolean,
    private val cache: ScoresCache,
    /** Today in Courtside's zone; replaced in tests. */
    internal var today: () -> LocalDate = { LocalDate.now(ZONE) },
) {
    constructor(context: Context, scope: CoroutineScope) : this(
        scope,
        fetcher = { path -> CourtsideGuide.get(context, path) },
        configured = { CourtsideGuide.configured(context) },
        cache = ScoresCache(File(context.filesDir, "courtside-scores.json")),
    )

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
                runCatching { configured() && fetcher(scoresPath())?.let { CourtsideScores.parseScores(it) } != null }.getOrDefault(false)
            }
            if (ok) reachable = true
        }
    }

    private fun scoresPath(day: LocalDate = today()): String = "/api/tv/scores?date=$day&zone=${ZONE.id}"

    var open by mutableStateOf(false)
        private set
    var games by mutableStateOf<List<Score>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    /** True while the games on screen are a cached copy and the fresh scoreboard is still on its way. */
    var updating by mutableStateOf(false)
        private set
    /** The Pacific day [games] belong to. */
    private var gamesDay: LocalDate? = null

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

    fun show() {
        open = true; game = null; detail = null
        restoreToday()
        refresh()
    }

    /** Before the network answers: keep today's games in memory, else today's copy on disk; drop an earlier day's. */
    private fun restoreToday() {
        val day = today()
        if (gamesDay == day && games.isNotEmpty()) { updating = true; return }
        val cached = runCatching { cache.load(day)?.let { CourtsideScores.parseScores(it) } }.getOrNull()
        games = cached ?: emptyList()
        gamesDay = if (cached != null) day else null
        updating = cached != null
        failed = false
        if (tab !in tabs) tab = YOURS
    }
    fun close() { if (open) closedTick++; open = false; game = null; detail = null }

    /** Bumped on each close, so the top bar can put focus back on the Scores button. */
    var closedTick by mutableStateOf(0)
        private set

    /** Back: the game page goes back to the list; the list closes the panel. */
    fun back() { if (game != null) { game = null; detail = null } else close() }

    fun refresh() {
        if (loading) return
        loading = true
        val day = today()
        scope.launch {
            val parsed = withContext(Dispatchers.IO) {
                val text = runCatching { fetcher(scoresPath(day)) }.getOrNull()
                text?.let { runCatching { CourtsideScores.parseScores(it) }.getOrNull() }
                    ?.also { runCatching { cache.save(day, text) } }
            }
            loading = false
            updating = false
            failed = parsed == null && games.isEmpty()
            if (parsed != null) {
                games = parsed
                gamesDay = day
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
        val ZONE: ZoneId = ZoneId.of("America/Los_Angeles")
    }
}

/** The last good `/api/tv/scores` body on disk, tagged with its day: first line the date, then the body. */
class ScoresCache(private val file: File) {
    fun save(day: LocalDate, body: String) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText("$day\n$body")
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    /** The body saved for [day], or null if there is none or it is from another day. */
    fun load(day: LocalDate): String? {
        if (!file.exists()) return null
        val text = file.readText()
        val nl = text.indexOf('\n')
        if (nl < 0 || text.substring(0, nl) != day.toString()) return null
        return text.substring(nl + 1)
    }
}

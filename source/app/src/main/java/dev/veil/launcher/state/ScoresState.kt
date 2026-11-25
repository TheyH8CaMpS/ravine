package dev.veil.launcher.state

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.veil.launcher.data.CourtsideGuide
import dev.veil.launcher.data.CourtsideScores
import dev.veil.launcher.data.CourtsideScores.Score
import dev.veil.launcher.data.LIVE_SCORE_MAX_AGE_MS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    private val pageTimeoutMs: Long = 20_000,
) {
    constructor(context: Context, scope: CoroutineScope) : this(
        scope,
        fetcher = { path -> CourtsideGuide.get(context, path) },
        configured = { CourtsideGuide.configured(context) },
        cache = ScoresCache(File(context.filesDir, "courtside-scores.json")),
    )

    /** Configuration is local, so Scores stays reachable even when the provider isn't. */
    val available: Boolean get() = forceAvailable ?: configuredOnDevice
    internal var forceAvailable: Boolean? = null // previews
    private var configuredOnDevice by mutableStateOf(runCatching(configured).getOrDefault(false))

    /** Re-read local setup on resume and the minute tick. Only opening Scores starts a score fetch. */
    fun refreshConfiguration() {
        configuredOnDevice = runCatching(configured).getOrDefault(false)
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
    var lastGoodAt by mutableStateOf<Long?>(null)
        private set

    fun staleSince(now: Long): Long? = lastGoodAt?.takeIf { now - it > LIVE_SCORE_MAX_AGE_MS }
    fun asOfLabel(now: Long, format: (Long) -> String): String? = staleSince(now)?.let { "As of ${format(it)}" }

    /** Tabs: Your teams, each league playing today, Notable (when Courtside has lines), Standings. */
    val tabs: List<String> get() =
        listOf(YOURS) +
            CourtsideScores.LEAGUE_ORDER.filter { l -> games.any { it.league == l } } +
            (if (notable.isNotEmpty()) listOf(NOTABLE) else emptyList()) +
            STANDINGS
    var tab by mutableStateOf(YOURS)

    fun gamesFor(tab: String): List<Score> = if (tab == YOURS) games.filter { it.favorite } else games.filter { it.league == tab }

    /** The game page, while one is open. */
    var game by mutableStateOf<Score?>(null)
        private set
    var detail by mutableStateOf<CourtsideScores.Detail?>(null)
        private set
    var detailFailed by mutableStateOf(false)
        private set
    private var detailRequest: Job? = null

    /** NFL, NBA, the college AP poll, and MLB when Courtside reports that it is in season. */
    var standings by mutableStateOf<List<Pair<String, CourtsideScores.Standings>>>(emptyList())
        private set
    var standingsFailed by mutableStateOf(false)
        private set
    private var standingsRequest: Job? = null
    private var standingsLoaded = 0L

    /** Courtside's Notable lines (ravine#22): today's, or last night's while today has none yet. Up to 8. */
    var notable by mutableStateOf<List<CourtsideScores.Notable>>(emptyList())
        private set
    /** "Today" or "Last night", like the web overview's slate label. */
    var notableLabel by mutableStateOf("Today")
        private set
    private var notableDay: LocalDate? = null
    private var notableFor: LocalDate? = null // the day the lines were loaded on
    private var notableLoaded = 0L

    fun show() {
        open = true
        clearGame()
        restoreToday()
        refresh()
        loadNotable()
    }

    /** Lines only change as games finish: loaded on open, at most every 10 minutes. */
    fun loadNotable(now: Long = System.currentTimeMillis()) {
        val day = today()
        if (notableFor == day && now - notableLoaded < 10 * 60_000L) return
        notableFor = day
        notableLoaded = now
        scope.launch {
            val (rows, from) = withContext(Dispatchers.IO) {
                fun fetch(d: LocalDate) = runCatching { fetcher(notablePath(d))?.let { CourtsideScores.parseNotable(it) } }.getOrNull()
                val todays = fetch(day)
                // Mornings before any game has finished: last night's lines.
                if (todays != null && todays.isNotEmpty()) todays to day else (fetch(day.minusDays(1)) ?: emptyList()) to day.minusDays(1)
            }
            notable = rows.take(8)
            notableDay = from
            notableLabel = if (from == day) "Today" else "Last night"
            if (rows.isEmpty()) notableFor = null
            if (tab !in tabs) tab = YOURS
        }
    }

    private fun notablePath(day: LocalDate) = "/api/tv/notable?date=$day&zone=${ZONE.id}"

    /** OK on a Notable line: its game page, from today's list or that day's scoreboard. */
    fun openNotable(n: CourtsideScores.Notable) {
        games.firstOrNull { it.feed == n.feed && it.eventId == n.eventId }?.let { openGame(it); return }
        val day = notableDay ?: today()
        scope.launch {
            val g = withContext(Dispatchers.IO) {
                runCatching { fetcher(scoresPath(day))?.let { CourtsideScores.parseScores(it) } }.getOrNull()
                    ?.firstOrNull { it.feed == n.feed && it.eventId == n.eventId }
            }
            if (g != null && open) openGame(g)
        }
    }

    /** Before the network answers: keep today's games in memory, else today's copy on disk; drop an earlier day's. */
    private fun restoreToday() {
        val day = today()
        if (gamesDay == day && games.isNotEmpty()) { updating = true; return }
        val cached = runCatching {
            cache.read(day)?.let { CourtsideScores.parseScores(it.body) to it.fetchedAt }
        }.getOrNull()
        games = cached?.first ?: emptyList()
        lastGoodAt = cached?.second
        gamesDay = if (cached != null) day else null
        updating = cached != null
        failed = false
        if (tab !in tabs) tab = YOURS
    }
    fun close() { if (open) closedTick++; open = false; clearGame() }

    private fun clearGame() {
        detailRequest?.cancel()
        detailRequest = null
        game = null
        detail = null
        detailFailed = false
    }

    /** Bumped on each close, so the top bar can put focus back on the Scores button. */
    var closedTick by mutableStateOf(0)
        private set

    /** Back: the game page goes back to the list; the list closes the panel. */
    fun back() { if (game != null) clearGame() else close() }

    fun refresh() {
        if (loading) return
        loading = true
        val day = today()
        scope.launch {
            val parsed = withContext(Dispatchers.IO) {
                val text = runCatching { fetcher(scoresPath(day)) }.getOrNull()
                text?.let { body ->
                    runCatching { CourtsideScores.parseScores(body) }.getOrNull()?.let { scores ->
                        val fetchedAt = System.currentTimeMillis()
                        runCatching { cache.save(day, body, fetchedAt) }
                        scores to fetchedAt
                    }
                }
            }
            updating = false
            failed = parsed == null && games.isEmpty()
            if (parsed != null) {
                games = parsed.first
                lastGoodAt = parsed.second
                gamesDay = day
                game = game?.let { g -> games.firstOrNull { it.id == g.id } ?: g }
            }
            if (tab !in tabs) tab = YOURS
            if (tab == STANDINGS && !standingsFailed) loadStandings()
            loading = false
        }
    }

    fun openGame(g: Score) {
        clearGame()
        game = g
        loadDetail(g)
    }

    fun refreshDetail() { game?.let { loadDetail(it) } }

    private fun loadDetail(g: Score) {
        if (detailRequest?.isActive == true) return
        detailFailed = false
        detailRequest = scope.launch {
            val result = requestPage {
                runCatching { fetcher("/api/tv/game?feed=${g.feed}&id=${g.eventId}")?.let(CourtsideScores::parseDetail) }.getOrNull()
            }
            detailRequest = null
            if (game?.id == g.id) {
                if (result != null) detail = result else detailFailed = true
            }
        }
    }

    private suspend fun <T> requestPage(load: CoroutineScope.() -> T): T? {
        // Wait separately from blocking I/O so the panel can leave Loading even if the socket hasn't returned.
        val request = scope.async(Dispatchers.IO) { load() }
        return try { withTimeoutOrNull(pageTimeoutMs) { request.await() } } finally { request.cancel() }
    }

    fun selectTab(t: String) {
        tab = t
        if (t == STANDINGS) loadStandings()
    }

    /** Standings change slowly: loaded at most every 10 minutes. */
    fun loadStandings() {
        val now = System.currentTimeMillis()
        if (standingsRequest?.isActive == true || (now - standingsLoaded < 10 * 60_000L && standings.isNotEmpty())) return
        standingsLoaded = now
        standingsFailed = false
        standingsRequest = scope.launch {
            val loaded = requestPage {
                listOf("NFL", "NBA", "NCAAF", "MLB").mapNotNull { league ->
                    ensureActive()
                    runCatching {
                        fetcher("/api/tv/standings?league=$league")?.let(CourtsideScores::parseStandings)
                    }.getOrNull()
                        ?.takeIf { it.groups.isNotEmpty() && (league != "MLB" || it.inSeason) }
                        ?.let { league to it }
                }
            }
            standingsRequest = null
            if (loaded.isNullOrEmpty()) {
                standingsLoaded = 0L
                standingsFailed = true
            } else {
                standings = loaded
            }
        }
    }

    companion object {
        const val YOURS = "Your teams"
        const val STANDINGS = "Standings"
        const val NOTABLE = "Notable"
        val ZONE: ZoneId = ZoneId.of("America/Los_Angeles")
    }
}

/** The last good scoreboard on disk, tagged with its Pacific day and fetch time. */
class ScoresCache(private val file: File) {
    internal data class Entry(val body: String, val fetchedAt: Long)

    fun save(day: LocalDate, body: String, fetchedAt: Long = System.currentTimeMillis()) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText("$day $fetchedAt\n$body")
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    /** The body saved for [day], or null if there is none or it is from another day. */
    internal fun read(day: LocalDate): Entry? {
        if (!file.exists()) return null
        val text = file.readText()
        val nl = text.indexOf('\n')
        if (nl < 0) return null
        val header = text.substring(0, nl)
        if (header.substringBefore(' ') != day.toString()) return null
        val fetchedAt = header.substringAfter(' ', "").toLongOrNull() ?: file.lastModified()
        return Entry(text.substring(nl + 1), fetchedAt)
    }

    fun load(day: LocalDate): String? = read(day)?.body
}

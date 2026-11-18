package dev.veil.launcher.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Ravine's account-free Continue Watching (PROJECT.md §5, option 1).
 *
 * While Nuvio plays, its media session reports the title, "S1:E2 – Episode" (or the year for films), the
 * length and the position. [record] keeps one entry per show or film on this Shield. [items] turns them into
 * Continue Watching cards: TMDB supplies the IMDb id (which Nuvio's "open in stream picker" intent needs) and
 * the artwork; a finished episode becomes "Up next" if the next one has aired; a finished film drops off.
 */
object LocalHistory {
    data class Entry(
        val title: String,
        val isMovie: Boolean,
        val season: Int = 0,
        val episode: Int = 0,
        val episodeTitle: String? = null,
        val year: Int? = null,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val lastPlayed: Long = 0,
        /** Resolved once and remembered. */
        val tmdb: Int? = null,
        val imdb: String? = null,
        /** The user removed it from the row (kept so it doesn't come back until played again). */
        val hidden: Boolean = false,
    ) {
        val key: String get() = if (isMovie) "m:${title.lowercase()}:${year ?: ""}" else "s:${title.lowercase()}"
        val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        val finished: Boolean get() = progress >= FINISHED
    }

    private const val FINISHED = 0.9f
    private const val MAX_ENTRIES = 40
    private const val MAX_AGE_MS = 60L * 24 * 60 * 60_000
    private const val MIN_WATCH_MS = 60_000L

    private var file: File? = null
    private val lock = Any()
    private var cache: MutableMap<String, Entry>? = null
    private var lastSave = 0L

    fun init(context: Context) {
        if (file == null) file = File(context.applicationContext.filesDir, "nuvio_history.json")
    }

    private fun entries(): MutableMap<String, Entry> = cache ?: run {
        val map = LinkedHashMap<String, Entry>()
        runCatching { fromJson(file!!.readText()) }.getOrNull()?.forEach { map[it.key] = it }
        map.also { cache = it }
    }

    /** "S1:E2 – Pilot" → (1, 2, "Pilot"); "2024" or blank → null (a film). */
    fun parseEpisode(subtitle: String?): Triple<Int, Int, String?>? {
        val m = EPISODE.find(subtitle?.trim().orEmpty()) ?: return null
        val title = m.groupValues[3].trim().takeIf { it.isNotEmpty() }
        return Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), title)
    }

    private val EPISODE = Regex("""^S(\d+)\s*:?\s*E(\d+)(?:\s*[–—-]\s*(.*))?$""", RegexOption.IGNORE_CASE)
    private val YEAR = Regex("""^(19|20)\d{2}$""")

    /** Records what Nuvio is playing. Cheap; writes to disk at most every 15 s unless [flush]. */
    fun record(title: String, subtitle: String?, positionMs: Long, durationMs: Long, flush: Boolean = false) {
        if (title.isBlank() || file == null) return
        val ep = parseEpisode(subtitle)
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val map = entries()
            val fresh = Entry(
                title = title.trim(),
                isMovie = ep == null,
                season = ep?.first ?: 0,
                episode = ep?.second ?: 0,
                episodeTitle = ep?.third,
                year = subtitle?.trim()?.takeIf { ep == null && YEAR.matches(it) }?.toInt(),
                positionMs = positionMs.coerceAtLeast(0),
                durationMs = durationMs.coerceAtLeast(0),
                lastPlayed = now,
            )
            val old = map[fresh.key]
            // Ignore a few seconds of sampling a show; keep what we already resolved.
            if (old == null && positionMs < MIN_WATCH_MS && !flush) return
            map.remove(fresh.key)
            map[fresh.key] = fresh.copy(tmdb = old?.tmdb, imdb = old?.imdb)
            prune(map, now)
            if (flush || now - lastSave > 15_000) save(map, now)
        }
    }

    /** Removes an item from the row until it's played again. */
    fun hide(key: String) = synchronized(lock) {
        val map = entries()
        val e = map.values.firstOrNull { cardKey(it) == key } ?: return@synchronized
        map[e.key] = e.copy(hidden = true)
        save(map, System.currentTimeMillis())
    }

    private fun prune(map: MutableMap<String, Entry>, now: Long) {
        map.values.filter { now - it.lastPlayed > MAX_AGE_MS }.forEach { map.remove(it.key) }
        while (map.size > MAX_ENTRIES) map.remove(map.values.minByOrNull { it.lastPlayed }!!.key)
    }

    private fun save(map: Map<String, Entry>, now: Long) {
        lastSave = now
        runCatching { file?.writeText(toJson(map.values.toList())) }
    }

    private fun update(e: Entry) = synchronized(lock) {
        val map = entries()
        if (map.containsKey(e.key)) { map[e.key] = e; save(map, System.currentTimeMillis()) }
    }

    /** Must match [WatchItem.key] for the cards built below (no Trakt id, so it's keyed by title). */
    private fun cardKey(e: Entry) = if (e.isMovie) "m:${e.imdb ?: e.title}" else "s:${e.title}"

    /** Builds the Continue Watching cards, newest first. Network (TMDB): call off the main thread. */
    fun items(tmdb: Tmdb, limit: Int = 12): List<WatchItem> {
        val list = synchronized(lock) { entries().values.sortedByDescending { it.lastPlayed } }
        val out = ArrayList<WatchItem>()
        for (e0 in list) {
            if (out.size >= limit) break
            if (e0.hidden || (e0.isMovie && e0.finished)) continue
            val e = resolve(e0, tmdb)
            val id = e.tmdb
            val art = id?.let { runCatching { tmdb.art(e.isMovie, it) }.getOrNull() }
            var season = e.season
            var episode = e.episode
            var episodeTitle = e.episodeTitle
            var progress = e.progress
            var upNext = false
            if (!e.isMovie && e.finished) {
                // Finished an episode: offer the next one, if it has aired.
                val next = id?.let { nextAired(tmdb, it, e.season, e.episode) } ?: continue
                season = next.first; episode = next.second; episodeTitle = next.third
                progress = 0f; upNext = true
            }
            out += WatchItem(
                isMovie = e.isMovie,
                title = e.title,
                traktId = null,
                imdb = e.imdb,
                tmdb = id,
                season = season,
                episode = episode,
                episodeTitle = episodeTitle,
                progress = progress,
                runtime = if (upNext) null else (e.durationMs / 60_000).toInt().takeIf { it > 0 },
                upNext = upNext,
                sortTime = e.lastPlayed,
                backdrop = art?.backdrop,
                thumb = art?.thumb,
                logo = art?.logo,
                poster = art?.poster,
            )
        }
        return out
    }

    /** Finds the TMDB and IMDb ids by title (and year for films), once. */
    private fun resolve(e: Entry, tmdb: Tmdb): Entry {
        if (e.tmdb != null && e.imdb != null) return e
        val type = if (e.isMovie) "movie" else "tv"
        val id = e.tmdb ?: run {
            val q = java.net.URLEncoder.encode(e.title, "UTF-8")
            val yearParam = if (e.isMovie && e.year != null) "&year=${e.year}" else ""
            tmdb.get("search/$type", "query=$q$yearParam")?.optJSONArray("results")?.optJSONObject(0)?.int("id")
        } ?: return e
        val details = tmdb.get("$type/$id", if (e.isMovie) "" else "append_to_response=external_ids")
        val imdb = if (e.isMovie) details?.str("imdb_id") else details?.obj("external_ids")?.str("imdb_id")
        return e.copy(tmdb = id, imdb = imdb).also { update(it) }
    }

    /** The episode after (season, episode) if it has aired, using TMDB's "last episode to air". */
    private fun nextAired(tmdb: Tmdb, id: Int, season: Int, episode: Int): Triple<Int, Int, String?>? {
        val d = tmdb.get("tv/$id") ?: return null
        val last = d.obj("last_episode_to_air") ?: return null
        val lastS = last.int("season_number") ?: return null
        val lastE = last.int("episode_number") ?: return null
        val seasons = d.optJSONArray("seasons") ?: JSONArray()
        val countInSeason = (0 until seasons.length()).map { seasons.getJSONObject(it) }
            .firstOrNull { it.int("season_number") == season }?.int("episode_count") ?: 0
        val next = if (episode < countInSeason) season to episode + 1 else season + 1 to 1
        val aired = next.first < lastS || (next.first == lastS && next.second <= lastE)
        if (!aired) return null
        val title = tmdb.get("tv/$id/season/${next.first}/episode/${next.second}")?.str("name")
        return Triple(next.first, next.second, title)
    }

    fun toJson(list: List<Entry>): String = JSONArray().apply {
        list.forEach { e ->
            put(JSONObject().apply {
                put("title", e.title); put("movie", e.isMovie); put("s", e.season); put("e", e.episode)
                e.episodeTitle?.let { put("et", it) }; e.year?.let { put("year", it) }
                put("pos", e.positionMs); put("dur", e.durationMs); put("at", e.lastPlayed)
                e.tmdb?.let { put("tmdb", it) }; e.imdb?.let { put("imdb", it) }
                if (e.hidden) put("hidden", true)
            })
        }
    }.toString()

    fun fromJson(text: String): List<Entry> {
        val arr = JSONArray(text)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Entry(
                title = o.str("title") ?: return@mapNotNull null,
                isMovie = o.optBoolean("movie"),
                season = o.optInt("s"), episode = o.optInt("e"),
                episodeTitle = o.str("et"), year = o.int("year"),
                positionMs = o.optLong("pos"), durationMs = o.optLong("dur"), lastPlayed = o.optLong("at"),
                tmdb = o.int("tmdb"), imdb = o.str("imdb"), hidden = o.optBoolean("hidden"),
            )
        }
    }
}

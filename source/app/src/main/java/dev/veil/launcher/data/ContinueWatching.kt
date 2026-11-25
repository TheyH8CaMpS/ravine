package dev.veil.launcher.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Builds the "Continue watching" row from Trakt (which Nuvio syncs to):
 *  1. Paused episodes/movies (/sync/playback), with progress.
 *  2. The next unwatched episode of recently watched shows (/shows/{id}/progress/watched).
 */
object ContinueWatching {
    private const val MAX_ITEMS = 10
    private const val MAX_UP_NEXT_SHOWS = 10
    /** An episode counts as "NEW" for two weeks after it airs (if you haven't watched the show since). */
    private const val NEW_WINDOW_MS = 14L * 24 * 3600 * 1000

    suspend fun load(trakt: Trakt, tmdb: Tmdb, cached: List<WatchItem> = emptyList()): List<WatchItem> = coroutineScope {
        val previous = cached.associateBy { it.key }
        val previousUpNext = cached.filter { it.upNext }.associateBy { it.traktId }
        val playback = parsePlayback(JSONArray(trakt.get("/sync/playback?limit=30&extended=full")))
        val pausedShows = playback.filter { !it.isMovie }.mapNotNull { it.traktId }.toSet()
        // Shows you removed from Continue Watching (Trakt "hidden from progress") stay out of Up Next.
        val hidden = trakt.hiddenShows()

        // Most recently watched shows that aren't already paused mid-episode.
        val history = JSONArray(trakt.get("/sync/history/episodes?limit=60&extended=full"))
        val recentShows = LinkedHashMap<Int, Pair<JSONObject, Long>>()
        for (i in 0 until history.length()) {
            val h = history.getJSONObject(i)
            val show = h.obj("show") ?: continue
            val id = show.obj("ids")?.int("trakt") ?: continue
            if (id in pausedShows || id in hidden || id in recentShows) continue
            recentShows[id] = show to time(h.str("watched_at"))
            if (recentShows.size >= MAX_UP_NEXT_SHOWS) break
        }

        val upNext = recentShows.map { (id, pair) ->
            async {
                runCatching {
                    val p = JSONObject(
                        trakt.get("/shows/$id/progress/watched?hidden=false&specials=false&count_specials=false&extended=full"),
                    )
                    p.obj("next_episode")?.let { next0 ->
                        // Air date + runtime for "NEW" and "done by". If the progress call didn't include them,
                        // ask Trakt's documented episode endpoint. Missing data just means no badge.
                        var next = next0
                        if (next.str("first_aired") == null) {
                            val s = next.int("season"); val e = next.int("number")
                            if (s != null && e != null) runCatching {
                                next = JSONObject(trakt.get("/shows/$id/seasons/$s/episodes/$e?extended=full"))
                            }
                        }
                        val aired = time(next.str("first_aired"))
                        val lastWatched = time(p.str("last_watched_at")).takeIf { it > 0 } ?: pair.second
                        val now = System.currentTimeMillis()
                        val isNew = aired in (lastWatched + 1)..now && now - aired < NEW_WINDOW_MS
                        showItem(pair.first, next, progress = 0f, upNext = true, sortTime = maxOf(pair.second, if (isNew) aired else 0L))
                            .let { it.copy(isNew = isNew, runtime = it.runtime ?: pair.first.int("runtime")) }
                    }
                }.getOrElse { failure ->
                    if (failure is CancellationException) throw failure
                    previousUpNext[id]
                }
            }
        }.awaitAll().filterNotNull()

        val merged = (playback + upNext)
            .sortedByDescending { it.sortTime }
            .distinctBy { it.key }
            .take(MAX_ITEMS)

        merged.map { item ->
            async {
                val art = item.tmdb?.let { runCatching { tmdb.art(item.isMovie, it) }.getOrNull() }
                item.withArtwork(art, previous[item.key])
            }
        }.awaitAll()
    }

    private fun parsePlayback(arr: JSONArray): List<WatchItem> {
        val out = ArrayList<WatchItem>()
        for (i in 0 until arr.length()) {
            val j = arr.getJSONObject(i)
            val progress = (j.optDouble("progress", 0.0) / 100.0).toFloat()
            if (progress >= 0.95f) continue
            val t = time(j.str("paused_at"))
            when (j.str("type")) {
                "episode" -> {
                    val show = j.obj("show") ?: continue
                    val ep = j.obj("episode") ?: continue
                    out += showItem(show, ep, progress, upNext = false, sortTime = t).copy(playbackId = j.optLong("id").takeIf { it > 0 })
                }
                "movie" -> {
                    val m = j.obj("movie") ?: continue
                    val ids = m.obj("ids")
                    out += WatchItem(
                        isMovie = true,
                        title = m.str("title") ?: "Untitled",
                        traktId = ids?.int("trakt"),
                        imdb = ids?.str("imdb"),
                        tmdb = ids?.int("tmdb"),
                        progress = progress,
                        runtime = m.int("runtime"),
                        playbackId = j.optLong("id").takeIf { it > 0 },
                        sortTime = t,
                    )
                }
            }
        }
        return out
    }

    private fun showItem(show: JSONObject, ep: JSONObject, progress: Float, upNext: Boolean, sortTime: Long): WatchItem {
        val ids = show.obj("ids")
        return WatchItem(
            isMovie = false,
            title = show.str("title") ?: "Untitled",
            traktId = ids?.int("trakt"),
            imdb = ids?.str("imdb"),
            tmdb = ids?.int("tmdb"),
            season = ep.int("season") ?: 0,
            episode = ep.int("number") ?: 0,
            episodeTitle = ep.str("title"),
            progress = progress,
            runtime = ep.int("runtime"),
            upNext = upNext,
            sortTime = sortTime,
        )
    }

    private fun time(iso: String?): Long =
        iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
}

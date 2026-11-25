package dev.veil.launcher.data

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** The last good Nuvio/Trakt row, including the URLs Coil uses for its disk images. */
internal class WatchingCache(private val prefs: SharedPreferences) {
    fun load(): List<WatchItem> = runCatching {
        val rows = JSONArray(prefs.getString(KEY, "[]") ?: "[]")
        (0 until rows.length()).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            WatchItem(
                isMovie = row.optBoolean("movie"),
                title = row.str("title") ?: return@mapNotNull null,
                traktId = row.int("traktId"),
                imdb = row.str("imdb"),
                tmdb = row.int("tmdb"),
                season = row.optInt("season"),
                episode = row.optInt("episode"),
                episodeTitle = row.str("episodeTitle"),
                progress = row.optDouble("progress", 0.0).toFloat(),
                runtime = row.int("runtime"),
                upNext = row.optBoolean("upNext"),
                isNew = row.optBoolean("isNew"),
                playbackId = row.optLong("playbackId").takeIf { it > 0 },
                sortTime = row.optLong("sortTime"),
                backdrop = row.str("backdrop"),
                thumb = row.str("thumb"),
                logo = row.str("logo"),
                poster = row.str("poster"),
            )
        }
    }.getOrDefault(emptyList())

    fun save(items: List<WatchItem>) {
        val rows = JSONArray()
        for (item in items) {
            rows.put(JSONObject()
                .put("movie", item.isMovie)
                .put("title", item.title)
                .put("traktId", item.traktId)
                .put("imdb", item.imdb)
                .put("tmdb", item.tmdb)
                .put("season", item.season)
                .put("episode", item.episode)
                .put("episodeTitle", item.episodeTitle)
                .put("progress", item.progress.takeIf { it.isFinite() }?.toDouble() ?: 0.0)
                .put("runtime", item.runtime)
                .put("upNext", item.upNext)
                .put("isNew", item.isNew)
                .put("playbackId", item.playbackId)
                .put("sortTime", item.sortTime)
                .put("backdrop", item.backdrop)
                .put("thumb", item.thumb)
                .put("logo", item.logo)
                .put("poster", item.poster))
        }
        prefs.edit().putString(KEY, rows.toString()).apply()
    }

    private companion object {
        const val KEY = "watching_row"
    }
}

internal fun WatchItem.withArtwork(art: Tmdb.Art?, cached: WatchItem?): WatchItem = when {
    art != null -> copy(backdrop = art.backdrop, thumb = art.thumb, logo = art.logo, poster = art.poster)
    cached != null -> copy(backdrop = cached.backdrop, thumb = cached.thumb, logo = cached.logo, poster = cached.poster)
    else -> this
}

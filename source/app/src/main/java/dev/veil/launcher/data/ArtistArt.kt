package dev.veil.launcher.data

import android.content.Context
import org.json.JSONObject
import java.net.URLEncoder

/**
 * A picture of the artist for the music Poster (PROJECT.md D51), best first:
 *  1. WIDE: TheAudioDB's fan-art backgrounds (1280×720, made for TV screens; several per artist, so each song
 *     can get a different one). Public API, no account (the free key "123").
 *  2. SQUARE: Deezer's artist photo (1000×1000). Shown at full height on one side of the screen, where it's
 *     close to its real size and stays sharp, never stretched across 4K.
 *  3. NONE: no photo of this artist (or a podcast). The poster is built from the album cover's colours instead.
 * Names must match the artist exactly (ignoring case and punctuation), so a lookalike name never shows a
 * stranger. Results are kept for 30 days (a miss for 3).
 */
object ArtistArt {
    sealed interface Pick {
        data class Wide(val url: String) : Pick
        data class Square(val url: String) : Pick
        object None : Pick
    }

    private const val AUDIODB = "https://www.theaudiodb.com/api/v1/json/123/search.php?s="
    private const val DEEZER = "https://api.deezer.com/search/artist?limit=5&q="
    private const val PREFS = "ravine_artist_art"
    private const val KEEP = 30L * 24 * 60 * 60_000
    private const val KEEP_MISS = 3L * 24 * 60 * 60_000

    /** Blocking (network on a miss): call off the main thread. [song] varies the wide picture per song. */
    fun find(context: Context, artist: String?, song: String, now: Long = System.currentTimeMillis()): Pick {
        val names = candidates(artist ?: return Pick.None)
        if (names.isEmpty()) return Pick.None
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = norm(names.first())
        p.getString(key, null)?.let { cached ->
            val o = runCatching { JSONObject(cached) }.getOrNull()
            if (o != null) {
                val age = now - o.optLong("at")
                val kind = o.optString("kind")
                if (age < (if (kind == "none") KEEP_MISS else KEEP)) return decode(o, song)
            }
        }
        if (Http.offline) return Pick.None
        var found: JSONObject? = null
        for (name in names) {
            found = runCatching { lookup(name) }.getOrNull()
            if (found != null) break
        }
        val o = (found ?: JSONObject().put("kind", "none")).put("at", now)
        p.edit().putString(key, o.toString()).apply()
        return decode(o, song)
    }

    private fun decode(o: JSONObject, song: String): Pick = when (o.optString("kind")) {
        "wide" -> {
            val a = o.optJSONArray("urls")
            val urls = (0 until (a?.length() ?: 0)).map { a!!.getString(it) }
            if (urls.isEmpty()) Pick.None else Pick.Wide(urls[Math.floorMod(song.hashCode(), urls.size)])
        }
        "square" -> Pick.Square(o.getString("url"))
        else -> Pick.None
    }

    private fun lookup(name: String): JSONObject? {
        val want = norm(name)
        // 1. TheAudioDB fan art.
        val (code, text) = Http.call("GET", AUDIODB + enc(name))
        if (code == 200) {
            val artists = runCatching { JSONObject(text).optJSONArray("artists") }.getOrNull()
            for (i in 0 until (artists?.length() ?: 0)) {
                val a = artists!!.getJSONObject(i)
                if (norm(a.optString("strArtist")) != want) continue
                val urls = listOf("strArtistFanart", "strArtistFanart2", "strArtistFanart3", "strArtistFanart4")
                    .mapNotNull { k -> a.optString(k).takeIf { it.startsWith("https://") } }
                if (urls.isNotEmpty()) return JSONObject().put("kind", "wide").put("urls", org.json.JSONArray(urls))
            }
        }
        // 2. Deezer's artist photo.
        val (dc, dt) = Http.call("GET", DEEZER + enc(name))
        if (dc == 200) {
            val data = runCatching { JSONObject(dt).optJSONArray("data") }.getOrNull()
            for (i in 0 until (data?.length() ?: 0)) {
                val a = data!!.getJSONObject(i)
                if (norm(a.optString("name")) != want) continue
                val url = a.optString("picture_xl")
                // Deezer's grey silhouette for artists without a photo has an empty image id ("/artist//").
                if (url.startsWith("https://") && "/artist//" !in url) return JSONObject().put("kind", "square").put("url", url)
            }
        }
        return null
    }

    /** "Phoebe Bridgers, Julien Baker" → the whole credit first, then the first artist alone. */
    internal fun candidates(artist: String): List<String> {
        val whole = artist.trim()
        if (whole.isEmpty()) return emptyList()
        val first = whole.split(Regex("""\s*(,|;|/| feat\.? | ft\.? | featuring | x | & )\s*""", RegexOption.IGNORE_CASE)).first().trim()
        return listOf(whole, first).filter { it.isNotEmpty() }.distinct()
    }

    internal fun norm(s: String) = s.lowercase().replace("&", "and").replace(Regex("""[^\p{L}\p{N}]+"""), "")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

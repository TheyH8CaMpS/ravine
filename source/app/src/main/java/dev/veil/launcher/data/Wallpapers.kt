package dev.veil.launcher.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One photo from the wallpaper feed. [collection] is the feed's "location" field (e.g. "Moody green"). */
data class Wallpaper(
    val collection: String,
    val title: String,
    val author: String?,
    val url: String,
)

/**
 * Ravine's wallpaper library (PROJECT.md D5/D6): reads the same `overflight.json` feed that Projectivy's
 * Overflight plugin uses, keeps the last good copy on disk so Home works offline, and walks through it in a
 * shuffled order that changes daily. Images themselves are cached by Coil's disk cache.
 */
class Wallpapers(context: Context) {
    private val file = File(context.filesDir, "wallpapers.json")
    private val prefs = context.getSharedPreferences("ravine_wallpapers", Context.MODE_PRIVATE)

    var feedUrl: String
        get() = prefs.getString("feed", null) ?: DEFAULT_FEED
        set(v) = prefs.edit().putString("feed", v).apply()

    /** Rotation interval in minutes. */
    var rotateMinutes: Int
        get() = prefs.getInt("rotate_min", 10)
        set(v) = prefs.edit().putInt("rotate_min", v).apply()

    /** All wallpapers from the last good feed (empty until the first successful download). */
    var all: List<Wallpaper> = runCatching { parse(file.readText()) }.getOrDefault(emptyList())
        private set

    /** Downloads the feed; keeps the cached copy if the network or the feed is broken. Call off the main thread. */
    fun refresh(): Boolean {
        val (code, text) = runCatching { Http.call("GET", feedUrl) }.getOrNull() ?: return false
        if (code != 200) return false
        val list = runCatching { parse(text) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return false
        runCatching { file.writeText(text) }
        all = list
        return true
    }

    /**
     * The wallpaper for step [step] of today's shuffled order. With [prefer] (a collection, e.g. "Lakers" on
     * game day), only that collection's photos, if it has any.
     */
    fun at(step: Int, day: Long = System.currentTimeMillis() / 86_400_000L, prefer: String? = null): Wallpaper? {
        val list = prefer?.let { p -> all.filter { it.collection.equals(p, ignoreCase = true) } }?.takeIf { it.isNotEmpty() } ?: all
        if (list.isEmpty()) return null
        val order = list.indices.shuffled(kotlin.random.Random(day))
        return list[order[Math.floorMod(step, list.size)]]
    }

    /** Remembered across restarts, so Home doesn't always open on the same photo. */
    var step: Int
        get() = prefs.getInt("step", 0)
        set(v) = prefs.edit().putInt("step", v).apply()

    companion object {
        const val DEFAULT_FEED =
            "https://raw.githubusercontent.com/TheyH8CaMpS/shield-wallpaper-library/main/overflight.json"

        /**
         * Accepts the Overflight shape (a JSON array of {location, title, author, url_img}) and, to be lenient,
         * an object wrapping that array in "items"/"wallpapers"/"assets", and "url"/"image" for the image.
         * Entries without an http(s) image URL are skipped.
         */
        fun parse(text: String): List<Wallpaper> {
            val trimmed = text.trim()
            val arr: JSONArray = if (trimmed.startsWith("[")) JSONArray(trimmed) else {
                val o = JSONObject(trimmed)
                listOf("items", "wallpapers", "assets").firstNotNullOfOrNull { o.optJSONArray(it) } ?: JSONArray()
            }
            val out = ArrayList<Wallpaper>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = listOf("url_img", "url", "image").firstNotNullOfOrNull { o.str(it) } ?: continue
                if (!url.startsWith("http://") && !url.startsWith("https://")) continue
                out += Wallpaper(
                    collection = o.str("location") ?: o.str("collection") ?: "",
                    title = o.str("title") ?: "",
                    author = o.str("author"),
                    url = url,
                )
            }
            return out
        }
    }
}

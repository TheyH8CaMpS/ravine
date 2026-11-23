package dev.veil.launcher.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.Properties

/**
 * Optional channel names from Courtside (docs/courtside.md, PROJECT.md D30): which channel in the owner's
 * provider guide (the same one TiviMate uses) carries a game, e.g. "512 · Spectrum SportsNet LA". Shown on
 * the game strip instead of ESPN's network name. Watching still goes straight to TiviMate.
 *
 * The public release APK includes the configured TMDB_KEY, SIMKL_CLIENT_ID and WEATHER_CITY in BuildConfig.
 * The owner supplies the Courtside and Yak addresses and tokens separately over ADB. For Courtside, push this file once.
 *   /sdcard/Android/data/app.ravine.launcher/files/courtside.properties
 *     COURTSIDE_URL=https://…              (Courtside's private address)
 *     COURTSIDE_TV_TOKEN=…                 (Courtside's TV endpoint token)
 *     COURTSIDE_SITES_BYPASS_TOKEN=…       (optional; only while Courtside was on OpenAI Sites)
 * Ravine copies it into private app storage and deletes the file. Anything missing or failing → ESPN's name.
 */
object CourtsideGuide {
    private const val PREFS = "ravine_courtside"
    private val cache = HashMap<String, Pair<Long, String?>>() // event id → (fetched at, channel label)

    fun configured(context: Context): Boolean {
        importFile(context)
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return !p.getString("url", null).isNullOrBlank() && !p.getString("tv", null).isNullOrBlank()
    }

    /** Moves a pushed courtside.properties into private storage, then deletes it. */
    private fun importFile(context: Context) {
        val f = File(context.getExternalFilesDir(null) ?: return, "courtside.properties")
        if (!f.exists()) return
        runCatching {
            val props = Properties().apply { f.reader(Charsets.UTF_8).use { load(it) } }
            val url = props.getProperty("COURTSIDE_URL", "").trim().trimEnd('/')
            if (url.startsWith("https://")) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("url", url)
                    .putString("tv", props.getProperty("COURTSIDE_TV_TOKEN", "").trim())
                    .putString("bypass", props.getProperty("COURTSIDE_SITES_BYPASS_TOKEN", "").trim())
                    .apply()
            }
        }
        f.delete()
    }

    /** [game] with Courtside's channel label, if available (network; cached 30 minutes). */
    fun decorate(context: Context, game: Game, now: Long = System.currentTimeMillis()): Game {
        if (!configured(context)) return game
        val hit = synchronized(cache) { cache[game.id] }
        val label = if (hit != null && now - hit.first < 30 * 60_000L) hit.second else {
            val fetched = runCatching { fetch(context, game) }.getOrNull()
            synchronized(cache) { cache[game.id] = now to fetched }
            fetched
        }
        return if (label != null) game.copy(channel = label) else game
    }

    private fun fetch(context: Context, game: Game): String? {
        val zone = "America/Los_Angeles"
        val day = java.time.Instant.ofEpochMilli(game.startMs).atZone(java.time.ZoneId.of(zone)).toLocalDate()
        val text = get(context, "/api/tv/games?date=$day&zone=$zone&teams=${game.team.key}") ?: return null
        return bestChannel(JSONObject(text), game.id)
    }

    /** GET a Courtside TV route ([path] starts with "/api/tv/"): the body on 200, else null. Network: off the main thread. */
    fun get(context: Context, path: String): String? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val url = p.getString("url", null) ?: return null
        val headers = buildMap {
            put("Authorization", "Bearer " + p.getString("tv", ""))
            p.getString("bypass", null)?.takeIf { it.isNotBlank() }?.let { put("OAI-Sites-Authorization", "Bearer $it") }
        }
        val (code, text) = Http.call("GET", url + path, headers)
        return if (code == 200) text else null
    }

    /** The top channel for ESPN event [eventId] as "512 · Spectrum SportsNet LA". Pure, unit-tested. */
    fun bestChannel(root: JSONObject, eventId: String): String? {
        val games = root.optJSONArray("games") ?: return null
        for (i in 0 until games.length()) {
            val g = games.optJSONObject(i) ?: continue
            if (g.optString("eventId") != eventId) continue
            val c = g.optJSONArray("channels")?.optJSONObject(0) ?: return null
            val name = cleanName(c.optString("name"))
            if (name.isBlank()) return null
            val number = c.optString("number").trim()
            return if (number.isNotEmpty()) "$number · $name" else name
        }
        return null
    }

    /** "US: Spectrum SportsNet LA FHD" → "Spectrum SportsNet LA": drop the country prefix and quality tags. */
    fun cleanName(raw: String): String = raw
        .replace(Regex("""^\s*(US|USA|UK|CA)\s*[:|-]\s*""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s*\b(FHD|UHD|HD|SD|4K|8K|RAW|BACKUP)\b""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s{2,}"""), " ")
        .trim()
}

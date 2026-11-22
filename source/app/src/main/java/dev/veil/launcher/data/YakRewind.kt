package dev.veil.launcher.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.util.Properties

/**
 * Yak rewind (PROJECT.md D54): an older, already-aired Yak episode from yak.campsapps.com (repo
 * TheyH8CaMpS/yak), shown in Continue Watching on chosen days once today's Yak card is out of the way.
 * The pick is shared with the phone page: it stays until it's marked seen or skipped.
 *
 * Like Courtside, the address and token are pushed once over ADB and never ship in the APK:
 *   /sdcard/Android/data/app.ravine.launcher/files/yak.properties
 *     YAK_URL=https://yak.campsapps.com
 *     YAK_TV_TOKEN=…
 * Ravine copies it into private storage and deletes the file.
 */
object YakRewind {
    private const val PREFS = "ravine_yak"

    data class Pick(val id: String, val title: String, val date: String, val minutes: Int, val views: Long, val left: Int)

    /** When the card may show. Weeknights start at 5 PM (the daily show is usually watched right after work). */
    enum class Schedule(val label: String) {
        OFF("Off"), WEEKENDS("Weekends"), WEEKENDS_FRIDAY("Fri night + weekends"), EVERY_NIGHT("Every night");

        fun next() = entries[(ordinal + 1) % entries.size]
    }

    fun configured(context: Context): Boolean {
        importFile(context)
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return !p.getString("url", null).isNullOrBlank() && !p.getString("tv", null).isNullOrBlank()
    }

    private fun importFile(context: Context) {
        val f = File(context.getExternalFilesDir(null) ?: return, "yak.properties")
        if (!f.exists()) return
        runCatching {
            val props = Properties().apply { f.reader(Charsets.UTF_8).use { load(it) } }
            val url = props.getProperty("YAK_URL", "").trim().trimEnd('/')
            val token = props.getProperty("YAK_TV_TOKEN", "").trim()
            if (url.startsWith("https://") && token.isNotEmpty()) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("url", url).putString("tv", token).apply()
            }
        }
        f.delete()
    }

    /** Pure, unit-tested: may the card show at [now]? */
    fun allowed(schedule: Schedule, now: Long, zone: ZoneId = ZoneId.of("America/Los_Angeles")): Boolean {
        val t = Instant.ofEpochMilli(now).atZone(zone)
        val weekend = t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY
        val evening = t.hour >= 17
        return when (schedule) {
            Schedule.OFF -> false
            Schedule.WEEKENDS -> weekend
            Schedule.WEEKENDS_FRIDAY -> weekend || (t.dayOfWeek == DayOfWeek.FRIDAY && evening)
            Schedule.EVERY_NIGHT -> weekend || evening
        }
    }

    /** Pure, unit-tested: the `/api/tv/next` (or action) response → the pick, if any. */
    fun parse(text: String): Pick? {
        val root = JSONObject(text)
        val p = root.optJSONObject("pick") ?: return null
        return Pick(
            id = p.optString("id").ifBlank { return null },
            title = p.optString("t"),
            date = p.optString("d"),
            minutes = p.optInt("m"),
            views = p.optLong("v"),
            left = root.optInt("left"),
        )
    }

    /** "Clownin' Around | The Yak 10-8-21" → "Clownin' Around"; the date is shown separately. */
    fun shortTitle(t: String): String =
        t.replace(Regex("""\s*\|+\s*(the yak|yak rewind)?\s*(full episode)?\s*\d{1,2}[-/.]\d{1,2}[-/.]\d{2,4}\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\|\s*full episode\s*$""", RegexOption.IGNORE_CASE), "")
            .trim().ifEmpty { t }

    /** "2022-03-04" → "3-4-22", like the daily card's date. */
    fun dateLabel(d: String): String = runCatching {
        val (y, m, day) = d.split("-").map { it.toInt() }
        "$m-$day-${y % 100}"
    }.getOrDefault(d)

    // ---- network (off the main thread) ----

    fun next(context: Context): Pick? = call(context, "GET", "next", null)?.let(::parse)

    /** "seen", "skip" or "unseen" → the new pick. */
    fun act(context: Context, action: String, id: String?): Pick? =
        call(context, "POST", action, JSONObject().apply { id?.let { put("id", it) } }.toString())?.let(::parse)

    /** SmartTube played a title most of the way: the server marks it seen if it's a New York-era show. */
    fun played(context: Context, title: String) {
        call(context, "POST", "played", JSONObject().put("title", title).toString())
    }

    private fun call(context: Context, method: String, route: String, body: String?): String? {
        if (!configured(context)) return null
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val (code, text) = Http.call(method, "${p.getString("url", "")}/api/tv/$route", mapOf("Authorization" to "Bearer " + p.getString("tv", "")), body)
        return if (code == 200) text else null
    }

    fun toItem(p: Pick) = WatchItem(
        isMovie = true,
        title = "Yak rewind",
        traktId = null, imdb = null, tmdb = null,
        sortTime = 0L,
        backdrop = "https://i.ytimg.com/vi/${p.id}/maxresdefault.jpg",
        thumb = "https://i.ytimg.com/vi/${p.id}/mqdefault.jpg",
        youtubeId = p.id,
        subtitle = "${dateLabel(p.date)} · ${shortTitle(p.title)}",
        rewind = true,
    )
}

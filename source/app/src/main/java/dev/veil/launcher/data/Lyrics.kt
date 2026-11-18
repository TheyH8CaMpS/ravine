package dev.veil.launcher.data

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.math.abs

/**
 * Song lyrics from LRCLIB (lrclib.net): a free, open lyrics database with no account or API key.
 * Only the song's title, artist, album and length are sent; results are cached on the TV, so each song is
 * looked up once.
 *
 * Preference order: word-synced (each word timed) → line-synced → plain text (timing estimated) → nothing.
 */
object Lyrics {
    enum class Kind { WORDS, LINES, PLAIN, INSTRUMENTAL }

    @Immutable data class Word(val text: String, val startMs: Long, val endMs: Long)

    /** One line. [text] empty = a musical break (shown as soft breathing dots). */
    @Immutable data class Line(val text: String, val startMs: Long, val endMs: Long, val words: List<Word> = emptyList())

    @Immutable class Song(val kind: Kind, val lines: List<Line>) {
        val timed: Boolean get() = kind == Kind.WORDS || kind == Kind.LINES

        /** Index of the line playing at [posMs] (-1 before the first line). */
        fun indexAt(posMs: Long): Int {
            var lo = 0; var hi = lines.size - 1; var ans = -1
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                if (lines[mid].startMs <= posMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
            }
            return ans
        }
    }

    /** What the UI shows: still looking, found, or nothing (not found / offline / LRCLIB down). */
    sealed interface State {
        object Loading : State
        class Ready(val song: Song) : State
        object None : State
    }

    private const val API = "https://lrclib.net/api"
    private val memory = object : LinkedHashMap<String, Song?>(32, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Song?>?) = size > 40
    }
    /** After a network failure / rate limit, wait before asking again (per song, and globally on 429). */
    private val retryAfter = HashMap<String, Long>()
    @Volatile private var pausedUntil = 0L

    /**
     * Lyrics for a song, or null if there are none (or LRCLIB can't be reached right now). Blocking: call on
     * an IO thread.
     */
    fun load(context: Context, title: String, artist: String?, album: String?, durationMs: Long): Song? {
        // "v2": Ravine's looser matching; ignores "no lyrics" results cached by earlier builds.
        val key = "v2|$title|${artist.orEmpty()}|${album.orEmpty()}|${durationMs / 2000}"
        val dir = File(context.cacheDir, "lyrics").apply { mkdirs() }
        val file = File(dir, sha1(key) + ".json")
        synchronized(memory) {
            if (memory.containsKey(key)) { touch(file); return memory[key] }
        }
        readCache(file)?.let { (song, fresh) ->
            if (fresh) { touch(file); synchronized(memory) { memory[key] = song }; return song }
        }
        val now = SystemClock.elapsedRealtime()
        if (now < pausedUntil || (retryAfter[key] ?: 0L) > now) return null
        return try {
            val song = fetch(title, artist, album, durationMs)
            synchronized(memory) { memory[key] = song }
            writeCache(file, song)
            trim(dir)
            song
        } catch (e: Exception) {
            // Offline, LRCLIB down or rate-limited: show the normal layout, try again in a couple of minutes.
            retryAfter[key] = now + 120_000
            null
        }
    }

    // ---- Lookup ----------------------------------------------------------------------------------

    private class Unavailable : Exception()

    private fun get(url: String): Pair<Int, String> {
        val (code, text) = Http.call("GET", url)
        if (code == 429 || code == 503) { pausedUntil = SystemClock.elapsedRealtime() + 60_000; throw Unavailable() }
        if (code >= 500 || code == 0) throw Unavailable()
        return code to text
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun fetch(title: String, artist: String?, album: String?, durationMs: Long): Song? {
        val mainArtist = artist?.let { primaryArtist(it) }
        val wantSec = (durationMs / 1000).toInt()
        // 1) Search, so a word-synced version can be preferred when several exist.
        for (t in listOf(title, cleanTitle(title)).distinct()) {
            val url = "$API/v2/search?track_name=${enc(t)}" + (mainArtist?.let { "&artist_name=${enc(it)}" } ?: "")
            val (code, text) = get(url)
            if (code != 200) continue
            val best = pickBest(JSONArray(text), t, mainArtist, wantSec) ?: continue
            if (best.optBoolean("instrumental")) return Song(Kind.INSTRUMENTAL, emptyList())
            val (c2, rec) = get("$API/get/${best.getLong("id")}")
            if (c2 == 200) parseRecord(JSONObject(rec), durationMs)?.let { return it }
        }
        // 2) LRCLIB's own best match.
        if (mainArtist != null) {
            val url = "$API/get?track_name=${enc(cleanTitle(title))}&artist_name=${enc(mainArtist)}" +
                (album?.let { "&album_name=${enc(it)}" } ?: "") +
                (if (wantSec in 1..3600) "&duration=$wantSec" else "")
            val (code, text) = get(url)
            if (code == 200) return parseRecord(JSONObject(text), durationMs)
        }
        // 3) Ravine: no version within 6 s (e.g. Spotify's cut is a different length from every LRCLIB entry).
        // Take the closest one within 25 s, but only its words, paced across the song: its timing belongs to
        // another cut and would drift. Untimed lyrics beat no lyrics.
        for (t in listOf(title, cleanTitle(title)).distinct()) {
            val url = "$API/search?track_name=${enc(t)}" + (mainArtist?.let { "&artist_name=${enc(it)}" } ?: "")
            val (code, text) = get(url)
            if (code != 200) continue
            val near = pickBest(JSONArray(text), t, mainArtist, wantSec, maxOff = 25.0) ?: continue
            val words = near.str("plainLyrics") ?: near.str("syncedLyrics")?.let { stripLrc(it) } ?: continue
            estimatePlain(words, durationMs.takeIf { it > 0 } ?: (near.optDouble("duration", 0.0) * 1000).toLong())
                ?.takeIf { it.lines.isNotEmpty() }?.let { return it }
        }
        return null
    }

    /** LRC text without its [mm:ss.xx] time tags. */
    internal fun stripLrc(lrc: String): String =
        lrc.lines().joinToString("\n") { it.replace(Regex("""\[\d{1,2}:\d{2}(?:[.:]\d{1,3})?]"""), "").trim() }

    /** Best search hit: right song, right artist, right length; word-synced > line-synced > plain. */
    internal fun pickBest(results: JSONArray, title: String, artist: String?, wantSec: Int, maxOff: Double = 6.0): JSONObject? {
        var best: JSONObject? = null
        var bestScore = Double.NEGATIVE_INFINITY
        val nt = norm(cleanTitle(title)); val na = artist?.let { norm(it) }
        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            val rt = norm(cleanTitle(r.optString("trackName"))); val ra = norm(r.optString("artistName"))
            if (rt.isEmpty() || !(rt == nt || rt.contains(nt) || nt.contains(rt))) continue
            if (na != null && na.isNotEmpty() && !(ra.contains(na) || na.contains(ra))) continue
            val d = r.optDouble("duration", 0.0)
            val off = if (wantSec > 0 && d > 0) abs(d - wantSec) else 0.0
            if (off > maxOff) continue // a different version (live, remix, edit…)
            val quality = when {
                r.optBoolean("hasWordSync") -> 3
                r.optBoolean("hasSyncedLyrics") -> 2
                r.optBoolean("hasPlainLyrics") -> 1
                r.optBoolean("instrumental") -> 0
                else -> -1
            }
            if (quality < 0) continue
            val score = quality * 10.0 - off - (if (rt == nt) 0.0 else 1.5)
            if (score > bestScore) { bestScore = score; best = r }
        }
        return best
    }

    private fun parseRecord(j: JSONObject, durationMs: Long): Song? {
        if (j.optBoolean("instrumental")) return Song(Kind.INSTRUMENTAL, emptyList())
        val total = durationMs.takeIf { it > 0 } ?: (j.optDouble("duration", 0.0) * 1000).toLong()
        if (j.optBoolean("hasWordSync")) {
            j.str("lyricsfile")?.let { runCatching { parseLyricsfile(it, total) }.getOrNull() }
                ?.takeIf { s -> s.lines.any { it.words.isNotEmpty() } }?.let { return it }
        }
        j.str("syncedLyrics")?.let { runCatching { parseLrc(it, total) }.getOrNull() }?.takeIf { it.lines.isNotEmpty() }?.let { return it }
        j.str("plainLyrics")?.let { estimatePlain(it, total) }?.takeIf { it.lines.isNotEmpty() }?.let { return it }
        return null
    }

    // ---- Formats -----------------------------------------------------------------------------------

    /**
     * LRCLIB's Lyricsfile (YAML): `lines: - text / start_ms / end_ms / words: - text / start_ms`.
     * A small purpose-built reader for exactly that shape (no YAML library needed).
     */
    internal fun parseLyricsfile(yaml: String, totalMs: Long): Song {
        class L(var text: String = "", var start: Long = -1, var end: Long = -1, val words: MutableList<Triple<String, Long, Long>> = mutableListOf())
        val lines = mutableListOf<L>()
        var inLines = false
        var inWords = false
        for (raw in yaml.lines()) {
            if (raw.isBlank()) continue
            val indent = raw.indexOfFirst { it != ' ' }
            val s = raw.trim()
            if (indent == 0 && !s.startsWith("- ")) { inLines = s.startsWith("lines:"); inWords = false; continue }
            if (!inLines) continue
            when {
                indent == 0 && s.startsWith("- text:") -> { lines += L(text = scalar(s.removePrefix("- text:"))); inWords = false }
                indent == 2 && s.startsWith("start_ms:") -> lines.lastOrNull()?.start = s.removePrefix("start_ms:").trim().toLongOrNull() ?: -1
                indent == 2 && s.startsWith("end_ms:") -> lines.lastOrNull()?.end = s.removePrefix("end_ms:").trim().toLongOrNull() ?: -1
                indent == 2 && s.startsWith("words:") -> inWords = true
                inWords && s.startsWith("- text:") -> lines.lastOrNull()?.words?.add(Triple(scalar(s.removePrefix("- text:")), -1L, -1L))
                inWords && s.startsWith("start_ms:") -> lines.lastOrNull()?.words?.let { w ->
                    if (w.isNotEmpty()) w[w.lastIndex] = w.last().copy(second = s.removePrefix("start_ms:").trim().toLongOrNull() ?: -1)
                }
                inWords && s.startsWith("end_ms:") -> lines.lastOrNull()?.words?.let { w ->
                    if (w.isNotEmpty()) w[w.lastIndex] = w.last().copy(third = s.removePrefix("end_ms:").trim().toLongOrNull() ?: -1)
                }
            }
        }
        val timed = lines.filter { it.start >= 0 }
        val out = timed.mapIndexed { i, l ->
            val end = if (l.end > l.start) l.end else timed.getOrNull(i + 1)?.start ?: (l.start + 5_000)
            val ws = l.words.filter { it.second >= 0 }
            val words = ws.mapIndexed { k, w ->
                val wEnd = if (w.third > w.second) w.third else ws.getOrNull(k + 1)?.second ?: end
                Word(w.first, w.second, wEnd)
            }
            Line(l.text.trim(), l.start, end, words)
        }
        return Song(if (out.any { it.words.isNotEmpty() }) Kind.WORDS else Kind.LINES, withBreaks(out, totalMs))
    }

    /** YAML scalar: plain, 'single-quoted' ('' = '), or "double-quoted" (JSON-style escapes). */
    private fun scalar(v: String): String {
        val t = v.trim()
        return when {
            t.isEmpty() || t == "~" || t == "null" -> ""
            t.startsWith("'") && t.endsWith("'") && t.length >= 2 -> t.substring(1, t.length - 1).replace("''", "'")
            t.startsWith("\"") && t.endsWith("\"") && t.length >= 2 -> runCatching { JSONArray("[$t]").getString(0) }.getOrDefault(t.trim('"'))
            else -> t
        }
    }

    private val TAG = Regex("""\[(\d{1,3}):(\d{1,2}(?:[.:]\d{1,3})?)]""")
    private val WORD_TAG = Regex("""<(\d{1,3}):(\d{1,2}(?:[.:]\d{1,3})?)>""")
    private fun ms(min: String, sec: String) = (min.toLong() * 60_000 + (sec.replace(':', '.').toDouble() * 1000)).toLong()

    /** Standard LRC ("[01:23.45] line"), including enhanced "<01:23.45> word" timings when present. */
    internal fun parseLrc(lrc: String, totalMs: Long): Song {
        val entries = mutableListOf<Pair<Long, String>>()
        for (raw in lrc.lines()) {
            val tags = TAG.findAll(raw).toList()
            if (tags.isEmpty()) continue
            val text = raw.substring(tags.last().range.last + 1).trim()
            for (m in tags) entries += ms(m.groupValues[1], m.groupValues[2]) to text
        }
        entries.sortBy { it.first }
        var anyWords = false
        val out = entries.mapIndexed { i, (start, text) ->
            val end = entries.getOrNull(i + 1)?.first ?: (if (totalMs > start) minOf(totalMs, start + 8_000) else start + 5_000)
            val wordTags = WORD_TAG.findAll(text).toList()
            if (wordTags.isEmpty()) {
                Line(text, start, end)
            } else {
                anyWords = true
                val words = wordTags.mapIndexed { k, m ->
                    val from = m.range.last + 1
                    val to = wordTags.getOrNull(k + 1)?.range?.first ?: text.length
                    val wStart = ms(m.groupValues[1], m.groupValues[2])
                    val wEnd = wordTags.getOrNull(k + 1)?.let { ms(it.groupValues[1], it.groupValues[2]) } ?: end
                    Word(text.substring(from, to), wStart, wEnd)
                }.filter { it.text.isNotBlank() }
                Line(words.joinToString("") { it.text }.trim(), start, end, words)
            }
        }
        return Song(if (anyWords) Kind.WORDS else Kind.LINES, withBreaks(out, totalMs))
    }

    /**
     * Plain lyrics: estimate when each line is sung. Singing usually starts a little in and ends a little
     * before the end; longer lines take longer; a blank line (verse/chorus gap) is a short pause. The UI
     * scrolls these slowly and softly rather than pretending to be exact.
     */
    internal fun estimatePlain(text: String, totalMs: Long): Song? {
        val raw = text.lines().map { it.trim() }
        if (raw.none { it.isNotEmpty() }) return null
        val total = if (totalMs > 30_000) totalMs else 210_000L
        val start = (total * 0.07).toLong()
        val end = (total * 0.93).toLong()
        fun weight(l: String) = if (l.isEmpty()) 1.6 else 1.0 + l.length / 28.0
        val sum = raw.sumOf { weight(it) }
        var t = start.toDouble()
        val lines = raw.map { l ->
            val d = (end - start) * weight(l) / sum
            val line = Line(l, t.toLong(), (t + d).toLong())
            t += d
            line
        }
        // Collapse runs of blank lines into one.
        val out = lines.filterIndexed { i, l -> l.text.isNotEmpty() || (i > 0 && lines[i - 1].text.isNotEmpty()) }
        return Song(Kind.PLAIN, out)
    }

    /** Marks long gaps (intro, solos) as breaks, so the UI can show breathing dots there. */
    private fun withBreaks(lines: List<Line>, totalMs: Long): List<Line> {
        if (lines.isEmpty()) return lines
        val out = mutableListOf<Line>()
        if (lines.first().startMs > 6_000) out += Line("", 0, lines.first().startMs)
        for ((i, l) in lines.withIndex()) {
            out += l
            val next = lines.getOrNull(i + 1) ?: break
            if (l.text.isNotEmpty() && next.startMs - l.endMs > 6_000) out += Line("", l.endMs, next.startMs)
        }
        return out
    }

    // ---- Matching helpers --------------------------------------------------------------------------

    /** "Song - Remastered 2011", "Song (feat. X)", "Song - Live at …" → "Song". */
    internal fun cleanTitle(t: String): String = t
        .replace(Regex("""\s*[(\[](feat\.?|ft\.?|with)\s[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s+-\s+.*\b(remaster(ed)?|live|version|edit|mono|stereo|mix|remix|bonus|deluxe|acoustic)\b.*$""", RegexOption.IGNORE_CASE), "")
        .trim()

    /** "Artist A, Artist B" / "A & B" / "A feat. B" → "Artist A". */
    private fun primaryArtist(a: String): String =
        a.split(Regex("""\s*(,|&|\bfeat\.?|\bft\.?|\bx\b|;)\s*""", RegexOption.IGNORE_CASE)).first().trim().ifEmpty { a }

    private fun norm(s: String) = s.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

    // ---- Cache (on the TV, so each song is looked up once) -----------------------------------------

    /** The cache keeps the most recently played songs. */
    internal const val KEEP_SONGS = 500
    /** Trim only once it's this far over, so the cleanup runs every ~20 new songs, not on every song. */
    private const val TRIM_AT = KEEP_SONGS + 20

    /** Marks a song as just played (its file's date is what "recent" means). */
    private fun touch(f: File) {
        runCatching { if (f.exists()) f.setLastModified(System.currentTimeMillis()) }
    }

    /**
     * Keeps the [KEEP_SONGS] most recently played songs and deletes the rest. Runs on the background thread
     * that downloads lyrics (never on the UI thread), and only once the folder is [TRIM_AT] files big.
     */
    internal fun trim(dir: File) = runCatching {
        val files = dir.listFiles() ?: return@runCatching
        if (files.size <= TRIM_AT) return@runCatching
        files.sortedBy { it.lastModified() }.take(files.size - KEEP_SONGS).forEach { it.delete() }
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun writeCache(f: File, song: Song?) = runCatching {
        val j = JSONObject().put("saved", System.currentTimeMillis())
        if (song != null) {
            j.put("kind", song.kind.name)
            j.put("lines", JSONArray().apply {
                song.lines.forEach { l ->
                    put(JSONObject().put("t", l.text).put("s", l.startMs).put("e", l.endMs).apply {
                        if (l.words.isNotEmpty()) put("w", JSONArray().apply {
                            l.words.forEach { w -> put(JSONObject().put("t", w.text).put("s", w.startMs).put("e", w.endMs)) }
                        })
                    })
                }
            })
        }
        f.writeText(j.toString())
    }

    /** (song or null for "no lyrics", is it fresh). "No lyrics" is re-checked after 3 days. */
    private fun readCache(f: File): Pair<Song?, Boolean>? = runCatching {
        if (!f.exists()) return null
        val j = JSONObject(f.readText())
        val age = System.currentTimeMillis() - j.optLong("saved")
        if (!j.has("kind")) return null to (age < 3 * 24 * 3_600_000L)
        val arr = j.getJSONArray("lines")
        val lines = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val w = o.optJSONArray("w")
            Line(
                o.getString("t"), o.getLong("s"), o.getLong("e"),
                if (w == null) emptyList() else (0 until w.length()).map { k ->
                    val x = w.getJSONObject(k); Word(x.getString("t"), x.getLong("s"), x.getLong("e"))
                },
            )
        }
        Song(Kind.valueOf(j.getString("kind")), lines) to true
    }.getOrNull()
}

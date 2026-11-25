package dev.veil.launcher.data

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Daily YouTube shows on Home (PROJECT.md D34). Today: The Yak (Barstool Yak, weekdays ~1 PM ET). When the day's
 * full stream is up, Continue Watching starts with it ("NEW EPISODE" until opened), and OK opens that video in
 * SmartTube. Source: YouTube's public channel feed (no key, no account).
 *
 * The full show is told apart from clips and "Best of" recaps by its title: "… | The Yak 11-18-26".
 */
object YouTubeShows {
    data class Show(val key: String, val name: String, val channelId: String, val episodeTitle: Regex, val exclude: Regex)
    data class Episode(val show: Show, val videoId: String, val title: String, val dateLabel: String, val publishedMs: Long)

    val THE_YAK = Show(
        key = "yak", name = "The Yak", channelId = "UC8BZMZxcXNJLkVtmR_cd1nA",
        episodeTitle = Regex("""\|\s*The Yak\s+(\d{1,2}-\d{1,2}-\d{2})\s*$""", RegexOption.IGNORE_CASE),
        exclude = Regex("""best of""", RegexOption.IGNORE_CASE),
    )

    internal val SMARTTUBE = listOf(
        "com.teamsmart.videomanager.tv", "com.liskovsoft.smarttubetv.beta", "org.smarttube.stable", "org.smarttube.beta",
    )

    /** Newest full episode in [feed] published within [maxAgeMs] of [now]. Pure: unit-tested on a saved feed. */
    fun latestEpisode(show: Show, feed: String, now: Long, maxAgeMs: Long = 40 * 60 * 60_000L): Episode? =
        feed.split("<entry>").drop(1).mapNotNull { entry ->
            val id = tag(entry, "yt:videoId") ?: return@mapNotNull null
            val title = tag(entry, "title")?.let(::unescape) ?: return@mapNotNull null
            val published = tag(entry, "published")?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
                ?: return@mapNotNull null
            val m = show.episodeTitle.find(title) ?: return@mapNotNull null
            if (show.exclude.containsMatchIn(title)) return@mapNotNull null
            Episode(show, id, title.substring(0, m.range.first).trim(), m.groupValues[1], published)
        }.filter { now - it.publishedMs in 0..maxAgeMs }.maxByOrNull { it.publishedMs }

    /** Fetches the show's feed. Network: off the main thread. */
    fun fetch(show: Show, now: Long = System.currentTimeMillis()): Episode? {
        val (code, text) = Http.call("GET", "https://www.youtube.com/feeds/videos.xml?channel_id=${show.channelId}")
        return if (code == 200) latestEpisode(show, text, now) else null
    }

    fun opened(context: Context, videoId: String): Boolean =
        videoId in context.getSharedPreferences("ravine_shows", Context.MODE_PRIVATE).getStringSet("opened", emptySet())!!

    /** Opens the episode in SmartTube (or whatever handles YouTube links) and remembers it was opened. */
    fun open(context: Context, videoId: String): Boolean {
        val prefs = context.getSharedPreferences("ravine_shows", Context.MODE_PRIVATE)
        val seen = (prefs.getStringSet("opened", emptySet())!!.toList().takeLast(30) + videoId).toSet()
        prefs.edit().putStringSet("opened", seen).apply()
        return openVideo(context, videoId)
    }

    /** Open a video without marking it as a watched Yak episode. */
    fun openVideo(context: Context, videoId: String): Boolean {
        val uri = Uri.Builder().scheme("https").authority("www.youtube.com").path("/watch")
            .appendQueryParameter("v", videoId).build()
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val smartTube = NetflixButton.smartTube(context)
        if (smartTube != null && runCatching {
            context.startActivity(Intent(intent).setPackage(smartTube))
        }.isSuccess) return true
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    fun toItem(e: Episode, isNew: Boolean) = WatchItem(
        isMovie = true,
        title = e.show.name,
        traktId = null, imdb = null, tmdb = null,
        isNew = isNew,
        sortTime = e.publishedMs,
        backdrop = "https://i.ytimg.com/vi/${e.videoId}/maxresdefault.jpg",
        thumb = "https://i.ytimg.com/vi/${e.videoId}/mqdefault.jpg",
        youtubeId = e.videoId,
        subtitle = "${e.dateLabel} · ${e.title}",
    )

    private fun tag(s: String, name: String): String? {
        val start = s.indexOf("<$name>").takeIf { it >= 0 } ?: return null
        val end = s.indexOf("</$name>", start).takeIf { it >= 0 } ?: return null
        return s.substring(start + name.length + 2, end).trim()
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")
}

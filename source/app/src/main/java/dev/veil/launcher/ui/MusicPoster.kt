package dev.veil.launcher.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.veil.launcher.data.ArtistArt
import dev.veil.launcher.data.NowPlaying
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Home while music plays (PROJECT.md D51). Poster (default): the artist fills the screen, the song title set
 * poster-large. Sleeve: the album cover like a print on the wall, over a soft field of its own colours.
 * Without a wide artist picture the Poster falls back to a square photo at full height on the right, and
 * without any photo to a field built from the cover's colours, so nothing is ever a blurry stretch.
 */
object MusicPoster {
    const val OFF = "off"
    const val POSTER = "poster"
    const val SLEEVE = "sleeve"

    /** Video apps also report "now playing"; only music and podcasts get the poster. */
    private val VIDEO = setOf(
        "com.nuvio.tv", "org.smarttube.stable", "org.smarttube.beta", "com.liskovsoft.smarttubetv.beta",
        "com.teamsmart.videomanager.tv", "ar.tvplayer.tv", "com.netflix.ninja", "com.google.android.youtube.tv",
        "com.plexapp.android", "com.amazon.amazonvideo.livingroom", "com.apple.atve.androidtv.appletv", "com.cbs.ott",
        "com.google.android.videos", "tv.twitch.android.app", "com.bamnetworks.mobile.android.gameday.atbat",
        "com.playon.nfhslive.googletv", "air.com.vudu.air.DownloaderTablet",
    )

    fun isMusic(pkg: String) = pkg !in VIDEO

    fun label(style: String) = when (style) { POSTER -> "Poster"; SLEEVE -> "Sleeve"; else -> "Off" }
}

/** The artist picture for [track] (network on first sight of an artist; cached after). */
@Composable
fun rememberArtistPick(track: NowPlaying.Track?): ArtistArt.Pick {
    val context = LocalContext.current
    return produceState<ArtistArt.Pick>(ArtistArt.Pick.None, track?.artist, track?.title) {
        value = if (track == null) ArtistArt.Pick.None else withContext(Dispatchers.IO) {
            runCatching { ArtistArt.find(context.applicationContext, track.artist, track.title) }.getOrDefault(ArtistArt.Pick.None)
        }
    }.value
}

/** Two colours from the cover: a deep field colour and a lighter glow. */
@Composable
private fun rememberCoverColors(art: ImageBitmap?): Pair<Color, Color> {
    val bg = ThemeState.current.bg
    return remember(art, bg) {
        val c = art?.let { ArtColor.extract(it) }.orEmpty()
        val a = c.getOrNull(0) ?: Veil.Accent
        val b = c.getOrNull(1) ?: a
        lerp(bg, a, 0.5f) to lerp(bg, b, 0.78f)
    }
}

/**
 * Full-screen layer drawn over the wallpaper while music plays (the Poster's wide artist picture is drawn by
 * Home's own backdrop instead, so it drifts like the photos).
 */
@Composable
fun MusicBackground(track: NowPlaying.Track, style: String, pick: ArtistArt.Pick) {
    val context = LocalContext.current
    val (field, glow) = rememberCoverColors(track.art)
    val soft = rememberSoftArt(track.artLarge ?: track.art)
    Crossfade(targetState = Triple(style, pick, field), animationSpec = tween(700), label = "music-bg") { (s, p, f) ->
        when {
            s == MusicPoster.SLEEVE -> Box(Modifier.fillMaxSize().background(f)) {
                soft?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f)))
            }
            p is ArtistArt.Pick.Wide -> Unit // Home's backdrop shows it
            p is ArtistArt.Pick.Square -> Row(Modifier.fillMaxSize().background(f)) {
                Spacer(Modifier.weight(1f))
                Box(Modifier.fillMaxHeight().aspectRatio(1f)) {
                    // Full height: a 1000 px photo on a 1080-line screen, so it stays sharp.
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(p.url).crossfade(true).size(1000, 1000).build(),
                        contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                    )
                    // Its left edge melts into the colour field.
                    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to f, 0.38f to f.copy(alpha = 0f))))
                }
            }
            else -> Box(
                // No photo: a field from the cover's own colours; the grain on top gives it texture.
                Modifier.fillMaxSize()
                    .background(Brush.linearGradient(listOf(lerp(f, Color.Black, 0.45f), f, lerp(f, Color.Black, 0.6f))))
                    .background(Brush.radialGradient(listOf(glow.copy(alpha = 0.55f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(1500f, 260f), radius = 1300f)),
            )
        }
    }
}

/** The song, in place of the photo caption on Home. */
@Composable
fun MusicCaption(track: NowPlaying.Track, style: String, pick: ArtistArt.Pick, appLabel: String?, modifier: Modifier) {
    val pos by produceState(track.positionNow(), track) { while (true) { value = track.positionNow(); delay(500) } }
    val lyrics = rememberLyrics(track)
    val line = (lyrics as? dev.veil.launcher.data.Lyrics.State.Ready)?.song?.takeIf { it.timed }?.let { s ->
        val i = s.indexAt(pos + LEAD_MS)
        s.lines.getOrNull(i)?.text?.takeIf { it.isNotBlank() } to s.lines.getOrNull(i + 1)?.text?.takeIf { it.isNotBlank() }
    }
    val progress = if (track.durationMs > 0) (pos.toFloat() / track.durationMs).coerceIn(0f, 1f) else null
    if (style == MusicPoster.SLEEVE) Sleeve(track, appLabel, pos, progress, line, modifier)
    else Poster(track, pick, pos, progress, line?.first, modifier)
}

@Composable
private fun Poster(track: NowPlaying.Track, pick: ArtistArt.Pick, pos: Long, progress: Float?, lyric: String?, modifier: Modifier) {
    Column(modifier.fillMaxWidth()) {
        lyric?.let {
            Text(
                "“$it”", fontFamily = Display, fontStyle = FontStyle.Italic, fontSize = 15.sp, lineHeight = 19.sp,
                color = Veil.Text.copy(alpha = 0.8f), maxLines = 2, modifier = Modifier.widthIn(max = 360.dp).padding(bottom = 16.dp),
            )
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("NOW PLAYING", fontSize = 7.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = Veil.Accent)
                Text(
                    track.title, fontFamily = Display, fontSize = 56.sp, lineHeight = 54.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
                )
                track.artist?.let {
                    Text(it, fontFamily = Display, fontStyle = FontStyle.Italic, fontSize = 22.sp, color = Veil.Text.copy(alpha = 0.85f), maxLines = 1)
                }
            }
            // Details and a small cover, bottom right; larger when there's no artist photo behind.
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(horizontalAlignment = Alignment.End) {
                    track.album?.takeIf { it.isNotBlank() && it != track.title }?.let {
                        Text(it.uppercase(), fontSize = 7.sp, letterSpacing = 1.2.sp, color = Veil.Text.copy(alpha = 0.6f), maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
                    }
                    if (track.durationMs > 0) {
                        Text("${clock(pos)} / ${clock(track.durationMs)}", fontSize = 7.sp, letterSpacing = 1.2.sp, color = Veil.Text.copy(alpha = 0.6f),
                            modifier = Modifier.padding(top = 3.dp))
                    }
                }
                (track.artLarge ?: track.art)?.let {
                    val size = coverSize(track, if (pick is ArtistArt.Pick.None) 130.dp else 92.dp)
                    Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).shadow(14.dp, RoundedCornerShape(2.dp)))
                }
            }
        }
        progress?.let { Progress(it, Modifier.padding(top = 14.dp).fillMaxWidth()) }
    }
}

@Composable
private fun Sleeve(track: NowPlaying.Track, appLabel: String?, pos: Long, progress: Float?, line: Pair<String?, String?>?, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // The 640 px copy (the 96 px one is for the pill); a small cover is shown smaller over its own blur.
        (track.artLarge ?: track.art)?.let {
            Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.size(coverSize(track, 210.dp)).shadow(22.dp, RoundedCornerShape(2.dp)))
        }
        Spacer(Modifier.width(30.dp))
        Column(Modifier.weight(1f)) {
            Text(listOfNotNull("NOW PLAYING", appLabel?.uppercase()).joinToString("  ·  "), fontSize = 7.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp, color = Veil.Accent)
            Text(track.title, fontFamily = Display, fontSize = 44.sp, lineHeight = 44.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp))
            track.artist?.let { Text(it, fontFamily = Display, fontStyle = FontStyle.Italic, fontSize = 20.sp, color = Veil.Text.copy(alpha = 0.85f), maxLines = 1) }
            track.album?.takeIf { it.isNotBlank() && it != track.title }?.let {
                Text("FROM “${it.uppercase()}”", fontSize = 7.sp, letterSpacing = 1.2.sp, color = Veil.Text.copy(alpha = 0.6f), maxLines = 1,
                    modifier = Modifier.padding(top = 8.dp))
            }
            progress?.let {
                Progress(it, Modifier.padding(top = 14.dp).width(300.dp))
                Row(Modifier.width(300.dp).padding(top = 4.dp)) {
                    Text(clock(pos), fontSize = 7.sp, color = Veil.Text.copy(alpha = 0.6f))
                    Spacer(Modifier.weight(1f))
                    Text("−" + clock((track.durationMs - pos).coerceAtLeast(0)), fontSize = 7.sp, color = Veil.Text.copy(alpha = 0.6f))
                }
            }
            line?.first?.let {
                Text("“$it”", fontFamily = Display, fontStyle = FontStyle.Italic, fontSize = 16.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 14.dp))
            }
            line?.second?.let {
                Text(it, fontFamily = Display, fontStyle = FontStyle.Italic, fontSize = 12.sp, color = Veil.Text.copy(alpha = 0.4f), maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Progress(f: Float, modifier: Modifier) {
    Box(modifier.height(1.dp).background(Veil.Text.copy(alpha = 0.18f))) {
        Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(Veil.Accent))
    }
}

private fun clock(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

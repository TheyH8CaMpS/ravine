@file:OptIn(ExperimentalLayoutApi::class)

package dev.veil.launcher.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import androidx.tv.material3.LocalTextStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.tv.material3.LocalContentColor
import dev.veil.launcher.data.Lyrics
import dev.veil.launcher.data.NowPlaying
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/** Highlights run this far ahead of the reported position, so they land with the voice, not after it. */
internal const val LEAD_MS = 180L

/** Lyrics for the playing song: Loading, then Ready or None. Re-fetched only when the song changes. */
@Composable
fun rememberLyrics(track: NowPlaying.Track?): Lyrics.State {
    val context = LocalContext.current
    val key = track?.let { "${it.pkg}|${it.title}|${it.artist}" }
    return produceState<Lyrics.State>(Lyrics.State.Loading, key) {
        if (track == null) { value = Lyrics.State.None; return@produceState }
        value = Lyrics.State.Loading
        val song = withContext(Dispatchers.IO) {
            runCatching { Lyrics.load(context.applicationContext, track.title, track.artist, track.album, track.durationMs) }.getOrNull()
        }
        value = if (song != null && (song.lines.isNotEmpty() || song.kind == Lyrics.Kind.INSTRUMENTAL)) Lyrics.State.Ready(song) else Lyrics.State.None
    }.value
}

/** The playback position, updated every frame (read it in draw/layer blocks to avoid recomposing). */
@Composable
fun rememberPlaybackPosition(track: NowPlaying.Track): MutableLongState {
    val pos = remember { mutableLongStateOf(track.positionNow()) }
    LaunchedEffect(track) {
        while (true) withFrameMillis { pos.longValue = track.positionNow() + LEAD_MS }
    }
    return pos
}

/** True when there's something worth showing (timed or plain lyrics with text). */
fun Lyrics.State.hasLines(): Boolean = this is Lyrics.State.Ready && song.lines.any { it.text.isNotEmpty() }

enum class LyricsLook(val size: TextUnit, val lineGap: Dp, val anchor: Float, val overlay: Boolean = false) {
    /** Next to the album art in Veil music mode. */
    PANEL(30.sp, 18.dp, 0.36f),
    /** The full-screen lyrics view. */
    FULL(42.sp, 24.dp, 0.38f),
    /** Over the visualizer: a short centred strip, the current line with the next one rising up behind it. */
    OVERLAY(34.sp, 12.dp, 0.4f, overlay = true),
}

/**
 * Scrolling lyrics (Apple Music style): the current line bright and full size, the rest smaller and dimmer
 * the further away they are, the list gliding up as the song moves on. Word-synced songs light up word by
 * word; line-synced ones line by line; plain (untimed) lyrics drift slowly with a soft focus instead of
 * pretending to be exact.
 *
 * Lazy: only the lines near the screen exist, so a new song costs a handful of lines, not the whole song.
 * The scroll follows its target every frame: it lands on the right line straight away (even mid-song),
 * then glides.
 */
@Composable
fun LyricsScroller(song: Lyrics.Song, pos: MutableLongState, look: LyricsLook, modifier: Modifier = Modifier) {
    val lines = song.lines
    val plain = song.kind == Lyrics.Kind.PLAIN
    val center = look.overlay
    val list = remember(song) { LazyListState() }
    // The current line is worked out in the scroll loop below and only published when it changes (a
    // per-frame derived state churned garbage, and the clean-ups stuttered the animation).
    var current by remember(song) { mutableIntStateOf(song.indexAt(pos.longValue)) }
    // The next line that has words (skips instrumental breaks), previewed faintly in the overlay.
    val nextText = remember(song, current) { ((current + 1) until lines.size).firstOrNull { lines[it].text.isNotEmpty() } ?: -1 }
    // Where each line sits in the list (updated on layout; read when fading lines at the edges).
    val tops = remember(song) { FloatArray(lines.size) }
    val heights = remember(song) { FloatArray(lines.size) }
    var viewport by remember { mutableIntStateOf(0) }
    var placed by remember(song) { mutableStateOf(false) }
    val density = LocalDensity.current

    // Sub-pixel smooth scrolling: the list itself only scrolls in whole pixels (which made the text shiver
    // a pixel back and forth every frame), so whole pixels go to the list and the leftover fraction is a
    // layer shift.
    val shift = remember(song) { mutableFloatStateOf(0f) }
    LaunchedEffect(song, pos, viewport) {
        if (viewport == 0 || lines.isEmpty()) return@LaunchedEffect
        var last = 0L
        while (true) {
            val now = withFrameMillis { it }
            val p = pos.longValue
            val raw = song.indexAt(p)
            if (raw != current) current = raw
            val i = raw.coerceIn(0, lines.lastIndex)
            val info = list.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == i }
            val anchor = viewport * look.anchor
            if (item == null) {
                // Far away (first show, or a seek): jump there; the next frames fine-tune.
                list.scrollToItem(i)
                shift.floatValue = 0f
                last = 0L
                continue
            }
            val top = (item.offset - info.viewportStartOffset).toFloat()
            val y = if (plain) {
                val l = lines[i]
                top + item.size * ((p - l.startMs).toFloat() / (l.endMs - l.startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
            } else top + item.size / 2f
            // Where the line is on screen now (layout + shift), and where it should be after this frame.
            val err = y + shift.floatValue - anchor
            val newErr = if (!placed) 0f else {
                val dt = if (last == 0L) 0.016f else (now - last).coerceIn(0, 100) / 1000f
                err * exp(-dt / if (plain) 0.12f else 0.22f)
            }
            // Close enough: settle exactly on the line, so nothing is redrawn until the next line.
            val settled = if (abs(newErr) < 0.25f) 0f else newErr
            var newShift = anchor + settled - y
            val whole = (-newShift).toInt()
            if (whole != 0) newShift += list.dispatchRawDelta(whole.toFloat())
            if (newShift != shift.floatValue) shift.floatValue = newShift
            placed = true
            last = now
        }
    }

    val pad = with(density) { (viewport * look.anchor).toDp() }
    Box(modifier.onSizeChanged { viewport = it.height }) {
        LazyColumn(
            state = list,
            userScrollEnabled = false,
            contentPadding = PaddingValues(top = pad, bottom = with(density) { viewport.toDp() }),
            verticalArrangement = Arrangement.spacedBy(look.lineGap),
            horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                alpha = if (placed) 1f else 0f
                translationY = shift.floatValue
            },
        ) {
            itemsIndexed(lines) { i, line ->
                val dist = if (current < 0) i + 1 else abs(i - current)
                val isCurrent = i == current
                // Brightness falls off with distance from the current line (a natural fade at the edges).
                val targetAlpha = when {
                    center -> when {
                        isCurrent -> 1f
                        i == nextText -> 0.38f
                        else -> 0f
                    }
                    isCurrent -> if (plain) 0.92f else 1f
                    plain -> (0.55f - 0.1f * dist).coerceAtLeast(0.12f)
                    i < current -> (0.3f - 0.06f * dist).coerceAtLeast(0.08f)
                    else -> (0.42f - 0.07f * dist).coerceAtLeast(0.1f)
                }
                val alpha by animateFloatAsState(targetAlpha, tween(if (center) 450 else 500), label = "a")
                val small = if (center) 0.72f else 0.93f
                val scale by animateFloatAsState(if (isCurrent || plain) 1f else small, tween(600, easing = FastOutSlowInEasing), label = "s")
                Box(
                    Modifier.onPlaced {
                        tops[i] = it.positionInParent().y
                        heights[i] = it.size.height.toFloat()
                    }.graphicsLayer {
                        // Fade lines out near the top and bottom edges, so none is ever cut in half.
                        var edge = 1f
                        if (viewport > 0) {
                            val y = tops[i] + shift.floatValue
                            val zone = viewport * 0.16f
                            val topF = ((y + heights[i] * 0.5f) / zone).coerceIn(0f, 1f)
                            val botF = ((viewport - (y + heights[i])) / zone + 0.15f).coerceIn(0f, 1f)
                            edge = topF * topF * (3f - 2f * topF) * botF * botF * (3f - 2f * botF)
                        }
                        this.alpha = alpha * edge
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                        scaleX = scale; scaleY = scale
                        transformOrigin = if (center) TransformOrigin(0.5f, 0.5f) else TransformOrigin(0f, 0.5f)
                    },
                ) {
                    when {
                        line.text.isEmpty() -> if (!center) BreakDots(line, pos, isCurrent, look.size)
                        line.words.isNotEmpty() -> WordLine(line, pos, look.size, center = center, shadow = center, live = isCurrent)
                        else -> LyricText(line.display(), look.size, if (center) TextAlign.Center else TextAlign.Start, shadow = center)
                    }
                }
            }
        }
    }
}

/** For word-synced lines, the words joined exactly as the live line shows them, so nothing re-wraps. */
private fun Lyrics.Line.display(): String = if (words.isEmpty()) text else words.joinToString("") { it.text }.trim()

@Composable
private fun LyricText(text: String, size: TextUnit, align: TextAlign = TextAlign.Start, shadow: Boolean = false) {
    Text(
        // Ravine: lyrics in the serif display face (it reads smaller, hence 1.15×).
        text, fontFamily = Display, fontSize = size * 1.15f, lineHeight = size * 1.3f,
        letterSpacing = (-0.005f * size.value).sp, textAlign = align,
        style = lyricStyle(shadow),
    )
}

/**
 * A word-synced line, drawn word by word. On the current line each word fades from dim to full brightness
 * (with a tiny lift) as it's sung; only the words' layer alpha changes per frame, so nothing is rebuilt.
 * Every line of a word-synced song uses this layout, so a line never re-wraps when it becomes current.
 */
@Composable
private fun WordLine(
    line: Lyrics.Line,
    pos: MutableLongState,
    size: TextUnit,
    center: Boolean = false,
    shadow: Boolean = false,
    live: Boolean = true,
) {
    val words = line.words
    val anims = remember(line) { List(words.size) { Animatable(0f) } }
    if (live) {
        // Runs every time the line becomes current, so a replay or a jump back lights the words up again.
        LaunchedEffect(line) {
            words.forEachIndexed { k, w ->
                launch {
                    val now = pos.longValue
                    when {
                        now >= w.endMs -> anims[k].snapTo(1f)
                        else -> {
                            if (now < w.startMs) anims[k].snapTo(0f)
                            if (w.startMs > now) delay(w.startMs - now)
                            val dur = (w.endMs - w.startMs).coerceIn(120, 450).toInt()
                            anims[k].animateTo(1f, tween(dur, easing = FastOutSlowInEasing))
                        }
                    }
                }
            }
        }
    }
    val style = lyricStyle(shadow)
    FlowRow(horizontalArrangement = if (center) Arrangement.Center else Arrangement.Start) {
        words.forEachIndexed { k, w ->
            Text(
                if (k == words.lastIndex) w.text.trimEnd() else w.text,
                fontFamily = Display, fontSize = size * 1.15f, lineHeight = size * 1.3f,
                letterSpacing = (-0.005f * size.value).sp, style = style,
                modifier = if (!live) Modifier else Modifier.graphicsLayer {
                    val e = anims[k].value
                    alpha = 0.36f + 0.64f * e
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                    translationY = -2.5f * density * 4f * e * (1f - e) // a tiny lift while it lights up
                },
            )
        }
    }
}

/** Veil's text style (Manrope), plus a soft shadow when the lyrics sit on top of the visualizer. */
@Composable
private fun lyricStyle(shadow: Boolean): TextStyle {
    val base = LocalTextStyle.current
    return if (shadow) base.merge(TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.55f), blurRadius = 28f))) else base
}

/** A musical break: three dots that fill in over the gap and breathe gently, like Apple Music. */
@Composable
private fun BreakDots(line: Lyrics.Line, pos: MutableLongState, active: Boolean, size: TextUnit) {
    val d = with(LocalDensity.current) { (size.toPx() * 0.36f).toDp() }
    Row(
        Modifier.padding(vertical = 6.dp).graphicsLayer {
            if (active) {
                val s = 1f + 0.06f * sin(pos.longValue / 260f)
                scaleX = s; scaleY = s
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
        },
        horizontalArrangement = Arrangement.spacedBy(d * 0.7f),
    ) {
        for (k in 0..2) {
            Box(
                Modifier.size(d).graphicsLayer {
                    val f = if (!active) 0f else
                        (((pos.longValue - line.startMs).toFloat() / (line.endMs - line.startMs).coerceAtLeast(1)) * 3f - k).coerceIn(0f, 1f)
                    alpha = 0.35f + 0.65f * f
                }.clip(CircleShape).background(Veil.Text),
            )
        }
    }
}

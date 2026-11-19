package dev.veil.launcher.ui

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Paint
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Home's shading (the dark fades that keep text readable + the artwork-colour glow), pre-rendered once into
 * a single dithered image on the CPU.
 *
 * Why: this TV's GPU is very slow at full-screen gradient shaders (~50 ms a frame for these layers), which
 * made everything lag whenever the backdrop moved. As one image it costs about as much as drawing a photo.
 * The random dithering added while baking also removes colour banding, for free.
 */
@Composable
fun HomeShade(
    bg: Color,
    glow: Color?,
    modifier: Modifier = Modifier,
    /** Ravine Picture depth: an even darkening baked into the same image (one full-screen layer, not two; D46). */
    veil: Float = 0f,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var cur by remember { mutableStateOf<ImageBitmap?>(null) }      // fades + glow, in one image
    var base by remember { mutableStateOf<ImageBitmap?>(null) }     // fades only
    var curGlow by remember { mutableStateOf<ImageBitmap?>(null) }  // glow only (small)
    var prevGlow by remember { mutableStateOf<ImageBitmap?>(null) }
    var blending by remember { mutableStateOf(false) }
    val mix = remember { Animatable(1f) }
    LaunchedEffect(size, bg, glow, veil) {
        if (size.width <= 0) return@LaunchedEffect
        val w = size.width / 2; val h = size.height / 2
        val (nextAll, nextBase, nextGlow) = withContext(Dispatchers.Default) {
            Triple(bake(w, h, bg, glow, veil), bake(w, h, bg, null, veil), glow?.let { bakeGlow(w / 2, h / 2, it) })
        }
        if (nextAll === cur) return@LaunchedEffect
        val first = cur == null
        base = nextBase
        prevGlow = curGlow; curGlow = nextGlow
        cur = nextAll
        if (first) return@LaunchedEffect
        blending = true
        mix.snapTo(0f)
        mix.animateTo(1f, tween(700))
        blending = false
        prevGlow = null
    }
    Box(
        modifier.fillMaxSize().onSizeChanged { size = it }.drawBehind {
            val all = cur ?: return@drawBehind
            val dst = IntSize(this.size.width.toInt(), this.size.height.toInt())
            fun img(i: ImageBitmap, alpha: Float = 1f) = drawImage(
                i, srcOffset = IntOffset.Zero, srcSize = IntSize(i.width, i.height), dstOffset = IntOffset.Zero, dstSize = dst,
                alpha = alpha, filterQuality = FilterQuality.Low,
            )
            val t = mix.value
            val b0 = base
            if (!blending || t >= 1f || b0 == null) { img(all); return@drawBehind }
            // Changing cards: the dark fades stay exactly as they are and only the faint colour glow
            // crossfades (old out, new in). No offscreen copy of the screen is needed, and the bare artwork
            // can never show through.
            img(b0)
            prevGlow?.let { img(it, 1f - t) }
            curGlow?.let { img(it, t) }
        },
    )
}

private val cache = object : LinkedHashMap<String, ImageBitmap>(8, 0.75f, true) {
    override fun removeEldestEntry(e: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 10
}

/** Piecewise-linear gradient lookup. */
private fun ramp(t: Float, stops: FloatArray, values: FloatArray): Float {
    if (t <= stops[0]) return values[0]
    for (i in 1 until stops.size) {
        if (t <= stops[i]) {
            val f = (t - stops[i - 1]) / (stops[i] - stops[i - 1])
            return values[i - 1] + (values[i] - values[i - 1]) * f
        }
    }
    return values.last()
}

private val H_STOPS = floatArrayOf(0f, 0.42f, 0.78f)
private val H_ALPHA = floatArrayOf(0.94f, 0.6f, 0.05f)
// Ravine: the darkening starts a little higher and is denser behind the rows and dock (Veil: 0.55→0.5, 0.8→0.97).
private val V_STOPS = floatArrayOf(0f, 0.22f, 0.5f, 0.76f, 1f)
private val V_ALPHA = floatArrayOf(0.5f, 0f, 0.55f, 0.97f, 1f)
private val G_STOPS = floatArrayOf(0f, 0.55f, 1f)
private val G_ALPHA = floatArrayOf(0.16f, 0.05f, 0f)

/** Just the colour glow (premultiplied, transparent elsewhere), small: it's very soft and gets scaled up. */
private fun bakeGlow(w: Int, h: Int, glow: Color): ImageBitmap {
    val key = "glow $w x $h ${glow.value}"
    synchronized(cache) { cache[key]?.let { return it } }
    val px = IntArray(w * h)
    val rnd = Random(7)
    val cx = w * 0.18f; val cy = h * 1.02f; val radius = w * 0.62f
    val R = (glow.red * 255).toInt(); val G = (glow.green * 255).toInt(); val B = (glow.blue * 255).toInt()
    for (y in 0 until h) {
        val dy = y - cy
        for (x in 0 until w) {
            val dx = x - cx
            val ga = ramp(sqrt(dx * dx + dy * dy) / radius, G_STOPS, G_ALPHA)
            val A = (ga * 255f + rnd.nextFloat() - 0.5f).toInt().coerceIn(0, 255)
            px[y * w + x] = if (A == 0) 0 else (A shl 24) or (R shl 16) or (G shl 8) or B
        }
    }
    val img = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
    img.prepareToDraw()
    synchronized(cache) { cache[key] = img }
    return img
}

private fun bake(w: Int, h: Int, bg: Color, glow: Color?, veil: Float = 0f): ImageBitmap {
    val key = "$w x $h ${bg.value} ${glow?.value} $veil"
    synchronized(cache) { cache[key]?.let { return it } }
    val px = IntArray(w * h)
    val rnd = Random(42)
    val hx = FloatArray(w) { ramp(it / (w - 1f), H_STOPS, H_ALPHA) }
    val cx = w * 0.18f; val cy = h * 1.02f; val radius = w * 0.62f
    val br = bg.red; val bgG = bg.green; val bb = bg.blue
    val gr = glow?.red ?: 0f; val gg = glow?.green ?: 0f; val gb = glow?.blue ?: 0f
    for (y in 0 until h) {
        val va = ramp(y / (h - 1f), V_STOPS, V_ALPHA)
        val dy = y - cy
        for (x in 0 until w) {
            // Two Bg-coloured fades stacked (source-over): alpha = 1 - (1-a)(1-b)
            val a = 1f - (1f - hx[x]) * (1f - va) * (1f - veil)
            var outA = a
            var r = br * a; var g = bgG * a; var b = bb * a // premultiplied
            if (glow != null) {
                val dx = x - cx
                val ga = ramp(sqrt(dx * dx + dy * dy) / radius, G_STOPS, G_ALPHA)
                if (ga > 0f) {
                    r = gr * ga + r * (1f - ga); g = gg * ga + g * (1f - ga); b = gb * ga + b * (1f - ga)
                    outA = ga + outA * (1f - ga)
                }
            }
            // Dither: +-0.5 of an 8-bit step before rounding, so fades come out smooth instead of stepped.
            val n = rnd.nextFloat() - 0.5f
            val A = (outA * 255f + n).toInt().coerceIn(0, 255)
            if (A == 0) { px[y * w + x] = 0; continue }
            val inv = 255f * 255f / A // premultiplied (0..1) -> straight 0..255
            val R = (r * inv + n).toInt().coerceIn(0, 255)
            val G = (g * inv + n).toInt().coerceIn(0, 255)
            val B = (b * inv + n).toInt().coerceIn(0, 255)
            px[y * w + x] = (A shl 24) or (R shl 16) or (G shl 8) or B
        }
    }
    val img = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
    img.prepareToDraw()
    synchronized(cache) { cache[key] = img }
    return img
}

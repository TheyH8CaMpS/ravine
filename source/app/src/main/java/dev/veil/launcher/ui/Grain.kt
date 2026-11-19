package dev.veil.launcher.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.random.Random

/**
 * Ravine's film grain (PROJECT.md §2): a fixed, very faint speckle over the photo, for the vintage /
 * editorial feel. Baked once into a 256 px tile of light and dark specks and repeated across the screen
 * with a tiling shader, so it costs about as much as one flat layer. One grain = one UI pixel (2×2 on the 4K
 * panel), fine enough not to read as noise from the couch.
 */
object FilmGrain {
    private const val TILE = 256
    private const val STRENGTH = 0.085f // max alpha of a speck at "Classic" (the tile is baked once at this level)

    /** Grain levels (PROJECT.md D44): Off, Fine, Light (default), Classic (the original strength). */
    const val OFF = 0
    const val FINE = 1
    const val LIGHT = 2
    const val CLASSIC = 3
    fun name(level: Int) = when (level) { OFF -> "Off"; FINE -> "Fine"; CLASSIC -> "Classic"; else -> "Light" }
    /** Layer alpha for a level: the baked tile drawn more or less transparently. */
    fun alpha(level: Int) = when (level) { OFF -> 0f; FINE -> 0.35f; CLASSIC -> 1f; else -> 0.6f }

    /** Saved level; the old on/off switch maps to Classic/Off. */
    fun saved(context: android.content.Context): Int {
        val p = context.getSharedPreferences("veil", android.content.Context.MODE_PRIVATE)
        return if (p.contains("grain_level")) p.getInt("grain_level", LIGHT)
        else if (!p.getBoolean("film_grain", true)) OFF else LIGHT
    }

    val tile: ImageBitmap by lazy {
        val rnd = Random(1971)
        val px = IntArray(TILE * TILE) {
            // Roughly normal (sum of three uniforms), centred on zero: most specks are almost invisible.
            val g = (rnd.nextFloat() + rnd.nextFloat() + rnd.nextFloat()) / 1.5f - 1f
            val a = (kotlin.math.abs(g) * STRENGTH * 255f).toInt().coerceIn(0, 255)
            val c = if (g > 0) 0xFFFFFF else 0x000000
            (a shl 24) or c
        }
        Bitmap.createBitmap(px, TILE, TILE, Bitmap.Config.ARGB_8888).asImageBitmap().also { it.prepareToDraw() }
    }
}

@Composable
fun GrainLayer(level: Int, modifier: Modifier = Modifier) {
    val a = FilmGrain.alpha(level)
    if (a <= 0f) return
    val brush = ShaderBrush(ImageShader(FilmGrain.tile, TileMode.Repeated, TileMode.Repeated))
    Box(modifier.fillMaxSize().drawBehind { drawRect(brush, alpha = a) })
}

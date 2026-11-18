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
    private const val STRENGTH = 0.085f // max alpha of a speck

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
fun GrainLayer(enabled: Boolean, modifier: Modifier = Modifier) {
    if (!enabled) return
    val brush = ShaderBrush(ImageShader(FilmGrain.tile, TileMode.Repeated, TileMode.Repeated))
    Box(modifier.fillMaxSize().drawBehind { drawRect(brush) })
}

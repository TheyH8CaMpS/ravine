package dev.veil.launcher.ui

import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix

/**
 * Ravine "Picture depth" (PROJECT.md D15). The Shield here shows its interface in HDR / Dolby Vision mode
 * (kept that way on purpose for video), and the TV's SDR-in-HDR mapping lifts dark tones, so photos look
 * milky. Instead of changing the display mode, Home draws the picture deeper: a contrast curve around
 * mid-grey, a little less brightness, and an even darkening under the text shading. All of it is a colour
 * filter on the image plus one flat layer: cheap on the GPU.
 */
object PictureDepth {
    const val STANDARD = 0
    const val RICH = 1
    const val DEEP = 2

    fun name(d: Int) = when (d) { RICH -> "Rich"; DEEP -> "Deep"; else -> "Standard" }

    /** (contrast, brightness) for each level. */
    private fun params(d: Int) = when (d) { RICH -> 1.12f to 0.9f; DEEP -> 1.22f to 0.8f; else -> 1f to 1f }

    fun extraShade(d: Int) = when (d) { RICH -> 0.08f; DEEP -> 0.16f; else -> 0f }

    /** out = (in - 0.5) * contrast + 0.5, then * brightness. Offsets are in 0..255 units. */
    fun filter(d: Int): ColorFilter? {
        if (d == STANDARD) return null
        val (c, b) = params(d)
        val scale = c * b
        val offset = 0.5f * (1f - c) * 255f * b
        return ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    scale, 0f, 0f, 0f, offset,
                    0f, scale, 0f, 0f, offset,
                    0f, 0f, scale, 0f, offset,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
    }
}

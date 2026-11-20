package dev.veil.launcher.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toBitmap

/**
 * App icons for Ravine's dock and drawer (PROJECT.md D50): the app's own mark on a transparent background
 * whenever the icon allows it, never Android's circle. An adaptive icon gives up its background layer and its
 * foreground is shown alone; if that foreground is a full picture, or too dark to see on a dark photo, the
 * whole icon is kept as a softly rounded square. Older (non-adaptive) icons are drawn as they come.
 */
object AppIcons {
    const val SIZE = 160

    fun plate(d: Drawable, size: Int = SIZE): Bitmap {
        if (d !is AdaptiveIconDrawable) return d.toBitmap(size, size)
        val fg = d.foreground
        if (fg != null) {
            val mark = layer(fg, size)
            if (usableAlone(mark)) return mark
        }
        // Background + foreground, rounded square.
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val r = size * 0.22f
        c.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), r, r, Path.Direction.CW) })
        d.background?.let { c.drawBitmap(layer(it, size), 0f, 0f, null) }
        fg?.let { c.drawBitmap(layer(it, size), 0f, 0f, null) }
        return out
    }

    /** One adaptive layer (108 units, 72 of them the visible area) scaled so the visible area fills [size]. */
    private fun layer(l: Drawable, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val full = (size * 1.5f).toInt()
        val off = (size - full) / 2
        l.setBounds(off, off, off + full, off + full)
        l.draw(Canvas(bmp))
        return bmp
    }

    /**
     * A foreground works alone if it has real transparency around the mark (not a full-bleed picture) and the
     * mark is light or colourful enough to read on a dark photo.
     */
    internal fun usableAlone(b: Bitmap): Boolean {
        var opaque = 0; var total = 0; var lum = 0.0
        val step = 4
        for (y in 0 until b.height step step) for (x in 0 until b.width step step) {
            total++
            val p = b.getPixel(x, y)
            if (Color.alpha(p) > 200) {
                opaque++
                lum += (0.2126 * Color.red(p) + 0.7152 * Color.green(p) + 0.0722 * Color.blue(p)) / 255.0
            }
        }
        if (opaque == 0) return false
        val cover = opaque.toDouble() / total
        return cover in 0.04..0.85 && lum / opaque > 0.22
    }
}

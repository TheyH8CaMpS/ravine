package dev.veil.launcher.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL

/**
 * Where the Now Playing cover comes from (ravine#11). Apps often put only a small thumbnail in the session's
 * bitmap (Spotify podcasts), but many also publish a full-size art URI. The URI wins; the bitmap is the fallback.
 * A small cover is never drawn more than 2x its own size, so it's shown smaller instead of blown up.
 * The artwork URI is never logged.
 */
internal object CoverArt {
    /** Covers are loaded and kept at up to this size (as before). */
    const val MAX_PX = 640
    /** Below this, a cover counts as a thumbnail and gets the 2x cap. */
    const val SMALL_PX = 300
    const val MAX_UPSCALE = 2f

    enum class Source { URI, BITMAP, NONE }

    /** Album art URI first, then art, then the display icon; only schemes we can load. */
    fun artUri(md: MediaMetadata): String? = pickUri(
        listOf(
            md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI),
            md.getString(MediaMetadata.METADATA_KEY_ART_URI),
            md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI),
        ),
    )

    fun pickUri(candidates: List<String?>): String? =
        candidates.firstOrNull { u -> u != null && u.isNotBlank() && scheme(u) in LOADABLE }?.trim()

    fun choose(uri: String?, hasBitmap: Boolean): Source = when {
        uri != null -> Source.URI
        hasBitmap -> Source.BITMAP
        else -> Source.NONE
    }

    /**
     * Size in dp to draw a cover the layout wants at [wantDp]. A cover under [SMALL_PX] (by its source size,
     * [sourcePx]; 0 = unknown) is drawn at most 2x its own pixels.
     */
    fun drawDp(wantDp: Float, sourcePx: Int, density: Float): Float {
        if (sourcePx <= 0 || sourcePx >= SMALL_PX || density <= 0f) return wantDp
        return minOf(wantDp, sourcePx * MAX_UPSCALE / density)
    }

    /** Loads the cover from [uri] at up to [max] px. Blocking: call off the main thread. Null on any failure. */
    fun load(context: Context, uri: String, max: Int = MAX_PX): Bitmap? = runCatching {
        val bytes = when (scheme(uri)) {
            "content", "android.resource" -> context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() }
            "https" -> fetch(uri)
            else -> null
        } ?: return null
        decode(bytes, max)
    }.getOrNull()

    private fun fetch(url: String): ByteArray? {
        if (Http.offline) return null
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5_000
            c.readTimeout = 8_000
            c.setRequestProperty("User-Agent", dev.veil.launcher.RavineApp.USER_AGENT)
            if (c.responseCode !in 200..299) return null
            if (c.contentLengthLong > MAX_BYTES) return null
            return c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) return null
                }
                out.toByteArray()
            }
        } finally {
            c.disconnect()
        }
    }

    private fun decode(bytes: ByteArray, max: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= max) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun scheme(u: String) = u.trim().substringBefore(':', "").lowercase()

    private val LOADABLE = setOf("content", "android.resource", "https")
    private const val MAX_BYTES = 8L * 1024 * 1024
}

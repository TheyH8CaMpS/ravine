package dev.veil.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ravine#11: the Now Playing cover prefers the session's art URI and never blows up a small thumbnail. */
class CoverArtTest {
    @Test fun trackChangeUsesThePlaceholderUntilItsOwnArtworkArrives() {
        val cover = TrackArtwork<String>()
        val first = TrackArtworkKey("test.player", "First song", "Artist", "Album", "1")
        val second = first.copy(title = "Second song", mediaId = "2")
        cover.store(first, "first cover")
        assertEquals("first cover", cover.forTrack(first))
        assertNull(cover.forTrack(second))
        cover.store(second, "second cover")
        assertEquals("second cover", cover.forTrack(second))
    }

    @Test fun artUriBeatsBitmap() {
        assertEquals(CoverArt.Source.URI, CoverArt.choose("https://i.scdn.co/image/abc", hasBitmap = true))
        assertEquals(CoverArt.Source.URI, CoverArt.choose("content://com.spotify.music/art/1", hasBitmap = false))
    }

    @Test fun bitmapIsTheFallback() {
        assertEquals(CoverArt.Source.BITMAP, CoverArt.choose(null, hasBitmap = true))
        assertEquals(CoverArt.Source.NONE, CoverArt.choose(null, hasBitmap = false))
    }

    @Test fun uriOrderAndSchemes() {
        // Album art URI, then art URI, then display icon URI.
        assertEquals("content://a/album", CoverArt.pickUri(listOf("content://a/album", "https://b/art", "https://c/icon")))
        assertEquals("https://b/art", CoverArt.pickUri(listOf(null, "https://b/art", "https://c/icon")))
        assertEquals("android.resource://pkg/1", CoverArt.pickUri(listOf(" ", null, "android.resource://pkg/1")))
        // Only what we can load: no plain http, files or unknown schemes.
        assertEquals("https://ok/x", CoverArt.pickUri(listOf("http://insecure/x", "file:///sdcard/x.jpg", "https://ok/x")))
        assertNull(CoverArt.pickUri(listOf("spotify:image:abc", null, "")))
    }

    @Test fun smallBitmapStaysUnderTwiceItsSize() {
        val density = 2f
        // A 96 px thumbnail wanted at 230 dp (460 px) is drawn at 96 dp (192 px = 2x).
        val dp = CoverArt.drawDp(230f, sourcePx = 96, density = density)
        assertEquals(96f, dp, 0.01f)
        assertEquals(true, dp * density <= 96 * CoverArt.MAX_UPSCALE)
        // Small but already within 2x: unchanged.
        assertEquals(92f, CoverArt.drawDp(92f, sourcePx = 120, density = 1f), 0.01f)
    }

    @Test fun largeOrUnknownCoversKeepTheirSize() {
        assertEquals(230f, CoverArt.drawDp(230f, sourcePx = 640, density = 2f), 0.01f)
        assertEquals(230f, CoverArt.drawDp(230f, sourcePx = 300, density = 2f), 0.01f)
        assertEquals(230f, CoverArt.drawDp(230f, sourcePx = 0, density = 2f), 0.01f)
    }
}

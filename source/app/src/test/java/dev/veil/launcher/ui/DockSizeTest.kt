package dev.veil.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ravine#12: dock icons about 15–20% smaller than the original 40 dp, with the item width narrowed to match. */
class DockSizeTest {
    @Test fun iconsAreAbout33dp() {
        assertEquals(33f, DOCK_ICON.value, 0.5f)
        val shrink = 1f - DOCK_ICON.value / 40f
        assertTrue("icons should be 15–20% smaller, were ${shrink * 100}%", shrink in 0.15f..0.20f)
    }

    @Test fun itemWidthNarrowsWithTheIcons() {
        assertEquals(68f, DOCK_ITEM_WIDTH.value, 0.5f)
        // Gap around each icon stays close to before (78 - 40 = 38 dp), so spacing stays even.
        assertEquals(38f, DOCK_ITEM_WIDTH.value - DOCK_ICON.value, 3f)
    }

    @Test fun allAppsGlyphKeepsItsProportion() {
        assertEquals(18f / 40f, DOCK_GLYPH.value / DOCK_ICON.value, 0.02f)
    }
}

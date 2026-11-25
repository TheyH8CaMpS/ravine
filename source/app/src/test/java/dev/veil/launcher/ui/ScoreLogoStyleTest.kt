package dev.veil.launcher.ui

import androidx.compose.ui.graphics.FilterQuality
import coil.size.Precision
import org.junit.Assert.assertEquals
import org.junit.Test

class ScoreLogoStyleTest {
    @Test fun scoresLogosUseExactDecodingAndHighQualityFiltering() {
        assertEquals(Precision.EXACT, ScoreLogoStyle.precision)
        assertEquals(FilterQuality.High, ScoreLogoStyle.filterQuality)
    }

    @Test fun decodeSizeIsTwiceTheDrawnPixelSize() {
        assertEquals(28, ScoreLogoStyle.decodeSize(14))
        assertEquals(52, ScoreLogoStyle.decodeSize(26))
        assertEquals(64, ScoreLogoStyle.decodeSize(32))
    }
}

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
}

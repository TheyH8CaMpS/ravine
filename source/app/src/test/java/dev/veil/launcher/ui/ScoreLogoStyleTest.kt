package dev.veil.launcher.ui

import androidx.compose.ui.graphics.FilterQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreLogoStyleTest {
    @Test fun scoresLogosUseATvSizedDecodeAndHighQualityFiltering() {
        assertTrue("Score logos should request at least 96 px", ScoreLogoStyle.decodeSizePx >= 96)
        assertEquals(FilterQuality.High, ScoreLogoStyle.filterQuality)
    }
}

package dev.veil.launcher.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuPaletteTest {
    @Test fun everyLookUsesReadableAccentColours() {
        for (theme in Themes.all) {
            val palette = menuPalette(theme)
            for (wallpaper in listOf(Color.Black, Color.White)) {
                val sheet = palette.sheet.compositeOver(wallpaper)
                // Classic grain peaks below 9% opacity. Allow for a bright speck on the sheet.
                val brightSheet = Color.White.copy(alpha = 0.09f).compositeOver(sheet)
                val quickAction = Veil.Glass.compositeOver(brightSheet)
                for ((name, fill) in listOf("sheet" to brightSheet, "quick action" to quickAction)) {
                    assertContrast(theme, "$name label", palette.label, fill)
                    assertContrast(theme, "$name value", palette.value, fill)
                    assertTrue(
                        "${theme.name} $name value should be brighter than its label",
                        palette.value.compositeOver(fill).luminance() > palette.label.compositeOver(fill).luminance(),
                    )
                    assertContrast(theme, "$name focused fill", palette.focus, fill, minimum = 3f)
                }
            }
            assertContrast(theme, "focused label", Veil.Ink, palette.focus)
            assertContrast(theme, "focused value", palette.focusValue, palette.focus)
        }
    }

    private fun assertContrast(
        theme: VeilThemeSpec,
        label: String,
        foreground: Color,
        background: Color,
        minimum: Float = 4.5f,
    ) {
        val text = foreground.compositeOver(background).luminance()
        val fill = background.luminance()
        val ratio = (maxOf(text, fill) + 0.05f) / (minOf(text, fill) + 0.05f)
        assertTrue("${theme.name} $label contrast is $ratio (minimum $minimum)", ratio >= minimum)
    }
}
@file:OptIn(ExperimentalTextApi::class)

package dev.veil.launcher.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ProvideTextStyle
import androidx.tv.material3.darkColorScheme
import dev.veil.launcher.R

/** A Veil theme: background tint, accent colour, and the glyph shown on its bubble. */
data class VeilThemeSpec(
    val id: String,
    val name: String,
    val bg: Color,
    val accent: Color,
    val glyph: () -> ImageVector,
)

object Themes {
    val all = listOf(
        // Ravine: forest green with a warm brass accent (the default), canyon bark with terracotta,
        // and Chavez Ravine night blue.
        VeilThemeSpec("ravine", "Ravine", Color(0xFF131A15), Color(0xFFC9A96E)) { VeilIcons.Star },
        VeilThemeSpec("canyon", "Canyon", Color(0xFF1A1512), Color(0xFFD48A5E)) { VeilIcons.Flame },
        VeilThemeSpec("chavez", "Chavez", Color(0xFF0F141C), Color(0xFF6FA3E0)) { VeilIcons.Moon },
        VeilThemeSpec("veil", "Veil", Color(0xFF15171C), Color(0xFFD9FF6B)) { VeilIcons.Lock },
        VeilThemeSpec("aurora", "Aurora", Color(0xFF0E1621), Color(0xFF5EF0C2)) { VeilIcons.Wave },
        VeilThemeSpec("ember", "Ember", Color(0xFF1B1411), Color(0xFFFFA45C)) { VeilIcons.Flame },
        VeilThemeSpec("haze", "Haze", Color(0xFF17131F), Color(0xFFC9B8FF)) { VeilIcons.Moon },
        VeilThemeSpec("glacier", "Glacier", Color(0xFF0F1820), Color(0xFF8FD3FF)) { VeilIcons.Snowflake },
        VeilThemeSpec("bloom", "Bloom", Color(0xFF1D1217), Color(0xFFFF8FB1)) { VeilIcons.Heart },
        VeilThemeSpec("mono", "Mono", Color(0xFF121212), Color(0xFFF4F5F7)) { VeilIcons.HalfCircle },
        VeilThemeSpec("gilded", "Gilded", Color(0xFF0F1116), Color(0xFFF2C94C)) { VeilIcons.Star },
    )
    val default = all.first()
}

/** The active theme. Compose re-reads it, so changing it restyles everything live. */
object ThemeState {
    var current by mutableStateOf(Themes.default)
        private set

    fun load(context: Context) {
        val id = context.getSharedPreferences("veil", Context.MODE_PRIVATE).getString("theme", null)
        current = Themes.all.firstOrNull { it.id == id } ?: Themes.default
    }

    fun set(context: Context, theme: VeilThemeSpec) {
        current = theme
        context.getSharedPreferences("veil", Context.MODE_PRIVATE).edit().putString("theme", theme.id).apply()
    }
}

/**
 * Design tokens. The Streamer draws its UI at 1920x1080 (density 2.0) and scales it to the 4K panel,
 * so 1dp = 2 UI px = 4 panel px. Lines are at least 1dp so nothing turns into a blurry hairline.
 * Bg and Accent follow the active theme.
 */
object Veil {
    val Bg: Color get() = ThemeState.current.bg
    /** The theme's accent, or the focused artwork's colour when "Colours from artwork" is on. */
    val Accent: Color get() = ArtTint.accent ?: ThemeState.current.accent
    /** Warm off-white ("bone") instead of Veil's cool white, to sit with the earthy palette. */
    val Text = Color(0xFFEDE7DA)
    val Ink = Color(0xFF121412)
    /** Menus are frosted glass: slightly see-through over the blurred page behind them. */
    val Sheet: Color get() = lerp(ThemeState.current.bg, Color.White, 0.1f).copy(alpha = 0.93f)
    val Glass = Color.White.copy(alpha = 0.08f)
    val GlassStrong = Color.White.copy(alpha = 0.12f)
    val Line = Color.White.copy(alpha = 0.16f)
}

private fun manrope(weight: Int) = Font(
    R.font.manrope,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Manrope = FontFamily(manrope(400), manrope(500), manrope(600), manrope(700), manrope(800))

/** Ravine's editorial display face (clock, wallpaper titles). One weight; italic for captions. */
val Display = FontFamily(
    Font(R.font.instrument_serif, FontWeight.Normal),
    Font(R.font.instrument_serif_italic, FontWeight.Normal, androidx.compose.ui.text.font.FontStyle.Italic),
)

@Composable
fun VeilTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Veil.Bg,
            surface = Veil.Bg,
            onSurface = Veil.Text,
            onBackground = Veil.Text,
            primary = Veil.Text,
        ),
    ) {
        // Colour comes from LocalContentColor (not the text style), so text inside a focused pill can turn dark.
        CompositionLocalProvider(LocalContentColor provides Veil.Text) {
            ProvideTextStyle(TextStyle(fontFamily = Manrope), content)
        }
    }
}

package dev.veil.launcher.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

internal data class MenuPalette(
    val sheet: Color,
    val label: Color,
    val value: Color,
    val focus: Color,
    val focusValue: Color,
)

internal fun menuPalette(theme: VeilThemeSpec) = MenuPalette(
    sheet = lerp(theme.bg, theme.accent, 0.15f).copy(alpha = 0.99f),
    label = Veil.Text.copy(alpha = 0.84f),
    value = lerp(theme.accent, Color.White, 0.62f),
    focus = theme.accent,
    focusValue = lerp(Veil.Ink, theme.accent, 0.18f),
)
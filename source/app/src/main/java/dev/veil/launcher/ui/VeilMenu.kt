@file:OptIn(ExperimentalTvMaterial3Api::class)

package dev.veil.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.veil.launcher.LauncherState
import dev.veil.launcher.data.Weather
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The "veil" wordmark is a button. While it — or anything in its menu — has focus, the Veil menu drops
 * down under it: start Veil Ambient, pick a theme, change the weather location. Press Down to enter it.
 */
@Composable
fun VeilMenuAnchor(state: LauncherState) {
    var focused by remember { mutableStateOf(false) }
    // Open only after focus has rested here briefly: when Veil comes to the front, focus passes over the
    // wordmark for a few ms before landing on Continue Watching, which would otherwise flash the menu.
    LaunchedEffect(focused) {
        if (focused) { delay(200); if (!state.veilMenuOpen) Sfx.open(); state.veilMenuOpen = true } else state.veilMenuOpen = false
    }
    Box(Modifier.onFocusChanged { focused = it.hasFocus; state.wordmarkFocused = it.hasFocus }) {
        // Styled like the other top-bar pills (soft fill + outline + chevron) so it reads as a button.
        Surface(
            onClick = { /* the menu opens on focus; OK does nothing extra */ },
            // Back closes the menu from the "veil" button too (not only from inside the menu), by sending
            // focus back to the page, exactly like the panel's own Back handling.
            modifier = Modifier.focusSound(1.12f).onPreviewKeyEvent { e ->
                if (e.key == Key.Back && e.type == KeyEventType.KeyUp) { Sfx.back(); state.focusHome(); true }
                else e.key == Key.Back
            },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
            // Ravine: the wordmark floats in the serif face (no pill) and fills in bone when focused.
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent, contentColor = Veil.Text,
                focusedContainerColor = Veil.Text, focusedContentColor = Veil.Ink,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        ) {
            Row(Modifier.padding(start = 12.dp, end = 10.dp, top = 2.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Ravine", fontFamily = Display, fontSize = 24.sp, letterSpacing = (-0.2).sp)
                Spacer(Modifier.width(5.dp))
                Icon(
                    VeilIcons.ChevronDown, contentDescription = "Menu", modifier = Modifier.size(11.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f),
                )
            }
        }
        Box(Modifier.floatingBelow(40.dp)) {
            // Kept built while on Home (it was rebuilt from scratch on every open, which stuttered); while
            // closed it isn't placed on screen, so the remote can never move focus into it.
            KeptPanel(open = state.veilMenuOpen) { VeilPanel(state) }
        }
    }
}

/**
 * Shows [content] when [open], fading and sliding it in/out (no offscreen copy). The content stays composed
 * while closed, so opening is instant; closed, it's measured but not placed, which also keeps it out of
 * D-pad focus search.
 */
@Composable
private fun KeptPanel(open: Boolean, content: @Composable () -> Unit) {
    val progress = androidx.compose.animation.core.animateFloatAsState(
        if (open) 1f else 0f, tween(if (open) 200 else 120), label = "veil-panel",
    )
    val placed by remember { androidx.compose.runtime.derivedStateOf { progress.value > 0f } }
    val show = open || placed
    androidx.compose.ui.layout.Layout(
        content = content,
        modifier = Modifier.graphicsLayer {
            val p = progress.value
            alpha = p
            translationY = -24.dp.toPx() * (1f - p)
            compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints) }
        val w = placeables.maxOfOrNull { it.width } ?: 0
        val h = placeables.maxOfOrNull { it.height } ?: 0
        layout(w, h) { if (show) placeables.forEach { it.place(0, 0) } }
    }
}

/**
 * Ravine's menu, laid out like the index of a field guide (PROJECT.md D21): a serif masthead with today's
 * date, four numbered sections, and one line per setting with its current value in small caps on the right.
 * OK cycles a value or opens the item. Everything about music lives in one row ("With music").
 */
@Composable
private fun VeilPanel(state: LauncherState) {
    val vizButton = remember { FocusRequester() }
    val weatherButton = remember { FocusRequester() }
    val scroll = rememberScrollState()
    LaunchedEffect(state.veilMenuOpen) { if (!state.veilMenuOpen) scroll.scrollTo(0) }
    // After a window opened from this menu closes, put focus back on the row that opened it.
    LaunchedEffect(state.menuReturn) {
        val target = when (state.menuReturn) { "viz" -> vizButton; "weather" -> weatherButton; else -> null }
        if (target != null) {
            delay(80)
            runCatching { target.requestFocus() }
            state.menuReturn = null
        }
    }
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .onPreviewKeyEvent { e ->
                // Back closes the menu by sending focus back to the page.
                if (e.key == Key.Back && e.type == KeyEventType.KeyUp) { Sfx.back(); state.focusHome(); true }
                else e.key == Key.Back
            }
            .width(400.dp).heightIn(max = 470.dp).clip(shape).background(Veil.Sheet).border(1.dp, Veil.Line, shape),
    ) {
        GrainLayer(state.filmGrain, Modifier.matchParentSize())
        Column(
            Modifier.verticalScroll(scroll).padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // Masthead: today's date like the top of a page (the wordmark above already says "Ravine").
            val day = remember(state.minuteTick / 3_600_000) {
                java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault()).format(java.util.Date())
            }
            val date = remember(state.minuteTick / 3_600_000) {
                java.text.SimpleDateFormat("MMMM d", java.util.Locale.getDefault()).format(java.util.Date())
            }
            Text(day, fontFamily = Display, fontSize = 34.sp, lineHeight = 36.sp, letterSpacing = (-0.3).sp)
            Text(
                listOfNotNull(date, state.weatherPlace, state.weather?.let { "${it.tempC}°" }).joinToString("  ·  "),
                fontFamily = Display, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                fontSize = 12.sp, color = Veil.Text.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(10.dp))

            Section("I", "Wallpaper")
            val w = state.wallpaper
            IndexRow("Next photo", w?.title?.takeIf(String::isNotBlank) ?: "…", serifValue = true, onClick = state::nextWallpaper)
            IndexRow("Change photo every", "${state.rotateMinutes} min", onClick = state::cycleRotateMinutes)
            IndexRow("Picture depth", PictureDepth.name(state.pictureDepth), onClick = state::cyclePictureDepth)
            IndexRow("Film grain", if (state.filmGrain) "On" else "Off", onClick = state::toggleFilmGrain)

            Section("II", "Ambient")
            IndexRow("Start Ambient now", arrow = true, onClick = state::startAmbient)
            IndexRow(
                "Starts after", if (state.ambientMinutes == 0) "Never" else "${state.ambientMinutes} min",
                onClick = state::cycleAmbientMinutes,
            )
            IndexRow(
                "With music",
                when (state.musicMode) { "viz" -> "Visualizer"; "art" -> "Album art"; else -> "Lyrics" },
                onClick = state::cycleMusicMode,
            )
            if (state.musicMode == "viz") {
                IndexRow(
                    "Visualizer settings", arrow = true, modifier = Modifier.focusRequester(vizButton),
                    onClick = { state.vizSettingsOpen = true },
                )
            } else if (!state.visualizerAllowed) {
                Note(if (!state.visualizerSupported) "The visualizer isn't supported on this TV." else "The visualizer needs audio access (granted over ADB).")
            }

            Section("III", "Look")
            IndexRow("Theme", ThemeState.current.name, swatch = ThemeState.current.accent, onClick = state::cycleTheme)
            IndexRow("Colours from the photo", if (state.artColors) "On" else "Off", onClick = state::toggleArtColors)

            Section("IV", "Home")
            IndexRow(
                "Weather", state.weatherPlace ?: "Set place", modifier = Modifier.focusRequester(weatherButton),
                onClick = { state.weatherPicker = true },
            )
            IndexRow("Interface sounds", if (state.sounds) "On" else "Off", onClick = state::toggleSounds)
            IndexRow("Seasonal touches", if (state.seasonal) "On" else "Off", onClick = state::toggleSeasonal)
            IndexRow("Ad-blocking pill", if (state.showPrivacy) "Shown" else "Hidden", onClick = state::toggleShowPrivacy)

            Section("V", "Game day")
            dev.veil.launcher.data.MyTeams.all.forEach { t ->
                IndexRow(t.name, if (t.key in state.gameTeams) "On" else "Off", swatch = Color(t.color)) { state.toggleTeam(t.key) }
            }
            IndexRow("Team wallpapers", if (state.teamWallpapers) "On" else "Off", onClick = state::toggleTeamWallpapers)
            IndexRow("Watch games in", state.watchWithLabel, onClick = state::cycleWatchWith)

            Spacer(Modifier.height(10.dp))
            Text(
                "Back to close", fontFamily = Display, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                fontSize = 10.sp, color = Veil.Text.copy(alpha = 0.4f),
            )
        }
    }
}

/** "I  WALLPAPER ————": a roman numeral in the serif, the name in small caps, then a hairline rule. */
@Composable
private fun Section(numeral: String, name: String) {
    Row(Modifier.padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(numeral, fontFamily = Display, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, fontSize = 15.sp, color = Veil.Accent)
        Spacer(Modifier.width(9.dp))
        Text(name.uppercase(), fontSize = 8.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp, color = Veil.Text.copy(alpha = 0.6f))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Veil.Line))
    }
}

@Composable
private fun Note(text: String) = Text(
    text, fontFamily = Display, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, fontSize = 10.sp,
    color = Veil.Text.copy(alpha = 0.5f), modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
)

/**
 * One line of the index: the setting on the left, its value on the right (small caps in the accent colour,
 * or the photo title in serif italics). Focus fills the line in bone with dark text, like the dock.
 */
@Composable
private fun IndexRow(
    label: String,
    value: String? = null,
    modifier: Modifier = Modifier,
    serifValue: Boolean = false,
    arrow: Boolean = false,
    swatch: Color? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = { Sfx.select(); onClick() },
        modifier = modifier.fillMaxWidth().focusSound(1.05f).onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent, contentColor = Veil.Text,
            focusedContainerColor = Veil.Text, focusedContentColor = Veil.Ink,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1)
            if (swatch != null) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(swatch))
                Spacer(Modifier.width(7.dp))
            }
            val valueColor = if (focused) Veil.Ink.copy(alpha = 0.75f) else Veil.Accent
            when {
                value != null && serifValue -> Text(
                    value, fontFamily = Display, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, fontSize = 13.sp,
                    color = if (focused) Veil.Ink.copy(alpha = 0.75f) else Veil.Text.copy(alpha = 0.7f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 190.dp),
                )
                value != null -> Text(
                    value.uppercase(), fontSize = 8.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp,
                    color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 190.dp),
                )
            }
            if (arrow) Text("→", fontSize = 12.sp, color = valueColor)
        }
    }
}

@Composable
fun WeatherPicker(state: LauncherState) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Weather.Place>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    val close = { state.weatherPicker = false; state.menuReturn = "weather" }

    LaunchedEffect(query) {
        if (query.trim().length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(350)
        searching = true
        results = withContext(Dispatchers.IO) { Weather.search(query) }
        searching = false
    }

    Dialog(onDismissRequest = { Sfx.back(); close() }) {
        Column(
            Modifier.width(320.dp).clip(RoundedCornerShape(20.dp)).background(Veil.Sheet)
                .border(1.dp, Veil.Line, RoundedCornerShape(20.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Weather location", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
            Text("Now: ${state.weatherPlace ?: "not set"}", fontSize = 9.5.sp, color = Veil.Text.copy(alpha = 0.6f))
            Spacer(Modifier.height(2.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = Veil.Text, fontSize = 13.sp, fontFamily = Manrope),
                cursorBrush = SolidColor(Veil.Accent),
                modifier = Modifier.fillMaxWidth().focusRequester(field)
                    .clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.08f))
                    .border(1.dp, Veil.Line, RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                decorationBox = { inner ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(VeilIcons.Search, contentDescription = null, modifier = Modifier.size(13.dp), tint = Veil.Text.copy(alpha = 0.6f))
                        Spacer(Modifier.width(8.dp))
                        Box {
                            if (query.isEmpty()) Text("Type or say a city…", fontSize = 13.sp, color = Veil.Text.copy(alpha = 0.45f))
                            inner()
                        }
                    }
                },
            )
            if (searching) Text("Searching…", fontSize = 9.5.sp, color = Veil.Text.copy(alpha = 0.6f))
            results.forEach { p ->
                PickerRow(title = p.name, subtitle = p.region) { state.setWeatherPlace(p); close() }
            }
            if (state.weatherHasOverride && state.weatherDefault.isNotEmpty()) {
                PickerRow(title = "Use default", subtitle = state.weatherDefault) { state.resetWeatherPlace(); close() }
            }
            Text("Back to cancel", fontSize = 8.5.sp, color = Veil.Text.copy(alpha = 0.45f))
        }
    }
    LaunchedEffect(Unit) {
        delay(120)
        runCatching { field.requestFocus() }
    }
}

@Composable
private fun PickerRow(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        onClick = { Sfx.select(); onClick() },
        modifier = Modifier.fillMaxWidth().focusSound(1.12f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent, contentColor = Veil.Text,
            focusedContainerColor = Veil.Text, focusedContentColor = Veil.Ink,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(VeilIcons.Pin2, contentDescription = null, modifier = Modifier.size(12.dp), tint = LocalContentColor.current)
            Spacer(Modifier.width(8.dp))
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.width(6.dp))
            Text(subtitle, fontSize = 9.5.sp, color = dimColor(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

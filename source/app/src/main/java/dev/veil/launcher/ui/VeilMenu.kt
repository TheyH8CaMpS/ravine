@file:OptIn(ExperimentalTvMaterial3Api::class)

package dev.veil.launcher.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import dev.veil.launcher.BuildConfig
import dev.veil.launcher.LauncherState
import dev.veil.launcher.alerts.AlertEngine
import dev.veil.launcher.data.MyTeams
import dev.veil.launcher.data.Updates
import dev.veil.launcher.data.YakRewind
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
    val anchor = remember { FocusRequester() }
    // Owner (D53): the menu opens only on OK, never just because focus passes over the wordmark. Leaving the
    // wordmark and its menu closes it.
    LaunchedEffect(focused) {
        if (!focused && !state.weatherPicker && !state.vizSettingsOpen && state.menuReturn == null) {
            state.veilMenuOpen = false
        }
    }
    Box(Modifier.onFocusChanged { focused = it.hasFocus; state.wordmarkFocused = it.hasFocus }.focusGroup()) {
        // Styled like the other top-bar pills (soft fill + outline + chevron) so it reads as a button.
        Surface(
            // OK opens/closes the menu (D45, D53: only OK opens it); Back closes it.
            onClick = { if (state.veilMenuOpen) { Sfx.back(); state.veilMenuOpen = false } else { Sfx.open(); state.veilMenuOpen = true } },
            // Back closes the menu from the "veil" button too (not only from inside the menu), by sending
            // focus back to the page, exactly like the panel's own Back handling.
            modifier = Modifier.focusRequester(anchor).focusSound(1.12f).onPreviewKeyEvent { e ->
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
                Text("Ravine", style = Type.Display, fontSize = 24.sp, letterSpacing = (-0.2).sp)
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
            KeptPanel(open = state.veilMenuOpen) { VeilPanel(state, anchor) }
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

private enum class MenuSection(val title: String) {
    Wallpaper("Wallpaper"), Ambient("Ambient"), Look("Look"), GameDay("Game day"),
    Weather("Weather"), Visualizer("Visualizer"), Yak("Yak"), Updates("Updates"),
}

/** An overview that fits on one screen, with scrolling reserved for a section's settings. */
@Composable
private fun VeilPanel(state: LauncherState, anchor: FocusRequester) {
    var section by remember { mutableStateOf<MenuSection?>(null) }
    var returnTo by remember { mutableStateOf<MenuSection?>(null) }
    val first = remember { FocusRequester() }
    val sectionBack = remember { FocusRequester() }
    val sectionRows = remember { MenuSection.entries.associateWith { FocusRequester() } }
    val vizButton = remember { FocusRequester() }
    val weatherButton = remember { FocusRequester() }

    LaunchedEffect(state.veilMenuOpen, section, returnTo) {
        if (!state.veilMenuOpen) {
            section = null
            returnTo = null
        } else {
            withFrameNanos { }
            val target = if (section != null) sectionBack else returnTo?.let(sectionRows::get) ?: first
            target.requestFocus()
        }
    }
    // After a window opened from this menu closes, return to its original setting.
    LaunchedEffect(state.menuReturn) {
        val target = when (state.menuReturn) {
            "viz" -> if (section == MenuSection.Visualizer) vizButton else null
            "weather" -> if (section == MenuSection.Weather) weatherButton else null
            else -> null
        }
        if (target != null && state.veilMenuOpen) {
            delay(80)
            target.requestFocus()
        }
        state.menuReturn = null
    }

    fun back() {
        Sfx.back()
        if (section == null) {
            state.veilMenuOpen = false
            state.focusHome()
        } else {
            anchor.requestFocus()
            returnTo = section
            section = null
        }
    }

    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .onPreviewKeyEvent { e ->
                if (e.key != Key.Back) false else {
                    if (e.type == KeyEventType.KeyUp) back()
                    true
                }
            }
            .width(434.dp).heightIn(max = 460.dp).clip(shape)
            .background(Veil.Sheet).border(1.dp, Veil.Line, shape),
    ) {
        GrainLayer(state.walls.grainLevel, Modifier.matchParentSize())
        val page = section
        if (page == null) {
            Column(
                Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val date = remember(state.minuteTick / 3_600_000) {
                    java.text.SimpleDateFormat("EEEE, MMMM d", java.util.Locale.getDefault()).format(java.util.Date())
                }
                Text(date, style = Type.Title, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val place = listOfNotNull(state.weatherPlace, state.weather?.let { "${it.tempC}°" })
                if (place.isNotEmpty()) {
                    Text(place.joinToString(" · "), style = Type.Body, fontSize = 10.sp, color = Veil.Muted, maxLines = 1)
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    QuickAction(
                        "Next photo",
                        state.walls.current?.title?.takeIf(String::isNotBlank)?.let { "Now: $it" },
                        Modifier.weight(1f).focusRequester(first),
                        state.walls::next,
                    )
                    QuickAction("Start Ambient now", null, Modifier.weight(1f), state::startAmbient)
                    QuickAction("Score alerts", AlertEngine.Settings.label(state.alertMode), Modifier.weight(1f), state::cycleAlertMode)
                }
                Spacer(Modifier.height(6.dp))
                MenuSection.entries.forEach { item ->
                    IndexRow(item.title, sectionSummary(item, state), modifier = Modifier.focusRequester(sectionRows.getValue(item)), arrow = true) {
                        // Keep focus in the wordmark/menu group while the old row leaves composition.
                        anchor.requestFocus()
                        section = item
                    }
                }
                MenuFooter("${state.updates.currentLabel}  ·  Back to close")
            }
        } else {
            key(page) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(page.title, style = Type.Title, fontSize = 22.sp)
                    IndexRow("Back to sections", modifier = Modifier.focusRequester(sectionBack), onClick = ::back)
                    Spacer(Modifier.height(4.dp))
                    SectionSettings(page, state, weatherButton, vizButton)
                }
            }
        }
    }
}

private fun onOff(enabled: Boolean) = if (enabled) "On" else "Off"

private fun ambientTime(state: LauncherState) =
    if (state.ambientMinutes == 0) "Never" else "${state.ambientMinutes} min"

private fun musicMode(state: LauncherState) = when (state.musicMode) {
    "viz" -> "Visualizer"
    "art" -> "Album art"
    else -> "Lyrics"
}

private fun updateStatus(state: LauncherState) =
    state.updates.status ?: state.updates.release?.let { "${it.label} ready" }
        ?: "Build ${Updates.currentBuild(BuildConfig.VERSION_CODE)}"

private fun sectionSummary(section: MenuSection, state: LauncherState) = when (section) {
    MenuSection.Wallpaper -> "Every ${state.walls.rotateMinutes} min · ${PictureDepth.name(state.walls.pictureDepth)}"
    MenuSection.Ambient -> "${ambientTime(state)} · ${musicMode(state)}"
    MenuSection.Look -> ThemeState.current.name
    MenuSection.GameDay -> MyTeams.all.filter { it.key in state.gameDay.teams }
        .joinToString(", ") { it.name }.ifEmpty { "No teams selected" }
    MenuSection.Weather -> state.weatherPlace ?: "Set place"
    MenuSection.Visualizer -> when {
        state.musicMode == "viz" -> "On with music"
        state.visualizerAllowed -> "Off"
        else -> "Unavailable"
    }
    MenuSection.Yak -> "Daily ${onOff(state.shows.followYak).lowercase()} · rewind ${state.yakRewind.schedule.label.lowercase()}"
    MenuSection.Updates -> state.updates.release?.let { "Update ready" }
        ?: if (state.updates.automatic) "Automatic" else "Ask"
}

@Composable
private fun SectionSettings(
    section: MenuSection,
    state: LauncherState,
    weatherButton: FocusRequester,
    vizButton: FocusRequester,
) {
    when (section) {
        MenuSection.Wallpaper -> {
            IndexRow("Change photo every", "${state.walls.rotateMinutes} min", onClick = state.walls::cycleRotateMinutes)
            IndexRow("Picture depth", PictureDepth.name(state.walls.pictureDepth), onClick = state.walls::cyclePictureDepth)
            IndexRow("Film grain", FilmGrain.name(state.walls.grainLevel), onClick = state.walls::cycleGrain)
            IndexRow("While music plays", MusicPoster.label(state.musicWallpaper), onClick = state::cycleMusicWallpaper)
        }
        MenuSection.Ambient -> {
            IndexRow("Starts after", ambientTime(state), onClick = state::cycleAmbientMinutes)
            IndexRow("With music", musicMode(state), onClick = state::cycleMusicMode)
        }
        MenuSection.Look -> {
            IndexRow("Theme", ThemeState.current.name, swatch = ThemeState.current.accent, onClick = state::cycleTheme)
            IndexRow("Colours from the photo", onOff(state.artColors), onClick = state::toggleArtColors)
            IndexRow("Netflix button", state.remote.label, onClick = state.remote::cycle)
            if (state.remote.learning) Note("Press the Netflix button on the remote once so Ravine can learn it.")
            else if (state.remote.missed) Note("Ravine didn't see the Netflix button. The Ravine Home-button service must be on (Accessibility).")
            IndexRow("Interface sounds", onOff(state.sounds), onClick = state::toggleSounds)
            IndexRow("Seasonal touches", onOff(state.seasonal), onClick = state::toggleSeasonal)
            IndexRow("Ad-blocking pill", if (state.showPrivacy) "Shown" else "Hidden", onClick = state::toggleShowPrivacy)
        }
        MenuSection.GameDay -> {
            MyTeams.all.forEach { team ->
                IndexRow(team.name, onOff(team.key in state.gameDay.teams), swatch = Color(team.color)) {
                    state.gameDay.toggleTeam(team.key)
                }
            }
            IndexRow("Team wallpapers", onOff(state.gameDay.teamWallpapers), onClick = state.gameDay::toggleTeamWallpapers)
            IndexRow("Team visualizer", onOff(state.gameDay.teamVisualizer), onClick = state.gameDay::toggleTeamVisualizer)
            IndexRow("Watch games in", state.gameDay.watchWithLabel, onClick = state.gameDay::cycleWatchWith)
            if (state.alertMode != AlertEngine.Settings.OFF) {
                IndexRow("Alert delay", if (state.alertDelay == 0) "None" else "${state.alertDelay} s", onClick = state::cycleAlertDelay)
                Note("Alerts wait this long so IPTV catches up first. While ${state.gameDay.watchWithLabel} is open during your team's game, its alerts are skipped.")
            }
            Note(
                if (state.gameDay.courtsideChannels) "Channel names from Courtside, as in TiviMate's guide."
                else "Channel names from ESPN. Courtside can supply your provider's channels (set up over ADB).",
            )
        }
        MenuSection.Weather -> {
            IndexRow("Weather", state.weatherPlace ?: "Set place", modifier = Modifier.focusRequester(weatherButton)) {
                state.weatherPicker = true
            }
        }
        MenuSection.Visualizer -> {
            when {
                state.musicMode == "viz" -> IndexRow(
                    "Visualizer settings", arrow = true, modifier = Modifier.focusRequester(vizButton),
                    onClick = { state.vizSettingsOpen = true },
                )
                !state.visualizerSupported -> Note("The visualizer isn't supported on this TV.")
                !state.visualizerAllowed -> Note("The visualizer needs audio access (granted over ADB).")
                else -> Note("Choose Visualizer under Ambient → With music to open its settings here.")
            }
        }
        MenuSection.Yak -> {
            IndexRow("The Yak daily", onOff(state.shows.followYak), onClick = state.shows::toggleFollowYak)
            IndexRow("Yak rewind", state.yakRewind.schedule.label, onClick = state.yakRewind::cycleSchedule)
            if (state.yakRewind.schedule != YakRewind.Schedule.OFF && !state.yakRewind.configured) {
                Note("Push yak.properties over ADB to connect yak.campsapps.com.")
            }
        }
        MenuSection.Updates -> {
            state.updates.release?.let { release ->
                IndexRow("Update ready", state.updates.status ?: release.label, arrow = true, onClick = state.updates::install)
            }
            IndexRow("Check for updates", updateStatus(state), onClick = state.updates::checkNow)
            IndexRow("Install updates", if (state.updates.automatic) "Automatic" else "Ask", onClick = state.updates::toggleAutomatic)
        }
    }
}

@Composable
private fun MenuFooter(text: String) = Text(
    text, style = Type.Body, fontSize = 10.sp,
    color = Veil.Text.copy(alpha = 0.55f), maxLines = 1,
    modifier = Modifier.padding(top = 6.dp),
)

@Composable
private fun QuickAction(label: String, value: String?, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = { Sfx.select(); onClick() },
        modifier = modifier.focusSound(1.05f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Veil.Glass, contentColor = Veil.Text,
            focusedContainerColor = Veil.Text, focusedContentColor = Veil.Ink,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Column(Modifier.height(46.dp).padding(horizontal = 8.dp), verticalArrangement = Arrangement.Center) {
            Text(label, style = Type.Label, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (value != null) {
                Text(value, style = Type.Value, fontSize = 9.sp, color = LocalContentColor.current.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}


@Composable
private fun Note(text: String) = Text(
    text, style = Type.Body, fontSize = 10.sp,
    color = Veil.Text.copy(alpha = 0.5f), modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
)

/**
 * One line of the index: the setting on the left, its value on the right in sentence case and the muted
 * text colour (ravine#15). Focus fills the line in bone with dark text, like the dock.
 */
@Composable
private fun IndexRow(
    label: String,
    value: String? = null,
    modifier: Modifier = Modifier,
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
            Text(label, style = Type.Label, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1)
            if (swatch != null) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(swatch))
                Spacer(Modifier.width(7.dp))
            }
            val valueColor = if (focused) Veil.Ink.copy(alpha = 0.75f) else Veil.Muted
            if (value != null) {
                Text(
                    value, style = Type.Value, fontSize = 11.sp,
                    color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp),
                )
            }
            if (arrow) {
                Spacer(Modifier.width(8.dp))
                Text("→", fontSize = 12.sp, color = valueColor)
            }
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

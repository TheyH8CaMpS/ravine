package dev.veil.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.veil.launcher.LauncherState
import dev.veil.launcher.data.CourtsideScores
import dev.veil.launcher.data.CourtsideScores.Score
import dev.veil.launcher.data.YouTubeShows
import dev.veil.launcher.state.ScoresState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Scores panel (PROJECT.md D49): Courtside's scoreboard in a sheet from the right, the photo still in view.
// Two levels only: the list (tabs by league) and a game's page; Back always goes up one.

private val Live = Color(0xFFE07A5F)
private val Dim get() = Veil.Text.copy(alpha = 0.55f)
private val Faint get() = Veil.Text.copy(alpha = 0.12f)

internal object ScoreLogoStyle {
    const val decodeSizePx = 128
    val filterQuality = FilterQuality.High
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ScoresPanel(state: LauncherState) {
    val s = state.scores
    BackHandler { Sfx.back(); s.back() }
    // Keep it current while open: scores every 30 s, the open game's page with them.
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            s.refresh()
            if (s.game?.live == true) s.refreshDetail()
        }
    }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.1f), 0.5f to Color.Black.copy(alpha = 0.55f), 1f to Color.Black.copy(alpha = 0.7f)),
            ),
        )
        Box(
            Modifier.align(Alignment.CenterEnd).width(430.dp).fillMaxHeight()
                .background(ThemeState.current.bg.copy(alpha = 0.95f))
                .clearAndSetSemantics { }
                // Keep the remote inside the sheet (Home is still composed underneath).
                .focusProperties { exit = { FocusRequester.Cancel } }.focusGroup(),
        ) {
            Box(Modifier.width(1.dp).fillMaxHeight().background(Veil.Text.copy(alpha = 0.1f)))
            Column(Modifier.fillMaxSize().padding(start = 32.dp, end = 34.dp, top = 28.dp, bottom = 18.dp)) {
                val g = s.game
                if (g == null) ScoreList(state, s) else GamePage(state, s, g)
            }
        }
    }
}

// ---- The list ---------------------------------------------------------------------------------------------

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ScoreList(state: LauncherState, s: ScoresState) {
    val context = LocalContext.current
    val firstRow = remember { FocusRequester() }
    Eyebrow("Courtside")
    Text("Scores", style = Type.Title, fontSize = 18.sp, lineHeight = 24.sp, modifier = Modifier.padding(top = 3.dp))
    Text(
        java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d")) +
            if (s.updating) "  ·  Updating…" else "",
        style = Type.Body, fontSize = 8.sp, color = Dim,
    )
    val tabs = s.tabs
    // Left/Right anywhere in the list moves between tabs; the tabs themselves are labels, not stops.
    fun step(delta: Int) {
        val i = (tabs.indexOf(s.tab) + delta).coerceIn(0, tabs.lastIndex)
        if (tabs[i] != s.tab) { Sfx.move(1.05f); s.selectTab(tabs[i]) }
    }
    Row(Modifier.padding(top = 11.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        tabs.forEach { t -> Tab(if (t == ScoresState.YOURS || t == ScoresState.STANDINGS || t == ScoresState.NOTABLE) t else CourtsideScores.leagueLabel(t), t == s.tab) }
    }
    val rowKeys = Modifier.onPreviewKeyEvent {
        if (it.type != KeyEventType.KeyDown) false
        else when (it.key) { Key.DirectionLeft -> { step(-1); true }; Key.DirectionRight -> { step(1); true }; else -> false }
    }
    if (s.tab == ScoresState.STANDINGS) {
        StandingsView(s, firstRow, rowKeys)
    } else if (s.tab == ScoresState.NOTABLE) {
        NotableView(s, firstRow, rowKeys)
    } else {
        val games = s.gamesFor(s.tab)
        val sections = listOf(
            "Live now" to games.filter { it.live },
            "Later today" to games.filter { !it.live && !it.final }.sortedBy { it.startMs },
            "Final" to games.filter { it.final },
        ).filter { it.second.isNotEmpty() }
        val list = rememberLazyListState()
        LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth()) {
            var first = true
            sections.forEachIndexed { i, (name, gs) ->
                item(key = "h-$name") { Section(name) }
                items(gs, key = { it.id }) { g ->
                    val m = if (first) Modifier.focusRequester(firstRow) else Modifier
                    first = false
                    GameRow(g, context, m.then(rowKeys)) { Sfx.open(); s.openGame(g) }
                }
            }
            if (sections.isEmpty()) item {
                NoteRow(
                    when {
                        s.failed -> "Courtside isn't answering right now. It will try again shortly."
                        s.loading && s.games.isEmpty() -> "Loading…"
                        s.tab == ScoresState.YOURS -> "No Raiders, Dodgers or Lakers games today."
                        else -> "No games today."
                    },
                    Modifier.focusRequester(firstRow).then(rowKeys),
                )
            }
        }
    }
    Hint(if (s.tab == ScoresState.STANDINGS) "▲ ▼ scroll  ·  ◀ ▶ tabs  ·  Back close" else "OK game details  ·  ◀ ▶ tabs  ·  Back close")
    // Focus sits on the first row of the tab (a game, a team, or the "nothing today" line), re-placed whenever
    // the tab or its content changes underneath it.
    val shape = when (s.tab) {
        ScoresState.STANDINGS -> s.standings.size
        ScoresState.NOTABLE -> s.notable.size
        else -> s.gamesFor(s.tab).size
    }
    LaunchedEffect(s.tab, shape) { delay(60); runCatching { firstRow.requestFocus() } }
}

@Composable
private fun Tab(label: String, selected: Boolean) {
    Column(Modifier.padding(horizontal = 7.dp, vertical = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 7.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp, color = if (selected) Veil.Text else Dim)
        Box(Modifier.padding(top = 2.dp).width(14.dp).height(1.dp).background(if (selected) Veil.Accent else Color.Transparent))
    }
}

/** A focusable line of text, so a tab with nothing in it still holds focus (and Left/Right still work). */
@Composable
private fun NoteRow(text: String, modifier: Modifier) {
    Surface(
        onClick = {}, modifier = modifier.fillMaxWidth().padding(top = 8.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(7.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent, contentColor = Veil.Text,
            focusedContainerColor = Veil.Text.copy(alpha = 0.06f), focusedContentColor = Veil.Text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Text(text, style = Type.Body, fontSize = 9.sp, color = Dim, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
    }
}

@Composable
private fun GameRow(g: Score, context: android.content.Context, modifier: Modifier, onClick: () -> Unit) {
    val colors = CourtsideScores.rowColors(g)
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().padding(vertical = 1.dp).focusSound(1f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(7.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent, contentColor = Veil.Text,
            // Only the focused row takes a tint, from the home team's colour (ravine#20).
            focusedContainerColor = Color(colors.focusTint).copy(alpha = 0.06f), focusedContentColor = Veil.Text,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(androidx.compose.foundation.BorderStroke(1.dp, Veil.Accent.copy(alpha = 0.6f)), shape = RoundedCornerShape(7.dp)),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                g.teams.forEachIndexed { i, t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Team colour bar, like the game page's performers (a final's loser at 40%).
                        colors.bars.getOrNull(i)?.let { (c, a) ->
                            Box(Modifier.width(3.dp).height(11.dp).clip(RoundedCornerShape(1.dp)).background(Color(c).copy(alpha = a)))
                            Spacer(Modifier.width(5.dp))
                        }
                        TeamLogo(t, 11.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            t.name, fontSize = 8.5.sp, fontWeight = if (t.result == "loss") FontWeight.Medium else FontWeight.SemiBold,
                            color = if (t.result == "loss") Dim else Veil.Text, maxLines = 1,
                        )
                        if (i == 0 && g.pick != null) Tag(if (g.live && closeGame(g.pick)) "Close" else "Worth watching")
                    }
                }
            }
            if (g.state != "pre") {
                Column(Modifier.width(30.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    g.teams.forEachIndexed { i, t ->
                        Text(
                            t.score, fontSize = 9.sp, fontWeight = if (t.result == "loss") FontWeight.Medium else FontWeight.Bold,
                            color = colors.scores.getOrNull(i)?.let { Color(it) } ?: if (t.result == "loss") Dim else Veil.Text, lineHeight = 13.sp,
                        )
                    }
                }
            }
            Column(Modifier.width(92.dp), horizontalAlignment = Alignment.End) {
                Text(
                    statusText(context, g), fontSize = 7.sp, fontWeight = FontWeight.Bold,
                    color = if (g.live) Live else Veil.Text, maxLines = 1,
                )
                (g.channel ?: if (g.final) null else null)?.let {
                    Text(it, fontSize = 6.5.sp, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Courtside's live "One-score game" reason (game-interest.ts), shown as the short CLOSE tag. */
private fun closeGame(pick: String) = pick.contains("one-score", true) || pick.contains("close", true)

private fun statusText(context: android.content.Context, g: Score): String = when (g.state) {
    "pre" -> if (g.startMs > 0) android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(g.startMs)) else g.status
    else -> g.status
}

@Composable
private fun Tag(text: String) {
    Text(
        text, style = Type.Label, fontSize = 6.sp, color = Veil.Accent,
        modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(2.dp))
            .background(Veil.Accent.copy(alpha = 0.12f)).padding(horizontal = 3.dp, vertical = 1.dp),
    )
}

@Composable
private fun TeamLogo(t: CourtsideScores.Team, size: Dp) {
    val logo = t.logo
    if (logo != null) {
        val context = LocalContext.current
        val request = remember(context, logo) {
            ImageRequest.Builder(context).data(logo).size(ScoreLogoStyle.decodeSizePx).build()
        }
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            filterQuality = ScoreLogoStyle.filterQuality,
            modifier = Modifier.size(size),
        )
    } else {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) { Box(Modifier.size(size / 2).clip(CircleShape).background(Color(t.color))) }
    }
}

// ---- Standings ----------------------------------------------------------------------------------------------

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.StandingsView(s: ScoresState, firstRow: FocusRequester, rowKeys: Modifier) {
    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
        if (s.standings.isEmpty()) item { NoteRow("Loading standings…", Modifier.focusRequester(firstRow).then(rowKeys)) }
        var first = true
        s.standings.forEach { (league, st) ->
            item(key = "l-$league") {
                Text(
                    CourtsideScores.leagueLabel(league), style = Type.Title, fontSize = 12.sp,
                    modifier = Modifier.padding(top = if (first) 6.dp else 16.dp),
                )
            }
            st.groups.forEachIndexed { gi, group ->
                item(key = "$league-g$gi") { Section(group.name) }
                items(group.rows.size, key = { "$league-g$gi-$it" }) { ri ->
                    val r = group.rows[ri]
                    val m = if (first) Modifier.focusRequester(firstRow) else Modifier
                    first = false
                    StandingRow(ri + 1, r, m.then(rowKeys))
                }
            }
        }
    }
}

// ---- Notable lines (ravine#22) ----------------------------------------------------------------------------

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.NotableView(s: ScoresState, firstRow: FocusRequester, rowKeys: Modifier) {
    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
        item(key = "h") { Section(s.notableLabel) }
        items(s.notable.size, key = { "n-$it" }) { i ->
            val n = s.notable[i]
            val m = if (i == 0) Modifier.focusRequester(firstRow) else Modifier
            val logoTeam = s.games.firstOrNull { it.feed == n.feed && it.eventId == n.eventId }
                ?.teams?.firstOrNull { it.abbr.equals(n.team, ignoreCase = true) && it.logo != null }
            NotableRow(n, logoTeam, m.then(rowKeys)) { Sfx.open(); s.openNotable(n) }
        }
    }
}

/** Label, player and team, then the stat line as the focus; a thin team-colour bar, and a soft wash when focused. */
@Composable
private fun NotableRow(n: CourtsideScores.Notable, logoTeam: CourtsideScores.Team?, modifier: Modifier, onClick: () -> Unit) {
    val team = Color(n.teamColor)
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().padding(vertical = 1.dp).focusSound(1f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(7.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent, contentColor = Veil.Text,
            focusedContainerColor = team.copy(alpha = 0.08f), focusedContentColor = Veil.Text,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(androidx.compose.foundation.BorderStroke(1.dp, Veil.Accent.copy(alpha = 0.6f)), shape = RoundedCornerShape(7.dp)),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.height(IntrinsicSize.Min).padding(horizontal = 8.dp, vertical = 5.dp)) {
            Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(1.dp)).background(team))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    n.label, style = Type.Label, fontSize = 6.5.sp, maxLines = 1,
                    color = when (n.type) { "rough" -> Veil.Text.copy(alpha = 0.7f); "mixed" -> Dim; else -> Veil.Accent },
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(n.name, fontSize = 8.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    if (logoTeam != null) {
                        Spacer(Modifier.width(5.dp))
                        TeamLogo(logoTeam, 8.dp)
                    }
                    Text("  ${n.team}", fontSize = 7.sp, color = Dim, maxLines = 1)
                }
                Text(n.statLine, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                listOfNotNull(n.context, if (n.onRoster) "On your roster" else null).joinToString("  ·  ").takeIf { it.isNotEmpty() }?.let {
                    Text(it, fontSize = 7.sp, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun StandingRow(place: Int, r: CourtsideScores.StandingRow, modifier: Modifier) {
    Surface(
        onClick = {},
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(5.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent, contentColor = Veil.Text,
            focusedContainerColor = Veil.Text.copy(alpha = 0.09f), focusedContentColor = Veil.Text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$place", style = Type.Value, fontSize = 8.sp, color = Dim, modifier = Modifier.width(16.dp))
            Text(r.name, fontSize = 8.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
            Text(r.record, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(50.dp), textAlign = TextAlign.End)
            Text(r.points?.let { "$it pts" } ?: r.gamesBack?.let { if (it == "-" || it == "0") "—" else "$it GB" } ?: "",
                fontSize = 7.sp, color = Dim, modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
            Text(r.streak ?: "", fontSize = 7.sp, color = Dim, modifier = Modifier.width(30.dp), textAlign = TextAlign.End)
        }
    }
}

// ---- A game's page ----------------------------------------------------------------------------------------

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.GamePage(state: LauncherState, s: ScoresState, g: Score) {
    val context = LocalContext.current
    val watch = remember { FocusRequester() }
    val firstHighlight = remember { FocusRequester() }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val stepPx = with(LocalDensity.current) { 90.dp.toPx() }
    val detail = s.detail
    val highlights = if (g.final) detail?.highlights.orEmpty() else emptyList()
    fun scrollAtEnd(event: KeyEvent, atTop: Boolean, atBottom: Boolean): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val distance = when (event.key) {
            Key.DirectionDown -> if (atBottom) stepPx else return false
            Key.DirectionUp -> if (atTop) -stepPx else return false
            else -> return false
        }
        scope.launch { scroll.animateScrollBy(distance) }
        return true
    }
    val (away, home) = g.teams.getOrNull(0) to g.teams.getOrNull(1)
    Eyebrow(CourtsideScores.leagueLabel(g.league))
    Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        away?.let { TeamLogo(it, 16.dp); Spacer(Modifier.width(6.dp)); Text(it.name, style = Type.Title, fontSize = 16.sp) }
        if (g.state != "pre") {
            Text("  ${away?.score ?: ""}", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("  –  ", fontSize = 13.sp, color = Dim)
            Text("${home?.score ?: ""}  ", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        } else Text("  at  ", style = Type.Body, fontSize = 13.sp, color = Dim)
        home?.let { Text(it.name, style = Type.Title, fontSize = 16.sp); Spacer(Modifier.width(6.dp)); TeamLogo(it, 16.dp) }
    }
    Row(Modifier.padding(top = 3.dp)) {
        Text(statusText(context, g), fontSize = 7.5.sp, fontWeight = FontWeight.Bold, color = if (g.live) Live else Veil.Text)
        g.situation?.let { Text("  ·  $it", fontSize = 7.5.sp, color = Veil.Text.copy(alpha = 0.85f)) }
    }
    // Why Courtside picked it (Worth watching), in its own words.
    g.pick?.let { Text(it, fontSize = 7.sp, color = Veil.Accent.copy(alpha = 0.9f), modifier = Modifier.padding(top = 2.dp)) }
    // Down reaches the highlight row when available, or scrolls the details.
    GameAction(
        onClick = { Sfx.select(); s.close(); state.gameDay.openWatchApp() },
        modifier = Modifier.padding(top = 9.dp).focusRequester(watch)
            .focusProperties { if (highlights.isNotEmpty()) down = firstHighlight }
            .onPreviewKeyEvent { scrollAtEnd(it, atTop = true, atBottom = highlights.isEmpty()) },
    ) {
        Text("▶  Watch in ${state.gameDay.watchWithLabel}", fontSize = 8.sp, fontWeight = FontWeight.Bold)
        g.channel?.let { Text("   $it", fontSize = 7.sp, modifier = Modifier.graphicsAlpha(0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
    LaunchedEffect(g.id) { delay(60); runCatching { watch.requestFocus() } }

    val d = detail
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
        if (d == null) { Note("Loading…"); return@Column }
        if (d.compare.isNotEmpty()) {
            Section("At a glance")
            d.compare.forEach { c -> CompareRow(c, g.team(d.teamIds.getOrNull(0)) ?: away, g.team(d.teamIds.getOrNull(1)) ?: home) }
        }
        if (d.performers.isNotEmpty()) {
            Section(if (g.state == "pre") "Season leaders" else "Top performers")
            val ids = (d.teamIds.filter { it.isNotBlank() }.ifEmpty { g.teams.map { it.id } })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ids.take(2).forEach { id ->
                    val t = g.team(id)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        t?.let { TeamHeader(it) }
                        d.performers.filter { it.teamId == id }.forEach { p -> Performer(p, t) }
                    }
                }
            }
        }
        if (highlights.isNotEmpty()) {
            Section("Highlights")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                highlights.forEachIndexed { index, highlight ->
                    val anchor = if (index == 0) Modifier.focusRequester(firstHighlight) else Modifier
                    HighlightCard(
                        highlight,
                        anchor.focusProperties { up = watch }
                            .onPreviewKeyEvent { scrollAtEnd(it, atTop = false, atBottom = true) },
                    ) {
                        Sfx.select()
                        if (!YouTubeShows.openVideo(context, highlight.videoId)) state.showToast("No app could open this highlight.")
                    }
                }
            }
        }
        if (d.moments.isNotEmpty()) {
            Section("The moments that mattered")
            d.moments.forEach { m -> MomentRow(m, g) }
        }
        if (d.compare.isEmpty() && d.performers.isEmpty() && d.moments.isEmpty() && highlights.isEmpty()) {
            Note("Details appear once the game starts.")
        }
        Spacer(Modifier.height(10.dp))
    }
    Hint(if (highlights.isEmpty()) "Back scores  ·  ▲ ▼ scroll" else "Back scores  ·  ◀ ▶ highlights  ·  ▲ ▼ move or scroll")
}

@Composable
private fun HighlightCard(highlight: CourtsideScores.Highlight, modifier: Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    val thumbnail = remember(context, highlight.videoId) {
        highlight.thumbnailUrl()?.let { ImageRequest.Builder(context).data(it).size(320, 180).build() }
    }
    val shape = RoundedCornerShape(7.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.width(114.dp).focusSound(),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Faint, contentColor = Veil.Text,
            focusedContainerColor = Faint, focusedContentColor = Veil.Text,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(androidx.compose.foundation.BorderStroke(1.dp, Veil.Accent), shape = shape),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
    ) {
        Column(Modifier.padding(4.dp)) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(4.dp)).background(Faint),
                contentAlignment = Alignment.Center,
            ) {
                Text("▶", fontSize = 15.sp, color = Dim, modifier = Modifier.clearAndSetSemantics { })
                if (thumbnail != null) {
                    AsyncImage(
                        model = thumbnail, contentDescription = null, modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.Crop, filterQuality = FilterQuality.High,
                    )
                }
            }
            Text(
                highlight.title, modifier = Modifier.padding(top = 4.dp), style = Type.Body,
                fontSize = 8.sp, lineHeight = 10.sp, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun GameAction(modifier: Modifier, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Veil.Text.copy(alpha = 0.12f), contentColor = Veil.Text,
            focusedContainerColor = Veil.Text, focusedContentColor = Veil.Ink,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

private fun Modifier.graphicsAlpha(a: Float) = this.graphicsLayer { alpha = a }

@Composable
private fun CompareRow(c: CourtsideScores.Compare, a: CourtsideScores.Team?, b: CourtsideScores.Team?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(c.values.getOrElse(0) { "—" }, fontSize = 8.sp, fontWeight = if (c.winner == "away") FontWeight.Bold else FontWeight.Medium,
            color = if (c.winner == "home") Dim else Veil.Text, modifier = Modifier.width(34.dp), textAlign = TextAlign.End)
        Bar(c.bars?.get(0), a, Modifier.weight(1f).padding(start = 6.dp), fromEnd = true)
        Text(c.label, style = Type.Label, fontSize = 6.5.sp, color = Dim, modifier = Modifier.width(90.dp), textAlign = TextAlign.Center, maxLines = 1)
        Bar(c.bars?.get(1), b, Modifier.weight(1f).padding(end = 6.dp), fromEnd = false)
        Text(c.values.getOrElse(1) { "—" }, fontSize = 8.sp, fontWeight = if (c.winner == "home") FontWeight.Bold else FontWeight.Medium,
            color = if (c.winner == "away") Dim else Veil.Text, modifier = Modifier.width(34.dp))
    }
}

@Composable
private fun Bar(percent: Int?, t: CourtsideScores.Team?, modifier: Modifier, fromEnd: Boolean) {
    Box(modifier.height(2.5.dp).clip(RoundedCornerShape(2.dp)).background(Faint), contentAlignment = if (fromEnd) Alignment.CenterEnd else Alignment.CenterStart) {
        if (percent != null && percent > 0) {
            Box(Modifier.fillMaxWidth(percent / 100f).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(Color(t?.color ?: 0xFFD8D8DC)))
        }
    }
}

/** A team's column header: its logo and name in the team's colour. */
@Composable
private fun TeamHeader(t: CourtsideScores.Team) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TeamLogo(t, 10.dp)
        Spacer(Modifier.width(5.dp))
        Text(t.name, style = Type.Label, fontSize = 7.sp, color = Color(t.color))
    }
}

/** One leader, marked with a short rule in the team's colour (owner: tell the teams apart at a glance, quietly). */
@Composable
private fun Performer(p: CourtsideScores.Performer, t: CourtsideScores.Team?) {
    Row(Modifier.heightIn(min = 18.dp)) {
        Box(Modifier.padding(top = 2.dp).width(1.5.dp).height(16.dp).background(Color(t?.color ?: 0xFFD8D8DC).copy(alpha = 0.85f)))
        Spacer(Modifier.width(6.dp))
        Column {
            Text(p.label, style = Type.Label, fontSize = 6.5.sp, color = Dim)
            Text("${p.athlete}  ·  ${p.line}", fontSize = 7.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MomentRow(m: CourtsideScores.Moment, g: Score) {
    val t = g.team(m.teamId)
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(1.5.dp).height(14.dp).background(t?.let { Color(it.color).copy(alpha = 0.85f) } ?: Faint))
        Spacer(Modifier.width(6.dp))
        t?.let { TeamLogo(it, 9.dp); Spacer(Modifier.width(5.dp)) }
        Text(
            "${CourtsideScores.periodLabel(g.feed, m.period)} ${m.clock}".trim(), style = Type.Value,
            fontSize = 8.sp, color = Dim, modifier = Modifier.width(44.dp), maxLines = 1,
        )
        Text(m.text, fontSize = 7.5.sp, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 10.sp)
        m.score?.let { Text(it, fontSize = 7.5.sp, fontWeight = FontWeight.Bold, color = Veil.Text.copy(alpha = 0.8f), modifier = Modifier.padding(start = 8.dp)) }
    }
}

// ---- Shared bits --------------------------------------------------------------------------------------------

@Composable
private fun Eyebrow(text: String) =
    Text(text, style = Type.Label, fontSize = 7.sp, color = Veil.Accent)

/** "Live now ————": the menu's section style (sentence case, no numerals; ravine#15). */
@Composable
private fun Section(name: String) {
    Row(Modifier.fillMaxWidth().padding(top = 11.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = Type.Label, fontSize = 7.sp, color = Dim)
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f).height(0.5.dp).background(Faint))
    }
}

@Composable
private fun Note(text: String) =
    Text(text, style = Type.Body, fontSize = 9.sp, color = Dim, modifier = Modifier.padding(top = 14.dp))

@Composable
private fun Hint(text: String) =
    Text(text, fontSize = 6.5.sp, color = Dim, modifier = Modifier.padding(top = 8.dp))

/** Top bar entry: "Scores", with a quiet live dot while one of your teams is playing. */
@Composable
fun ScoresButton(state: LauncherState) {
    val focus = remember { FocusRequester() }
    val liveFavorite = state.gameDay.game?.state == dev.veil.launcher.data.Game.State.LIVE
    LaunchedEffect(state.scores.closedTick) { if (state.scores.closedTick > 0) { delay(80); runCatching { focus.requestFocus() } } }
    Surface(
        onClick = { Sfx.open(); state.scores.show() },
        modifier = Modifier.focusRequester(focus).focusSound(1.12f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent, contentColor = Veil.Text,
            focusedContainerColor = Veil.Text, focusedContentColor = Veil.Ink,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Row(Modifier.padding(horizontal = 11.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (liveFavorite) { Box(Modifier.size(5.dp).clip(CircleShape).background(Live)); Spacer(Modifier.width(6.dp)) }
            Text("Scores", style = Type.Label, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}


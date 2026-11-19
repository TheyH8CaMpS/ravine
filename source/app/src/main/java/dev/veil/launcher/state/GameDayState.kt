package dev.veil.launcher.state

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.veil.launcher.data.AppEntry
import dev.veil.launcher.data.CourtsideGuide
import dev.veil.launcher.data.Game
import dev.veil.launcher.data.GameDay
import dev.veil.launcher.viz.VizPresets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Game day on Home (PROJECT.md D27–D31, D35): the strip's game, the teams, where games are watched. */
class GameDayState(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: SharedPreferences,
    /** Installed apps and the dock, for "Watch games in". */
    private val apps: () -> List<AppEntry>,
    private val pinnedApps: () -> List<AppEntry>,
    private val start: (Intent) -> Boolean,
) {
    /** The game on the strip: next start today, live score, or a recent final. */
    var game by mutableStateOf<Game?>(GameDay.current)
        private set
    private var busy = false

    fun tick() {
        if (busy || teams.isEmpty()) { if (teams.isEmpty()) game = null; return }
        busy = true
        scope.launch {
            try {
                game = withContext(Dispatchers.IO) { runCatching { GameDay.tick(context) }.getOrNull() }
            } finally { busy = false }
        }
    }

    var teams by mutableStateOf(GameDay.enabledTeams(context))
        private set

    fun toggleTeam(key: String) {
        teams = if (key in teams) teams - key else teams + key
        prefs.edit().putStringSet("game_teams", teams).apply()
        tick()
    }

    /** Courtside is set up for provider channel names (D30). */
    val courtsideChannels: Boolean get() = runCatching { CourtsideGuide.configured(context) }.getOrDefault(false)

    var teamWallpapers by mutableStateOf(prefs.getBoolean("team_wallpapers", true))
        private set

    fun toggleTeamWallpapers() {
        teamWallpapers = !teamWallpapers
        prefs.edit().putBoolean("team_wallpapers", teamWallpapers).apply()
    }

    /** The team's photo collection while its game is on (if team wallpapers are on). */
    fun preferredCollection(now: Long = System.currentTimeMillis()): String? =
        game?.takeIf { teamWallpapers && it.inWindow(now) }?.team?.collection

    /** The team's visualizer colours while its game is on (D35). */
    var teamVisualizer by mutableStateOf(prefs.getBoolean("team_visualizer", true).also { VizPresets.teamVisualizer = it })
        private set

    fun toggleTeamVisualizer() {
        teamVisualizer = !teamVisualizer
        VizPresets.teamVisualizer = teamVisualizer
        prefs.edit().putBoolean("team_visualizer", teamVisualizer).apply()
    }

    /** The app the strip opens (default TiviMate). */
    var watchWith by mutableStateOf(prefs.getString("watch_with", "ar.tvplayer.tv") ?: "ar.tvplayer.tv")
        private set

    val watchWithLabel: String get() = apps().firstOrNull { it.pkg == watchWith }?.label ?: "TiviMate"

    fun cycleWatchWith() {
        val choices = pinnedApps().map { it.pkg }.ifEmpty { listOf(watchWith) }
        watchWith = choices[(choices.indexOf(watchWith) + 1).mod(choices.size)]
        prefs.edit().putString("watch_with", watchWith).apply()
    }

    /** OK on the strip: always the "Watch games in" app, never Courtside (D29). */
    fun openGame() {
        if (game == null) return
        openWatchApp()
    }

    /** The "Watch games in" app (the Scores panel's Watch button too). */
    fun openWatchApp() {
        val pm = context.packageManager
        (pm.getLeanbackLaunchIntentForPackage(watchWith) ?: pm.getLaunchIntentForPackage(watchWith))
            ?.let { start(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

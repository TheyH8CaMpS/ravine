package dev.veil.launcher.alerts

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import dev.veil.launcher.data.CourtsideGuide
import dev.veil.launcher.data.CourtsideScores
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Score alerts (PROJECT.md D52), run by Ravine's always-on accessibility service so they work over any app.
 *
 * Spoiler-safe by design: IPTV runs up to a minute or more behind the real game, so every alert waits
 * [Settings.delaySeconds] (90 s by default) before it shows; and while your "Watch games in" app (TiviMate) is
 * on screen during your team's game, that game's alerts are dropped: you're probably watching it.
 * At most one alert per game every 3 minutes: a newer moment replaces one still waiting.
 */
class AlertEngine(
    private val context: Context,
    /** The app on screen now (from the accessibility service). */
    private val foreground: () -> String?,
    private val show: (ScoreAlerts.Alert) -> Unit,
) {
    object Settings {
        const val OFF = "off"
        const val TEAMS = "teams"
        const val TEAMS_BIG = "teams_big"
        val DELAYS = listOf(0, 30, 60, 90, 120, 180)

        fun prefs(c: Context) = c.getSharedPreferences("ravine_alerts", Context.MODE_PRIVATE)
        fun mode(c: Context) = prefs(c).getString("mode", TEAMS_BIG) ?: TEAMS_BIG
        fun delaySeconds(c: Context) = prefs(c).getInt("delay", 90)
        fun label(mode: String) = when (mode) { TEAMS -> "Your teams"; TEAMS_BIG -> "Your teams + big games"; else -> "Off" }
    }

    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ravine-alerts").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private var last: Map<String, CourtsideScores.Score> = emptyMap()
    private val shown = HashSet<String>()
    private val pending = PendingScoreAlerts()
    private val modeChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "mode" && Settings.mode(context) == Settings.OFF) pending.clear()
    }
    private val lastShownAt = HashMap<String, Long>()
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        Settings.prefs(context).registerOnSharedPreferenceChangeListener(modeChanged)
        worker.execute { poll() }
    }

    fun stop() {
        running = false
        Settings.prefs(context).unregisterOnSharedPreferenceChangeListener(modeChanged)
        pending.clear()
        worker.shutdownNow()
    }

    private fun poll() {
        if (!running) return
        var next = 10 * 60L
        val generation = pending.generation
        runCatching {
            val mode = Settings.mode(context)
            if (mode != Settings.OFF && CourtsideGuide.configured(context)) {
                val zone = "America/Los_Angeles"
                val day = java.time.LocalDate.now(java.time.ZoneId.of(zone))
                val text = CourtsideGuide.get(context, "/api/tv/scores?date=$day&zone=$zone")
                val cur = text?.let { CourtsideScores.parseScores(it) }
                if (cur != null) {
                    val now = System.currentTimeMillis()
                    val found = ScoreAlerts.detect(last, cur, bigGames = mode == Settings.TEAMS_BIG)
                    last = cur.associateBy { it.id }
                    val due = now + Settings.delaySeconds(context) * 1000L
                    pending.enqueue(generation, due, found.filter { shown.add(it.key) })
                    // Every minute while anything is live or about to start; otherwise every 10.
                    val soon = cur.any { it.live || (it.state == "pre" && it.startMs - now in 0..30 * 60_000L) }
                    if (soon || pending.nextDueAt() != null) next = 60L
                }
            }
            if (shown.size > 2_000) shown.clear()
        }
        flush(generation)
        if (running) worker.schedule({ poll() }, minOf(next, nextDueSeconds()), TimeUnit.SECONDS)
    }

    private fun nextDueSeconds(): Long =
        pending.nextDueAt()?.let { ((it - System.currentTimeMillis()) / 1000).coerceIn(1, 600) } ?: 600

    /** Shows alerts whose delay has passed (dropping your team's while you're watching in TiviMate). */
    private fun flush(generation: Long) {
        val now = System.currentTimeMillis()
        val watchApp = context.getSharedPreferences("veil", Context.MODE_PRIVATE).getString("watch_with", "ar.tvplayer.tv")
        pending.takeDue(now).forEach { a ->
            val gameId = a.gameId
            if (a.favorite && foreground() == watchApp) return@forEach
            if (now - (lastShownAt[gameId] ?: 0L) < 3 * 60_000L) return@forEach
            lastShownAt[gameId] = now
            main.post {
                if (pending.canDeliver(generation, Settings.mode(context) != Settings.OFF)) runCatching { show(a) }
            }
        }
    }
}

/** A disable invalidates polls in flight and callbacks already posted to the main thread. */
internal class PendingScoreAlerts {
    private val byGame = HashMap<String, Pair<Long, ScoreAlerts.Alert>>()
    @Volatile var generation = 0L
        private set

    @Synchronized fun clear() {
        generation++
        byGame.clear()
    }

    @Synchronized fun enqueue(pollGeneration: Long, due: Long, alerts: List<ScoreAlerts.Alert>) {
        if (pollGeneration == generation) alerts.forEach { byGame[it.gameId] = due to it }
    }

    @Synchronized fun nextDueAt(): Long? = byGame.values.minOfOrNull { it.first }

    @Synchronized fun takeDue(now: Long): List<ScoreAlerts.Alert> {
        val ready = byGame.values.filter { it.first <= now }.map { it.second }
        ready.forEach { byGame.remove(it.gameId) }
        return ready
    }

    fun canDeliver(pollGeneration: Long, enabled: Boolean) = enabled && pollGeneration == generation
}

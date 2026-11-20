package dev.veil.launcher.alerts

import android.content.Context
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
    /** Waiting alerts by game: (due at, alert). */
    private val pending = HashMap<String, Pair<Long, ScoreAlerts.Alert>>()
    private val lastShownAt = HashMap<String, Long>()
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        worker.execute { poll() }
    }

    fun stop() {
        running = false
        worker.shutdownNow()
    }

    private fun poll() {
        if (!running) return
        var next = 10 * 60L
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
                    synchronized(pending) {
                        found.filter { it.key !in shown }.forEach { a -> shown += a.key; pending[a.gameId] = due to a }
                    }
                    // Every minute while anything is live or about to start; otherwise every 10.
                    val soon = cur.any { it.live || (it.state == "pre" && it.startMs - now in 0..30 * 60_000L) }
                    if (soon || synchronized(pending) { pending.isNotEmpty() }) next = 60L
                }
            }
            if (shown.size > 2_000) shown.clear()
        }
        flush()
        if (running) worker.schedule({ poll() }, minOf(next, nextDueSeconds()), TimeUnit.SECONDS)
    }

    private fun nextDueSeconds(): Long = synchronized(pending) {
        pending.values.minOfOrNull { it.first }?.let { ((it - System.currentTimeMillis()) / 1000).coerceIn(1, 600) } ?: 600
    }

    /** Shows alerts whose delay has passed (dropping your team's while you're watching in TiviMate). */
    private fun flush() {
        val now = System.currentTimeMillis()
        val ready = synchronized(pending) {
            pending.entries.filter { it.value.first <= now }.map { it.key to it.value.second }.also { r -> r.forEach { pending.remove(it.first) } }
        }
        val watchApp = context.getSharedPreferences("veil", Context.MODE_PRIVATE).getString("watch_with", "ar.tvplayer.tv")
        ready.forEach { (gameId, a) ->
            if (a.favorite && foreground() == watchApp) return@forEach
            if (now - (lastShownAt[gameId] ?: 0L) < 3 * 60_000L) return@forEach
            lastShownAt[gameId] = now
            main.post { runCatching { show(a) } }
        }
    }
}

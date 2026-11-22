package dev.veil.launcher.state

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.veil.launcher.BuildConfig
import dev.veil.launcher.data.Updates
import dev.veil.launcher.data.WatchItem
import dev.veil.launcher.data.YouTubeShows
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Daily YouTube show cards (PROJECT.md D34): today's The Yak, put in front of Continue Watching. */
class ShowsState(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: SharedPreferences,
    /** Called when the card appears, changes or goes, so the row is rebuilt. */
    private val onChange: () -> Unit,
) {
    private var episode: YouTubeShows.Episode? = null
    private var lastCheck = 0L
    private val showPrefs get() = context.getSharedPreferences("ravine_shows", Context.MODE_PRIVATE)

    var followYak by mutableStateOf(prefs.getBoolean("follow_yak", true))
        private set

    fun toggleFollowYak() {
        followYak = !followYak
        prefs.edit().putBoolean("follow_yak", followYak).apply()
        lastCheck = 0L
        if (!followYak) { episode = null; onChange() } else tick(System.currentTimeMillis())
    }

    /** The card for today's episode, if any ("NEW" until opened). */
    fun card(): WatchItem? = episode?.let { YouTubeShows.toItem(it, isNew = !YouTubeShows.opened(context, it.videoId)) }

    /** Hold-OK → Remove: hidden until the next episode. */
    fun dismiss(videoId: String) {
        showPrefs.edit().putString("dismissed", videoId).apply()
        episode = null
        onChange()
    }

    /** Checks the feed every 20 minutes while Home is up. */
    fun tick(now: Long) {
        if (!followYak || now - lastCheck < 20 * 60_000L) return
        lastCheck = now
        scope.launch {
            val e = withContext(Dispatchers.IO) { runCatching { YouTubeShows.fetch(YouTubeShows.THE_YAK) }.getOrNull() }
            val next = e?.takeIf { it.videoId != showPrefs.getString("dismissed", null) }
            if (next?.videoId != episode?.videoId) { episode = next; onChange() }
        }
    }
}

/**
 * Yak rewind (PROJECT.md D54): an older Yak episode from yak.campsapps.com, in front of Continue Watching on
 * the chosen days, only while today's Yak card isn't waiting to be watched.
 */
class YakRewindState(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: SharedPreferences,
    private val toast: (String) -> Unit,
    private val onChange: () -> Unit,
) {
    private var pick: dev.veil.launcher.data.YakRewind.Pick? = null
    private var lastCheck = 0L

    var schedule by mutableStateOf(
        runCatching { dev.veil.launcher.data.YakRewind.Schedule.valueOf(prefs.getString("yak_rewind", "WEEKENDS")!!) }
            .getOrDefault(dev.veil.launcher.data.YakRewind.Schedule.WEEKENDS),
    )
        private set

    /** Set once yak.properties has been pushed; the menu explains how otherwise. */
    var configured by mutableStateOf(false)
        private set

    fun cycleSchedule() {
        schedule = schedule.next()
        prefs.edit().putString("yak_rewind", schedule.name).apply()
        lastCheck = 0L
        onChange()
        tick(System.currentTimeMillis())
    }

    /** The card, if the schedule allows it now and today's episode isn't waiting ([dailyWaiting]). */
    fun card(dailyWaiting: Boolean, now: Long = System.currentTimeMillis()): WatchItem? {
        val p = pick ?: return null
        if (dailyWaiting || !dev.veil.launcher.data.YakRewind.allowed(schedule, now)) return null
        return dev.veil.launcher.data.YakRewind.toItem(p)
    }

    private var wasAllowed: Boolean? = null

    /** Refreshes the shared pick every 15 minutes while Home is up (the phone may have changed it). */
    fun tick(now: Long) {
        // The card comes and goes with the schedule (e.g. Friday at 5 PM) even when the pick hasn't changed.
        val allowedNow = dev.veil.launcher.data.YakRewind.allowed(schedule, now)
        if (wasAllowed != null && wasAllowed != allowedNow && pick != null) onChange()
        wasAllowed = allowedNow
        if (schedule == dev.veil.launcher.data.YakRewind.Schedule.OFF || now - lastCheck < 15 * 60_000L) return
        lastCheck = now
        scope.launch {
            val ok = withContext(Dispatchers.IO) { dev.veil.launcher.data.YakRewind.configured(context) }
            configured = ok
            if (!ok) return@launch
            val p = withContext(Dispatchers.IO) { runCatching { dev.veil.launcher.data.YakRewind.next(context) }.getOrNull() }
            if (p != null && p.id != pick?.id) { pick = p; onChange() }
        }
    }

    /** Hold-OK → "Seen it" (marks it and deals the next one) or "Another" (skips without marking). */
    fun act(action: String, id: String) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { dev.veil.launcher.data.YakRewind.act(context, action, id) } }
            if (r.isFailure) { toast("Couldn't reach yak.campsapps.com"); return@launch }
            pick = r.getOrNull()
            toast(if (action == "seen") "Marked as seen · ${pick?.left ?: 0} left" else "Here's another one")
            onChange()
        }
    }
}

/** "Update ready" at the top of the menu (PROJECT.md D33). */
class UpdateState(
    private val context: Context,
    private val scope: CoroutineScope,
    private val toast: (String) -> Unit,
) {
    private val prefs = context.getSharedPreferences("ravine_updates", Context.MODE_PRIVATE)

    /**
     * "Install updates: Automatic" (D42): download a new build in the background and open Android's install
     * screen the next time Home comes up, so updating is one press. Android requires that press for any
     * sideloaded app; only system apps can install silently.
     */
    var automatic by mutableStateOf(prefs.getBoolean("automatic", true))
        private set

    fun toggleAutomatic() {
        automatic = !automatic
        prefs.edit().putBoolean("automatic", automatic).apply()
        if (automatic) lastCheck = 0L
    }

    /** This install, for the menu footer ("Ravine 0.1.0 · build 24"). */
    val currentLabel: String = "Ravine ${BuildConfig.VERSION_NAME} · build ${Updates.currentBuild(BuildConfig.VERSION_CODE)}"

    private var downloaded: java.io.File? = null
    private var downloadedBuild = -1

    /** A newer Ravine release, if any (checked every 2 hours). */
    var release by mutableStateOf<Updates.Release?>(null)
        internal set // internal: preview tests set it
    var status by mutableStateOf<String?>(null)
        private set
    private var lastCheck = 0L

    fun tick(now: Long) {
        if (now - lastCheck < 2 * 60 * 60_000L) return
        lastCheck = now
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Updates.check(BuildConfig.VERSION_CODE) }.getOrNull() }
            release = r
            if (r != null && automatic && downloadedBuild != r.build) {
                val apk = withContext(Dispatchers.IO) { runCatching { Updates.download(context, r) }.getOrNull() }
                if (apk != null) { downloaded = apk; downloadedBuild = r.build }
            }
        }
    }

    /**
     * Home is on screen: with Automatic on and a downloaded build waiting, open the install screen, once per
     * build (if it's dismissed, "Update ready" stays in the menu).
     */
    fun onHome() {
        val apk = downloaded ?: return
        if (!automatic || prefs.getInt("prompted_build", -1) == downloadedBuild) return
        // Silent check: never jump to Settings just because Home came up; "Update ready" handles the permission.
        if (android.os.Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) return
        prefs.edit().putInt("prompted_build", downloadedBuild).apply()
        runCatching { Updates.install(context, apk) }
    }

    /**
     * "Check for updates" in the menu (D43): checks now, ignoring the 2-hour timer. Up to date → says so;
     * a newer build → straight to download and Android's install screen.
     */
    fun checkNow() {
        if (status == "Checking…" || status == "Downloading…") return
        status = "Checking…"
        lastCheck = System.currentTimeMillis()
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Updates.check(BuildConfig.VERSION_CODE) } }
            status = null
            when {
                r.isFailure -> status = "Couldn't reach GitHub · try again"
                r.getOrNull() == null -> { release = null; status = "Up to date" }
                else -> { release = r.getOrNull(); install() }
            }
        }
    }

    /** One-time install permission, then download and Android's installer. */
    fun install() {
        val r = release ?: return
        if (status == "Downloading…") return
        if (!Updates.canInstall(context)) { toast("Allow Ravine to install apps, then choose Update again"); return }
        status = "Downloading…"
        scope.launch {
            val apk = withContext(Dispatchers.IO) { runCatching { Updates.download(context, r) }.getOrNull() }
            if (apk == null) { status = "Download failed · try again"; return@launch }
            status = null
            runCatching { Updates.install(context, apk) }.onFailure { status = "Couldn't open the installer" }
        }
    }
}

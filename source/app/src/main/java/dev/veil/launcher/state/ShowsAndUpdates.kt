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

/** "Update ready" at the top of the menu (PROJECT.md D33). */
class UpdateState(
    private val context: Context,
    private val scope: CoroutineScope,
    private val toast: (String) -> Unit,
) {
    /** A newer Ravine release, if any (checked every 6 hours). */
    var release by mutableStateOf<Updates.Release?>(null)
        internal set // internal: preview tests set it
    var status by mutableStateOf<String?>(null)
        private set
    private var lastCheck = 0L

    fun tick(now: Long) {
        if (now - lastCheck < 6 * 60 * 60_000L) return
        lastCheck = now
        scope.launch {
            release = withContext(Dispatchers.IO) { runCatching { Updates.check(BuildConfig.VERSION_CODE) }.getOrNull() }
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

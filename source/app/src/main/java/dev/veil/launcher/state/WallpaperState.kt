package dev.veil.launcher.state

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.veil.launcher.data.Wallpapers
import dev.veil.launcher.ui.PictureDepth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Home's photo (PROJECT.md D5, D6, D15, D18): library, rotation, game-day collections, depth and grain. */
class WallpaperState(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: SharedPreferences,
    /** The team's collection on game day, else null. */
    private val preferred: (Long) -> String?,
    /** Rotate only while Home is what's on screen and no show artwork is up. */
    private val idleOnHome: () -> Boolean,
) {
    val library = Wallpapers(context)

    /** The photo behind Home while no Continue Watching card has focus. */
    var current by mutableStateOf(library.at(library.step))
        private set

    private var applied: String? = null
    private var lastFeed = 0L
    private var lastRotate = System.currentTimeMillis()
    private var failures = 0

    private fun at(step: Int, now: Long = System.currentTimeMillis()) = library.at(step, prefer = preferred(now))

    /** Next photo now (the menu's "Next photo"), and warm the cache for the one after. */
    fun next() {
        library.step = library.step + 1
        current = at(library.step)
        lastRotate = System.currentTimeMillis()
        prefetch()
    }

    /** A photo that won't load (moved, blocked, offline) is skipped, up to once around the whole library. */
    fun onResult(ok: Boolean) {
        if (ok) { failures = 0; return }
        if (++failures >= library.all.size.coerceAtLeast(1)) return // offline: stop trying
        next()
    }

    private fun prefetch() {
        val n = at(library.step + 1) ?: return
        runCatching {
            coil.Coil.imageLoader(context).enqueue(
                coil.request.ImageRequest.Builder(context).data(n.url).size(1920, 1080).build(),
            )
        }
    }

    /** Feed every 6 h; game-day switch at once; rotation on the configured interval. */
    fun tick(now: Long) {
        if (now - lastFeed > 6 * 60 * 60_000L) {
            lastFeed = now
            scope.launch {
                val ok = withContext(Dispatchers.IO) { library.refresh() }
                if (ok && current == null) { current = at(library.step); prefetch() }
            }
        }
        val prefer = preferred(now)
        if (prefer != applied) {
            applied = prefer
            current = at(library.step, now)
            lastRotate = now
            prefetch()
        }
        if (idleOnHome() && now - lastRotate >= library.rotateMinutes * 60_000L) next()
    }

    var rotateMinutes by mutableStateOf(library.rotateMinutes)
        private set

    fun cycleRotateMinutes() {
        val opts = listOf(5, 10, 20, 30, 60)
        rotateMinutes = opts[(opts.indexOf(rotateMinutes) + 1).mod(opts.size)]
        library.rotateMinutes = rotateMinutes
    }

    /** How deep the picture is drawn (PictureDepth); Deep suits a UI shown in HDR mode. */
    var pictureDepth by mutableStateOf(prefs.getInt("picture_depth", PictureDepth.DEEP))
        private set

    fun cyclePictureDepth() {
        pictureDepth = (pictureDepth + 1) % 3
        prefs.edit().putInt("picture_depth", pictureDepth).apply()
    }

    var filmGrain by mutableStateOf(prefs.getBoolean("film_grain", true))
        private set

    fun toggleFilmGrain() {
        filmGrain = !filmGrain
        prefs.edit().putBoolean("film_grain", filmGrain).apply()
    }
}

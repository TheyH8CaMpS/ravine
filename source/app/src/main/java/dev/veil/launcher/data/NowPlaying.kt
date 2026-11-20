package dev.veil.launcher.data

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.veil.launcher.MediaListener

/** Tracks the active media session (Spotify, Nuvio, …) for the "now playing" pill. */
class NowPlaying(private val context: Context) {
    /** Immutable: every change creates a new Track, so Compose can skip parts that didn't change. */
    @androidx.compose.runtime.Immutable
    data class Track(
        val pkg: String,
        val title: String,
        val artist: String?,
        val album: String? = null,
        val art: ImageBitmap?,
        /** Larger copy of the album art for Veil Ambient. */
        val artLarge: ImageBitmap?,
        val playing: Boolean,
        /** Song length in ms, 0 if unknown. */
        val durationMs: Long = 0,
        /** Position reported by the player, and when (elapsedRealtime) it was reported. */
        val positionMs: Long = 0,
        val positionAt: Long = 0,
        val speed: Float = 1f,
    ) {
        /** Current position, extrapolated while playing. */
        fun positionNow(): Long {
            val p = if (playing) positionMs + ((SystemClock.elapsedRealtime() - positionAt) * speed).toLong() else positionMs
            return if (durationMs > 0) p.coerceIn(0, durationMs) else p.coerceAtLeast(0)
        }
    }

    var track by mutableStateOf<Track?>(null)
        internal set // internal: previews set a song

    private val msm = context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(context, MediaListener::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var controllers: List<MediaController> = emptyList()
    /** Art for the current song: (song key, small, large). Only ever holds real artwork. */
    private var artCache: Triple<String, ImageBitmap, ImageBitmap>? = null
    /** Song whose artwork hasn't arrived yet, and since when. */
    private var missingArtKey: String? = null
    private var missingArtSince = 0L
    private val recheck = Runnable { recompute() }
    /** Album art is resized here, off the UI thread (song changes stay smooth). */
    private val artWorker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "veil-art").apply { isDaemon = true } }
    /** The song whose artwork is being resized right now. */
    private var artPending: String? = null
    /** Re-evaluates when a paused session reaches the hide time. */
    private val hideCheck = Runnable { recompute() }
    /** When we first saw each app paused (fallback if the player doesn't timestamp its state). */
    private val pausedSeen = HashMap<String, Long>()

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = recompute()
        override fun onMetadataChanged(metadata: MediaMetadata?) = recompute()
        override fun onSessionDestroyed() = recompute()
    }
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> attach(list.orEmpty()) }

    /** Starts listening; does nothing if the media permission hasn't been granted. */
    fun start() {
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            attach(msm.getActiveSessions(component))
        } catch (_: SecurityException) {
            track = null
        }
    }

    fun stop() {
        handler.removeCallbacks(recheck)
        handler.removeCallbacks(hideCheck)
        runCatching { msm.removeOnActiveSessionsChangedListener(sessionsListener) }
        controllers.forEach { runCatching { it.unregisterCallback(callback) } }
        controllers = emptyList()
    }

    fun togglePlayPause() {
        val c = current() ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    private fun attach(list: List<MediaController>) {
        controllers.forEach { runCatching { it.unregisterCallback(callback) } }
        controllers = list.filter { it.packageName != context.packageName }
        controllers.forEach { it.registerCallback(callback, handler) }
        recompute()
    }

    /** Prefer whatever is playing; otherwise the most recent paused session. */
    private fun current(): MediaController? =
        controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull {
                it.playbackState?.state in setOf(PlaybackState.STATE_PAUSED, PlaybackState.STATE_BUFFERING) && it.metadata != null
            }

    private fun recompute() {
        val c = current()
        val md = c?.metadata
        if (c == null || md == null) { track = null; return }
        // Paused for a while (e.g. Spotify left open): treat it as nothing playing, so the pill goes away
        // and Ambient shows your shows again. Pressing play brings it straight back.
        handler.removeCallbacks(hideCheck)
        val state = c.playbackState
        if (state?.state == PlaybackState.STATE_PLAYING) {
            pausedSeen.remove(c.packageName)
        } else {
            val now = SystemClock.elapsedRealtime()
            val seen = pausedSeen.getOrPut(c.packageName) { now }
            // The earlier of: when the player says its state last changed, and when we first saw it paused
            // (some players refresh their timestamp while paused).
            val since = minOf(seen, state?.lastPositionUpdateTime?.takeIf { it in 1..now } ?: seen)
            val pausedFor = now - since
            if (pausedFor >= HIDE_AFTER_PAUSE_MS) { track = null; return }
            handler.postDelayed(hideCheck, HIDE_AFTER_PAUSE_MS - pausedFor + 500)
        }
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (title.isNullOrBlank()) { track = null; return }
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val artKey = "${c.packageName}|$title|$artist"
        var small: ImageBitmap? = null
        var large: ImageBitmap? = null
        val cached = artCache?.takeIf { it.first == artKey }
        if (cached != null) {
            small = cached.second; large = cached.third
        } else {
            val bmp: Bitmap? = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            fun scaled(b: Bitmap, size: Int): ImageBitmap? {
                val s = minOf(size, maxOf(b.width, b.height))
                return runCatching { Bitmap.createScaledBitmap(b, s, s, true).asImageBitmap() }.getOrNull()
            }
            when {
                bmp != null && track != null -> {
                    // Resize on the worker; until it's done (a few ms) keep the current cover, as in the
                    // "artwork arrives a moment later" case below. (The very first song is resized right away.)
                    if (artPending != artKey) {
                        artPending = artKey
                        artWorker.execute {
                            val s96 = scaled(bmp, 96); val s640 = scaled(bmp, 640)
                            handler.post {
                                if (artPending == artKey) artPending = null
                                if (s96 != null && s640 != null) {
                                    artCache = Triple(artKey, s96, s640)
                                    missingArtKey = null
                                    handler.removeCallbacks(recheck)
                                }
                                if (controllers.isNotEmpty()) recompute() // not after stop()
                            }
                        }
                    }
                    small = track?.art; large = track?.artLarge
                }
                bmp != null -> {
                    small = scaled(bmp, 96); large = scaled(bmp, 640)
                }
            }
            if (bmp != null && track != null) {
                // Published below with the current cover; the resized one follows from the worker.
            } else if (small != null && large != null) {
                artCache = Triple(artKey, small, large)
                missingArtKey = null
                handler.removeCallbacks(recheck)
            } else {
                // Players often send the new title first and the artwork a moment later. Keep the
                // previous cover for up to 4 s instead of flashing a placeholder, then give up.
                val now = System.currentTimeMillis()
                if (missingArtKey != artKey) { missingArtKey = artKey; missingArtSince = now }
                if (now - missingArtSince < 4_000) {
                    small = track?.art; large = track?.artLarge
                    handler.removeCallbacks(recheck)
                    handler.postDelayed(recheck, 4_100)
                }
            }
        }
        val ps = c.playbackState
        track = Track(
            pkg = c.packageName,
            title = title,
            artist = artist,
            album = md.getString(MediaMetadata.METADATA_KEY_ALBUM),
            art = small,
            artLarge = large,
            playing = ps?.state == PlaybackState.STATE_PLAYING,
            durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0),
            positionMs = ps?.position ?: 0,
            positionAt = ps?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            speed = ps?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
        )
    }

    companion object {
        /** A paused player disappears from Veil after this long. */
        const val HIDE_AFTER_PAUSE_MS = 5 * 60_000L
    }
}

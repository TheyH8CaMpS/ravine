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

internal data class TrackArtworkKey(val pkg: String, val title: String, val artist: String?, val album: String?, val mediaId: String?)

/** Keep the latest decoded cover tied to the track that supplied it. */
internal class TrackArtwork<T> {
    private var entry: Pair<TrackArtworkKey, T>? = null
    fun forTrack(key: TrackArtworkKey): T? = entry?.takeIf { it.first == key }?.second
    fun store(key: TrackArtworkKey, art: T) { entry = key to art }
}

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
        /** Longest side of the artwork's source in px (0 = unknown). Small covers aren't drawn above 2x (ravine#11). */
        val artPx: Int = 0,
        val mediaId: String? = null,
    ) {
        internal val artworkKey get() = TrackArtworkKey(pkg, title, artist, album, mediaId)

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
    /** Art for the current song. Only ever holds real artwork. */
    private class Art(val small: ImageBitmap, val large: ImageBitmap, val px: Int)
    private val artCache = TrackArtwork<Art>()
    /** Song whose art URI failed to load, so it isn't retried on every metadata change. */
    private var artUriFailed: TrackArtworkKey? = null
    /** Album art is resized here, off the UI thread (song changes stay smooth). */
    private val artWorker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "veil-art").apply { isDaemon = true } }
    /** The song whose artwork is being resized right now. */
    private var artPending: TrackArtworkKey? = null
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
        val album = md.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val mediaId = md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        val artKey = TrackArtworkKey(c.packageName, title, artist, album, mediaId)
        if (artPending != artKey) artPending = null
        var small: ImageBitmap? = null
        var large: ImageBitmap? = null
        var artPx = 0
        val cached = artCache.forTrack(artKey)
        if (cached != null) {
            small = cached.small; large = cached.large; artPx = cached.px
        } else {
            val bmp: Bitmap? = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            // The session's art URI is usually full size, where the bitmap is often a thumbnail (ravine#11).
            val uri = if (artUriFailed == artKey) null else CoverArt.artUri(md)
            val source = CoverArt.choose(uri, bmp != null)
            fun scaled(b: Bitmap, size: Int): ImageBitmap? {
                val s = minOf(size, maxOf(b.width, b.height))
                return runCatching { Bitmap.createScaledBitmap(b, s, s, true).asImageBitmap() }.getOrNull()
            }
            // A URI is always loaded on the worker (it may be a download); a bitmap is too, except for the very first song.
            val async = source == CoverArt.Source.URI || (source == CoverArt.Source.BITMAP && track != null)
            if (async) {
                if (artPending != artKey) {
                    artPending = artKey
                    artWorker.execute {
                        val loaded = uri?.let { CoverArt.load(context, it) }
                        val b = loaded ?: bmp // the URI failed: fall back to the bitmap
                        val s96 = b?.let { scaled(it, 96) }; val s640 = b?.let { scaled(it, CoverArt.MAX_PX) }
                        val px = b?.let { maxOf(it.width, it.height) } ?: 0
                        handler.post {
                            if (artPending != artKey) return@post
                            artPending = null
                            if (uri != null && loaded == null) artUriFailed = artKey
                            if (s96 != null && s640 != null) {
                                artCache.store(artKey, Art(s96, s640, px))
                            }
                            if (controllers.isNotEmpty()) recompute() // not after stop()
                        }
                    }
                }
                if (track == null && bmp != null) {
                    // First song: show the bitmap now; the URI's cover replaces it when it arrives.
                    small = scaled(bmp, 96); large = scaled(bmp, CoverArt.MAX_PX); artPx = maxOf(bmp.width, bmp.height)
                }
            } else if (bmp != null) {
                small = scaled(bmp, 96); large = scaled(bmp, CoverArt.MAX_PX); artPx = maxOf(bmp.width, bmp.height)
                if (small != null && large != null) {
                    artCache.store(artKey, Art(small, large, artPx))
                }
            }
        }
        val ps = c.playbackState
        track = Track(
            pkg = c.packageName,
            title = title,
            artist = artist,
            album = album,
            art = small,
            artLarge = large,
            playing = ps?.state == PlaybackState.STATE_PLAYING,
            durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0),
            positionMs = ps?.position ?: 0,
            positionAt = ps?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            speed = ps?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            artPx = if (small != null) artPx else 0,
            mediaId = mediaId,
        )
    }

    companion object {
        /** A paused player disappears from Veil after this long. */
        const val HIDE_AFTER_PAUSE_MS = 5 * 60_000L
    }
}

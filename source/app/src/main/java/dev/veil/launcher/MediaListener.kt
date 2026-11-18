package dev.veil.launcher

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import dev.veil.launcher.data.LocalHistory

/**
 * Exists so Android allows Ravine to see active media sessions (for "now playing").
 * It does not read, store or react to notifications.
 *
 * Ravine: while connected it also follows **Nuvio's** media session only, and records what you watch
 * (title, episode, position) into [LocalHistory] on this TV, for the account-free Continue Watching row.
 * It runs in the background because Ravine itself isn't on screen while Nuvio plays.
 */
class MediaListener : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private var nuvio: MediaController? = null

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            // Pausing or stopping is a natural checkpoint: save now.
            val s = state?.state
            record(flush = s == PlaybackState.STATE_PAUSED || s == PlaybackState.STATE_STOPPED)
            schedule()
        }
        override fun onMetadataChanged(metadata: MediaMetadata?) = record(flush = true)
        override fun onSessionDestroyed() { record(flush = true); detach() }
    }

    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { list -> attach(list.orEmpty()) }

    /** Every 30 s while playing, so a crash or power-off loses little. */
    private val tick = object : Runnable {
        override fun run() { record(flush = false); schedule() }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        LocalHistory.init(this)
        runCatching {
            val msm = getSystemService(MediaSessionManager::class.java)
            val me = ComponentName(this, MediaListener::class.java)
            msm.addOnActiveSessionsChangedListener(sessions, me, handler)
            attach(msm.getActiveSessions(me))
        }
    }

    override fun onListenerDisconnected() {
        runCatching { getSystemService(MediaSessionManager::class.java).removeOnActiveSessionsChangedListener(sessions) }
        detach()
        super.onListenerDisconnected()
    }

    private fun attach(list: List<MediaController>) {
        val found = list.firstOrNull { it.packageName == LauncherState.NUVIO }
        if (found?.sessionToken == nuvio?.sessionToken) return
        detach()
        nuvio = found?.also { it.registerCallback(callback, handler); record(flush = false); schedule() }
    }

    private fun detach() {
        handler.removeCallbacks(tick)
        nuvio?.let { runCatching { it.unregisterCallback(callback) } }
        nuvio = null
    }

    private fun schedule() {
        handler.removeCallbacks(tick)
        if (nuvio?.playbackState?.state == PlaybackState.STATE_PLAYING) handler.postDelayed(tick, 30_000)
    }

    private fun record(flush: Boolean) {
        val c = nuvio ?: return
        val md = c.metadata ?: return
        val ps = c.playbackState ?: return
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: return
        val subtitle = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        var duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
        // Position now: the player reports it at state changes; extrapolate while playing.
        var pos = ps.position
        if (ps.state == PlaybackState.STATE_PLAYING && ps.lastPositionUpdateTime > 0) {
            pos += ((SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime) * ps.playbackSpeed.coerceAtLeast(0f)).toLong()
        }
        if (duration > 0) pos = pos.coerceAtMost(duration) else duration = 0
        LocalHistory.record(title, subtitle, pos, duration, flush)
    }
}

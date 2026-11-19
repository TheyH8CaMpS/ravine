package dev.veil.launcher.state

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.veil.launcher.data.NetflixButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** "Netflix button" in the menu (D47): open SmartTube instead, after one press to learn the button. */
class RemoteState(private val context: Context, private val scope: CoroutineScope) {
    private val prefs = NetflixButton.prefs(context)

    var target by mutableStateOf(prefs.getString(NetflixButton.TARGET, "") ?: "")
        private set
    var learning by mutableStateOf(false)
        private set
    /** Shown once if no press arrived (the button never reached Ravine). */
    var missed by mutableStateOf(false)
        private set
    private var timeout: Job? = null

    // The service writes the learned key from its own thread; keep a strong reference to the listener.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, _ ->
        learning = p.getBoolean(NetflixButton.LEARNING, false)
        target = p.getString(NetflixButton.TARGET, "") ?: ""
        if (!learning) timeout?.cancel()
    }
    init {
        prefs.edit().putBoolean(NetflixButton.LEARNING, false).apply()
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    val label: String get() = when {
        learning -> "Press it now…"
        target.isEmpty() -> "Netflix"
        else -> runCatching {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(target, 0)).toString()
        }.getOrDefault("SmartTube")
    }

    /** Netflix → SmartTube (learning the button first if needed) → Netflix. */
    fun cycle() {
        missed = false
        if (learning || target.isNotEmpty()) {
            prefs.edit().putBoolean(NetflixButton.LEARNING, false).putString(NetflixButton.TARGET, "").apply()
            return
        }
        val app = NetflixButton.smartTube(context) ?: return
        if (prefs.getInt(NetflixButton.KEY, -1) != -1) { prefs.edit().putString(NetflixButton.TARGET, app).apply(); return }
        // The service saves the key and the target together when the press arrives.
        prefs.edit().putString("pending_target", app).putBoolean(NetflixButton.LEARNING, true).apply()
        timeout?.cancel()
        timeout = scope.launch {
            delay(20_000)
            if (prefs.getBoolean(NetflixButton.LEARNING, false)) {
                prefs.edit().putBoolean(NetflixButton.LEARNING, false).apply()
                missed = true
            }
        }
    }
}

package dev.veil.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import dev.veil.launcher.data.NetflixButton

/**
 * Makes the remote's Home button open Ravine, and (if chosen) the Netflix button open SmartTube. It only looks
 * at those two keys and at which app's window comes to the front; it never reads screen content. All other
 * keys pass through.
 */
class HomeButtonService : AccessibilityService() {

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_HOME) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) goHome()
            return true
        }
        return netflixKey(event)
    }

    private val remote by lazy { NetflixButton.prefs(this) }
    private var netflixPressedAt = 0L

    /** The Netflix button opens the chosen app instead (D47); while the menu is learning, its code is recorded. */
    private fun netflixKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (remote.getBoolean(NetflixButton.LEARNING, false) && !NetflixButton.ordinary(code)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                remote.edit().putInt(NetflixButton.KEY, code)
                    .putString(NetflixButton.TARGET, remote.getString("pending_target", "") ?: "")
                    .putBoolean(NetflixButton.LEARNING, false).apply()
                netflixPressedAt = System.currentTimeMillis()
            }
            return true
        }
        if (code != remote.getInt(NetflixButton.KEY, -1) || code == -1) return false
        val target = remote.getString(NetflixButton.TARGET, "") ?: ""
        if (target.isEmpty()) return false
        // Swallow both halves of the press; open on release, as a click would.
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
            netflixPressedAt = System.currentTimeMillis()
            open(target)
        }
        return true
    }

    private fun open(pkg: String) {
        try {
            val pm = packageManager
            (pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg))
                ?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        } catch (_: Exception) {
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Fallback: if Google's home screen still comes up (e.g. after a system event), replace it.
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return
        // If the system still opens Netflix for its button (some firmware does so before apps see the key),
        // put the chosen app back in front. Only within seconds of a press: opening Netflix yourself is untouched.
        if (pkg == NetflixButton.NETFLIX && System.currentTimeMillis() - netflixPressedAt < 5_000) {
            val target = remote.getString(NetflixButton.TARGET, "") ?: ""
            if (target.isNotEmpty()) { netflixPressedAt = 0L; open(target); return }
        }
        // Only the TV's own home *screen* (not its other screens, like an apps list or settings).
        if (pkg == GOOGLE_HOME && cls.endsWith("HomeActivity")) { goHome(); return }
        if (otherHomes.any { (p, c) -> p == pkg && c == cls }) goHome()
    }

    override fun onInterrupt() = Unit

    private fun goHome() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
                ),
            )
        } catch (_: Exception) {
            // Never crash the service; Google's home screen remains as a fallback.
        }
    }

    /**
     * The TV's *default* home screen (package, activity), e.g. the Shield's com.google.android.tvlauncher.
     * Ravine: only the system default is replaced. Veil listed every app that *can* be a home screen, which
     * also bounced launcher apps like Projectivy straight back to Ravine whenever you opened them (or their
     * settings) from the apps list. Android's boot-time "FallbackHome" in Settings is left out.
     */
    private val otherHomes: List<Pair<String, String>> by lazy {
        runCatching {
            val r = packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
            ) ?: return@runCatching emptyList()
            val p = r.activityInfo.packageName
            if (p == packageName || p == "android" || p == "com.android.settings" || p == "com.android.tv.settings") emptyList()
            else listOf(p to r.activityInfo.name)
        }.getOrDefault(emptyList())
    }

    private companion object { const val GOOGLE_HOME = "com.google.android.apps.tv.launcherx" }
}

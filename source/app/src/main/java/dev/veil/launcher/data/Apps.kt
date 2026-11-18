package dev.veil.launcher.data

import dev.veil.launcher.BuildConfig
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/** Immutable: an entry is never modified (a changed app gets a new entry), so Compose can skip unchanged tiles. */
@androidx.compose.runtime.Immutable
data class AppEntry(
    val pkg: String,
    val label: String,
    val banner: ImageBitmap?,
    val icon: ImageBitmap?,
    val launch: Intent,
)

object Apps {
    /** Packages never shown in the app drawer. */
    private val HIDDEN = setOf(
        BuildConfig.APPLICATION_ID,
        "com.google.android.apps.tv.launcherx",
        "com.google.android.tvlauncher",
    )

    /**
     * Entries from earlier loads, by package, with the app's install/update time and the UI language when it
     * was built. Veil reloads the app list every time it comes back to the front; reusing unchanged entries
     * avoids re-decoding every icon and banner each time (and an unchanged list then isn't redrawn at all).
     */
    private class Cached(val updated: Long, val locale: String, val entry: AppEntry)
    private val cache = HashMap<String, Cached>()

    fun load(context: Context): List<AppEntry> = synchronized(cache) {
        val pm = context.packageManager
        val locale = context.resources.configuration.locales.toLanguageTags()
        val leanback = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0,
        )
        val phone = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
        )
        val byPkg = LinkedHashMap<String, ResolveInfo>()
        (leanback + phone).forEach { ri ->
            val p = ri.activityInfo.packageName
            if (p !in HIDDEN && p !in byPkg) byPkg[p] = ri
        }
        val result = byPkg.values.mapNotNull { ri ->
            val p = ri.activityInfo.packageName
            val updated = runCatching { pm.getPackageInfo(p, 0).lastUpdateTime }.getOrDefault(-1L)
            cache[p]?.takeIf { it.updated == updated && it.locale == locale && updated != -1L }?.let { return@mapNotNull it.entry }
            val launch = pm.getLeanbackLaunchIntentForPackage(p)
                ?: pm.getLaunchIntentForPackage(p) ?: return@mapNotNull null
            val banner = runCatching {
                (ri.activityInfo.loadBanner(pm) ?: ri.activityInfo.applicationInfo.loadBanner(pm))
                    ?.toBitmap(320, 180)?.asImageBitmap()
            }.getOrNull()
            // Icons are used for the round Home pills; 120px covers a 30dp circle at 2x density.
            val icon = runCatching {
                ri.activityInfo.applicationInfo.loadIcon(pm).toBitmap(120, 120).asImageBitmap()
            }.getOrNull()
            AppEntry(p, ri.loadLabel(pm).toString(), banner, icon, launch).also { cache[p] = Cached(updated, locale, it) }
        }.sortedBy { it.label.lowercase() }
        // Forget uninstalled apps.
        cache.keys.retainAll(byPkg.keys)
        result
    }
}

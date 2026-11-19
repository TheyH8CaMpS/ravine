package dev.veil.launcher.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Update ready" in the menu (PROJECT.md D33): checks Ravine's latest GitHub Release (public, no key) and, on
 * request, downloads ravine.apk and hands it to Android's installer. Builds are numbered by CI
 * (tag v0.1.0-build20, versionCode 100 + 20), so a higher build number is an update.
 */
object Updates {
    data class Release(val build: Int, val name: String, val apkUrl: String)

    private const val LATEST = "https://api.github.com/repos/TheyH8CaMpS/ravine/releases/latest"

    /** The build number in a CI tag ("v0.1.0-build20" → 20), or null. */
    fun buildOf(tag: String): Int? = Regex("""build(\d+)$""").find(tag)?.groupValues?.get(1)?.toIntOrNull()

    /** This install's build number (versionCode = 100 + CI run number; local builds are 100). */
    fun currentBuild(versionCode: Int): Int = (versionCode - 100).coerceAtLeast(0)

    fun parse(json: JSONObject): Release? {
        val build = buildOf(json.optString("tag_name")) ?: return null
        val assets = json.optJSONArray("assets") ?: return null
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name") == "ravine.apk" }?.str("browser_download_url") ?: return null
        return Release(build, json.optString("name"), apk)
    }

    /** The latest release if it's newer than [versionCode]. Network: call off the main thread. */
    fun check(versionCode: Int): Release? {
        val (code, text) = Http.call("GET", LATEST, mapOf("Accept" to "application/vnd.github+json"))
        // A failed check is an error, not "up to date" (Check for updates says which).
        if (code != 200) error("GitHub answered $code")
        return parse(JSONObject(text))?.takeIf { it.build > currentBuild(versionCode) }
    }

    /** True if Android lets Ravine open the installer; otherwise sends the owner to the one-time permission. */
    fun canInstall(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()) return true
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        return false
    }

    /** Downloads the APK (follows GitHub's redirect to its asset host). Network: off the main thread. */
    fun download(context: Context, release: Release): File {
        val dir = File(context.externalCacheDir ?: context.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "ravine.apk")
        var url = release.apkUrl
        repeat(5) {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 15_000; c.readTimeout = 60_000
                c.setRequestProperty("User-Agent", dev.veil.launcher.RavineApp.USER_AGENT)
                when (c.responseCode) {
                    in 300..399 -> { url = c.getHeaderField("Location") ?: error("redirect without location") }
                    200 -> { c.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }; return out }
                    else -> error("download failed: ${c.responseCode}")
                }
            } finally { c.disconnect() }
        }
        error("too many redirects")
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

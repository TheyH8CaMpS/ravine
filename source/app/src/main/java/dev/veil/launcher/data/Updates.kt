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
 * request, downloads ravine.apk and hands it to Android's installer.
 */
object Updates {
    data class Version(val major: Int, val minor: Int, val patch: Int, val build: Int? = null) : Comparable<Version> {
        override fun compareTo(other: Version): Int =
            compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }, { it.build ?: 0 })

        override fun toString(): String = "v$major.$minor.$patch" + (build?.let { "-build$it" } ?: "")
    }

    data class Release(val version: Version, val apkUrl: String) {
        val label: String get() = version.build?.let { "Build $it" } ?: version.toString()

        fun isNewerThan(versionName: String, versionCode: Int): Boolean {
            val installed = versionOf("v$versionName", currentBuild(versionCode)) ?: return false
            return version > installed
        }
    }

    private const val LATEST = "https://api.github.com/repos/TheyH8CaMpS/ravine/releases/latest"

    private val tagPattern = Regex("""v(\d+)\.(\d+)\.(\d+)(?:-build(\d+))?""")
    private val titleBuild = Regex("""\(build (\d+)\)$""")

    private fun versionOf(tag: String, defaultBuild: Int?): Version? {
        val (major, minor, patch, build) = tagPattern.matchEntire(tag)?.destructured ?: return null
        return Version(
            major.toIntOrNull() ?: return null, minor.toIntOrNull() ?: return null, patch.toIntOrNull() ?: return null,
            if (build.isEmpty()) defaultBuild else build.toIntOrNull() ?: return null,
        )
    }

    /** This install's displayed build number, encoded as versionCode minus 100. */
    fun currentBuild(versionCode: Int): Int = (versionCode - 100).coerceAtLeast(0)

    fun parse(json: JSONObject): Release? {
        // Plain tags can omit the build suffix. CI includes the APK's build number in the title.
        val build = titleBuild.find(json.optString("name"))?.groupValues?.get(1)?.toIntOrNull()
        val version = versionOf(json.optString("tag_name"), build) ?: return null
        val assets = json.optJSONArray("assets") ?: return null
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name") == "ravine.apk" }?.str("browser_download_url") ?: return null
        return Release(version, apk)
    }

    /** The latest release if it's newer than this install. Network: call off the main thread. */
    fun check(versionName: String, versionCode: Int): Release? {
        val (code, text) = Http.call("GET", LATEST, mapOf("Accept" to "application/vnd.github+json"))
        // A failed check is an error, not "up to date" (Check for updates says which).
        if (code != 200) error("GitHub answered $code")
        return parse(JSONObject(text))?.takeIf { it.isNewerThan(versionName, versionCode) }
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

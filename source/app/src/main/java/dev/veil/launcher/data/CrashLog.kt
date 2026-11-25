package dev.veil.launcher.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineExceptionHandler
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ravine's own record of crashes and freezes (PROJECT.md D32), readable with one ADB command even after a restart:
 *   adb shell cat /sdcard/Android/data/app.ravine.launcher/files/ravine-crashes.txt
 *
 * - Kotlin/Java crashes: written by an uncaught-exception handler as they happen.
 * - Freezes (ANR) and native crashes: Android 11+ keeps the reason and, for ANRs, the thread dump; Ravine copies
 *   new ones into the file on its next start (ApplicationExitInfo).
 * Stack traces only: no tokens, URLs or personal data. The file is capped at ~256 KB (oldest half dropped).
 */
object CrashLog {
    private const val NAME = "ravine-crashes.txt"
    private const val MAX = 256 * 1024

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { append(app, "CRASH on thread ${thread.name}", error.stackTraceToString()) }
            previous?.uncaughtException(thread, error)
        }
        if (Build.VERSION.SDK_INT >= 30) runCatching { recordExits(app) }
    }

    internal fun coroutineHandler(context: Context): CoroutineExceptionHandler {
        val app = context.applicationContext
        return CoroutineExceptionHandler { _, error ->
            runCatching {
                // Exception messages may include private provider URLs or credentials.
                val frames = error.stackTrace.joinToString("\n") { "  at $it" }
                append(app, "RECOVERED COROUTINE FAILURE", "${error.javaClass.name}\n$frames")
            }
        }
    }

    private fun recordExits(context: Context) {
        val prefs = context.getSharedPreferences("ravine_crashlog", Context.MODE_PRIVATE)
        val since = prefs.getLong("last_exit", 0L)
        val am = context.getSystemService(ActivityManager::class.java)
        val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 10)
            .filter { it.timestamp > since }
            .filter { it.reason in setOf(ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_CRASH) }
            .sortedBy { it.timestamp }
        for (e in exits) {
            val kind = when (e.reason) {
                ApplicationExitInfo.REASON_ANR -> "FREEZE (ANR)"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE CRASH"
                else -> "CRASH"
            }
            // ANR traces are long; the main thread (first lines) is what matters.
            val trace = runCatching { e.traceInputStream?.bufferedReader()?.use { it.readText().take(24 * 1024) } }.getOrNull()
            append(context, "$kind: ${e.description ?: ""}", trace ?: "(no trace recorded)", at = e.timestamp)
        }
        exits.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong("last_exit", it).apply() }
    }

    @Synchronized
    private fun append(context: Context, title: String, body: String, at: Long = System.currentTimeMillis()) {
        val dir = context.getExternalFilesDir(null) ?: return
        val f = File(dir, NAME)
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(at))
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        f.appendText("==== $stamp · Ravine $version · $title\n$body\n\n")
        if (f.length() > MAX) f.writeText(f.readText().takeLast(MAX / 2))
    }
}

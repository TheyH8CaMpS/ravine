package dev.veil.launcher.data

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent

/**
 * The remote's Netflix button (PROJECT.md D47). Android has no standard key code for it, so Ravine learns it:
 * the menu asks for one press and HomeButtonService records the code. Shared by the service and the menu.
 */
object NetflixButton {
    const val NETFLIX = "com.netflix.ninja"
    const val KEY = "key"           // learned key code, -1 until learned
    const val TARGET = "target"     // package to open; "" leaves the button to Netflix
    const val LEARNING = "learning" // waiting for the press

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("ravine_remote", Context.MODE_PRIVATE)

    /** SmartTube, whichever build is installed. */
    fun smartTube(context: Context): String? =
        YouTubeShows.SMARTTUBE.firstOrNull { runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess }

    /** Keys that are never the Netflix button, so pressing OK on the menu row isn't learned by mistake. */
    fun ordinary(code: Int): Boolean = code in setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_UNKNOWN,
    )
}

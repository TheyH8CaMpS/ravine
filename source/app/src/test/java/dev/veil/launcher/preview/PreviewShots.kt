package dev.veil.launcher.preview

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.request.ImageResult
import coil.request.SuccessResult
import dev.veil.launcher.LauncherState
import dev.veil.launcher.data.AppEntry
import dev.veil.launcher.data.WatchItem
import dev.veil.launcher.ui.VeilApp
import dev.veil.launcher.ui.VeilTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * Renders Ravine's screens to PNGs at the Shield's UI size (1920x1080, density 2), so changes can be
 * reviewed without the TV. Run:  ./gradlew testDebugUnitTest --tests dev.veil.launcher.preview.PreviewShots -Ppreviews
 * Output: PNG files in source/previews. Photos come from src/test/resources/preview; app icons are placeholders.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi", application = Application::class)
class PreviewShots {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val outDir = File(System.getProperty("ravine.previewDir") ?: "previews")

    @Before fun setUp() {
        assumeTrue("previews only with -Ppreviews", System.getProperty("ravine.previews") == "true")
        val wall = photo("wall.jpg"); val la = photo("la.jpg"); val lakers = photo("lakers.jpg")
        Coil.setImageLoader(
            ImageLoader.Builder(context).components {
                add(object : Interceptor {
                    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                        val data = chain.request.data.toString()
                        val bmp = when { "lakers" in data -> lakers; "la.jpg" in data -> la; else -> wall }
                        return SuccessResult(BitmapDrawable(context.resources, bmp), chain.request, DataSource.MEMORY)
                    }
                })
            }.build(),
        )
        File(context.filesDir, "wallpapers.json").writeText(
            """[{"location":"Moody green","title":"Fog in the green valley","author":"V2osk","url_img":"https://preview/wall.jpg"},
               {"location":"Lakers","title":"Purple and gold night","author":"Elmar78","url_img":"https://preview/lakers.jpg"}]""",
        )
        // No network in previews: the wallpaper feed "fails", so the test feed above stays in place.
        context.getSharedPreferences("ravine_wallpapers", Context.MODE_PRIVATE).edit().putString("feed", "http://127.0.0.1:9/none").commit()
        context.getSharedPreferences("veil", Context.MODE_PRIVATE).edit()
            .putString("pins", APPS.joinToString("|") { it.first }).commit()
    }

    private fun photo(name: String): Bitmap =
        javaClass.getResourceAsStream("/preview/$name")!!.use { BitmapFactory.decodeStream(it) }

    private fun newState(): LauncherState {
        val state = LauncherState(context, CoroutineScope(SupervisorJob() + Dispatchers.Main))
        state.apps = APPS.map { (pkg, label, color) -> AppEntry(pkg, label, null, icon(label, color), Intent()) }
        state.refreshExtras() // applies game day (team wallpapers) like the minute tick does
        state.items = listOf(
            WatchItem(
                isMovie = true, title = "The Killing of a Sacred Deer", traktId = null, imdb = "tt5715874", tmdb = 399057,
                progress = 0.02f, runtime = 121, backdrop = "https://preview/la.jpg", thumb = "https://preview/la.jpg",
            ),
        )
        return state
    }

    /** Placeholder app icon: a coloured disc with the app's initial. */
    private fun icon(label: String, color: Int) = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also { b ->
        val c = Canvas(b)
        c.drawCircle(48f, 48f, 48f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        c.drawText(label.take(1), 48f, 64f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = 0xFFFFFFFF.toInt(); textSize = 46f; textAlign = Paint.Align.CENTER; isFakeBoldText = true
        })
    }.asImageBitmap()

    private fun shoot(name: String, state: LauncherState, settle: Int = 40, after: (LauncherState) -> Unit = {}) {
        // Ravine never goes idle (wallpaper drift, clock), so the test clock is driven by hand.
        compose.mainClock.autoAdvance = false
        compose.setContent { VeilTheme { Box(Modifier) { VeilApp(state) } } }
        repeat(settle) {
            compose.mainClock.advanceTimeBy(100)
            Thread.sleep(40)
            // Also moves Android's clock, which Coil's crossfade and other animators run on.
            ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        after(state)
        repeat(10) {
            compose.mainClock.advanceTimeBy(100)
            ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        outDir.mkdirs()
        // Draw the window's view hierarchy straight into a bitmap (software, Robolectric native graphics).
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        // Coil's crossfade starts timing at the first draw: draw once, let 1.5 s pass, then capture.
        compose.runOnUiThread { view.draw(Canvas(bmp)) }
        repeat(15) { ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS); compose.mainClock.advanceTimeBy(100) }
        compose.runOnUiThread { bmp.eraseColor(0); view.draw(Canvas(bmp)) }
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun home() = shoot("home", newState())

    // The menu normally opens when its wordmark has focus; here it's opened directly once Home has settled.
    @Test fun menu() = shoot("menu", newState()) { it.veilMenuOpen = true }

    @Test fun ambient() = shoot("ambient", newState()) { it.startAmbient() }

    @Test fun vizSettings() = shoot("viz-settings", newState()) { it.vizSettingsOpen = true }

    // ---- Game day: a fake source, so previews don't depend on ESPN ----

    private fun gameDay(state: dev.veil.launcher.data.Game.State, ours: Int? = null, theirs: Int? = null, detail: String = "") {
        val now = System.currentTimeMillis()
        val start = when (state) {
            dev.veil.launcher.data.Game.State.PRE -> now + 2 * 60 * 60_000L
            dev.veil.launcher.data.Game.State.LIVE -> now - 70 * 60_000L
            dev.veil.launcher.data.Game.State.FINAL -> now - 150 * 60_000L
        }
        val game = dev.veil.launcher.data.Game(
            id = "401910100", team = dev.veil.launcher.data.MyTeams.LAKERS, opponent = "Jazz", opponentAbbr = "UTAH",
            home = true, startMs = start, state = state, detail = detail, ourScore = ours, theirScore = theirs,
            broadcast = "Spectrum Sports Net",
        )
        dev.veil.launcher.data.GameDay.reset()
        dev.veil.launcher.data.GameDay.source = object : dev.veil.launcher.data.GameSource {
            override fun schedule(team: dev.veil.launcher.data.Team) = if (team.key == "lakers") listOf(game) else emptyList()
            override fun refresh(game: dev.veil.launcher.data.Game) = game
        }
        dev.veil.launcher.data.GameDay.tick(context)
    }

    @Test fun gameTonight() { gameDay(dev.veil.launcher.data.Game.State.PRE); shoot("game-tonight", newState()) }

    @Test fun gameLive() {
        gameDay(dev.veil.launcher.data.Game.State.LIVE, 54, 50, "4:12 - 3rd")
        shoot("game-live", newState())
    }

    @Test fun gameLiveAmbient() {
        gameDay(dev.veil.launcher.data.Game.State.LIVE, 54, 50, "4:12 - 3rd")
        shoot("game-live-ambient", newState()) { it.startAmbient() }
    }

    @Test fun gameMenu() { gameDay(dev.veil.launcher.data.Game.State.PRE); shoot("game-menu", newState()) { it.veilMenuOpen = true } }

    private companion object {
        val APPS = listOf(
            Triple("ar.tvplayer.tv", "TiviMate", 0xFF1E88E5.toInt()),
            Triple("com.nuvio.tv", "Nuvio", 0xFF4B2FC9.toInt()),
            Triple("com.teamsmart.videomanager.tv", "SmartTube", 0xFFE53935.toInt()),
            Triple("au.com.shiftyjelly.pocketcasts", "Pocket Casts", 0xFFF43E37.toInt()),
            Triple("com.spotify.tv.android", "Spotify", 0xFF1DB954.toInt()),
        )
    }
}

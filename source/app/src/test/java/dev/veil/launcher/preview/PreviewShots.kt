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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
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
import dev.veil.launcher.data.Updates
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
        // Previews never touch live services (ESPN, YouTube, GitHub, TMDB, weather): deterministic screens.
        dev.veil.launcher.data.Http.offline = true
        dev.veil.launcher.data.GameDay.reset()
        dev.veil.launcher.data.GameDay.source = object : dev.veil.launcher.data.GameSource {
            override fun schedule(team: dev.veil.launcher.data.Team) = emptyList<dev.veil.launcher.data.Game>()
            override fun refresh(game: dev.veil.launcher.data.Game): dev.veil.launcher.data.Game? = null
        }
        val wall = photo("wall.jpg"); val la = photo("la.jpg"); val lakers = photo("lakers.jpg")
        Coil.setImageLoader(
            ImageLoader.Builder(context).components {
                add(object : Interceptor {
                    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                        val data = chain.request.data.toString()
                        if ("/logo/" in data) {
                            // Stand-in team logo: a disc in the team's colour with its abbreviation.
                            val (abbr, hex) = data.substringAfterLast('/').substringBefore('.').split('-')
                            val b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                            val c = android.graphics.Canvas(b)
                            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                            p.color = android.graphics.Color.parseColor("#$hex"); c.drawCircle(32f, 32f, 30f, p)
                            p.color = android.graphics.Color.WHITE; p.textSize = 22f; p.textAlign = android.graphics.Paint.Align.CENTER
                            p.isFakeBoldText = true; c.drawText(abbr, 32f, 40f, p)
                            return SuccessResult(BitmapDrawable(context.resources, b), chain.request, DataSource.MEMORY)
                        }
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
        state.apps = APPS.map { (pkg, label, color) -> AppEntry(pkg, label, null, plateIcon(label, color), Intent()) }
        state.refreshExtras() // applies game day (team wallpapers) like the minute tick does
        state.items = listOf(
            WatchItem(
                isMovie = true, title = "The Killing of a Sacred Deer", traktId = null, imdb = "tt5715874", tmdb = 399057,
                progress = 0.02f, runtime = 121, backdrop = "https://preview/la.jpg", thumb = "https://preview/la.jpg",
            ),
        )
        return state
    }

    /**
     * Stand-in adaptive icon (dark background layer, the app's initial in its colour as the foreground), run
     * through the same AppIcons.plate as on the TV: so the preview shows a mark on a transparent background.
     */
    private fun plateIcon(label: String, color: Int) = dev.veil.launcher.data.AppIcons.plate(
        android.graphics.drawable.AdaptiveIconDrawable(
            android.graphics.drawable.ColorDrawable(0xFF101010.toInt()),
            android.graphics.drawable.BitmapDrawable(context.resources, Bitmap.createBitmap(216, 216, Bitmap.Config.ARGB_8888).also { b ->
                val c = Canvas(b)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
                c.drawRoundRect(android.graphics.RectF(66f, 66f, 150f, 150f), 22f, 22f, p)
                c.drawText(label.take(1), 108f, 126f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = android.graphics.Color.WHITE; textSize = 52f; textAlign = Paint.Align.CENTER; isFakeBoldText = true
                })
            }),
        ),
    ).asImageBitmap()

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
        capture(name)
    }

    private fun capture(name: String) {
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

    /** Nothing in progress: no Continue Watching row (owner, minimalist). */
    @Test fun homeEmpty() = shoot("home-empty", newState()) { it.items = emptyList() }

    // The menu normally opens when its wordmark has focus; here it's opened directly once Home has settled.
    @Test fun menu() = shoot("menu", newState()) { it.veilMenuOpen = true }

    @OptIn(ExperimentalTestApi::class)
    @Test fun menuSections() {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        org.robolectric.Shadows.shadowOf(activityManager).setDeviceConfigurationInfo(
            android.content.pm.ConfigurationInfo().apply { reqGlEsVersion = 0x30000 },
        )
        org.robolectric.Shadows.shadowOf(context as Application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val state = newState()
        state.chooseMusicStyle("viz")
        shoot("menu-overview", state) { it.veilMenuOpen = true }
        fun button(label: String) = compose.onNode(hasText(label) and hasClickAction())
        for (label in listOf("Next photo", "Start Ambient now", "Score alerts")) {
            button(label).assertIsDisplayed()
        }
        button("Next photo").assertIsFocused()
        val settings = mapOf(
            "Wallpaper" to listOf("Change photo every", "Picture depth", "Film grain", "While music plays"),
            "Ambient" to listOf("Starts after", "With music"),
            "Look" to listOf("Theme", "Colours from the photo", "Netflix button", "Interface sounds", "Seasonal touches", "Ad-blocking pill"),
            "Game day" to listOf("Dodgers", "Lakers", "Raiders", "Team wallpapers", "Team visualizer", "Watch games in", "Alert delay"),
            "Weather" to listOf("Weather"),
            "Visualizer" to listOf("Visualizer settings"),
            "Yak" to listOf("The Yak daily", "Yak rewind"),
            "Updates" to listOf("Update ready", "Check for updates", "Install updates"),
        )
        for (section in settings.keys) button(section).assertIsDisplayed()
        // Exercise the conditional settings with local fixture state.
        compose.runOnUiThread {
            state.updates.release = Updates.Release(Updates.Version(0, 1, 0, 20), "https://fixture.invalid/update")
        }
        var previous = "Next photo"
        for ((section, labels) in settings) {
            button(previous).performKeyInput { pressKey(Key.DirectionDown) }
            compose.mainClock.advanceTimeBy(150)
            button(section).assertIsFocused()
            button(section).performKeyInput { pressKey(Key.DirectionCenter) }
            compose.mainClock.advanceTimeBy(150)
            org.junit.Assert.assertTrue("Menu should stay open after entering $section", state.veilMenuOpen)
            button("Back to sections").assertIsFocused()
            for (label in labels) button(label).assertIsDisplayed()
            if (section == "Wallpaper") capture("menu-wallpaper")
            button("Back to sections").performKeyInput { pressKey(Key.Back) }
            compose.mainClock.advanceTimeBy(150)
            button(section).assertIsFocused()
            previous = section
        }
    }

    @Test fun ambient() = shoot("ambient", newState()) { it.startAmbient() }

    @Test fun vizSettings() = shoot("viz-settings", newState()) { it.vizSettingsOpen = true }

    // ---- Game day: a fake source, so previews don't depend on ESPN ----

    private fun gameDay(state: dev.veil.launcher.data.Game.State, ours: Int? = null, theirs: Int? = null, detail: String = "", channel: String? = null) {
        val now = System.currentTimeMillis()
        val start = when (state) {
            dev.veil.launcher.data.Game.State.PRE -> now + 2 * 60 * 60_000L
            dev.veil.launcher.data.Game.State.LIVE -> now - 70 * 60_000L
            dev.veil.launcher.data.Game.State.FINAL -> now - 150 * 60_000L
        }
        val game = dev.veil.launcher.data.Game(
            id = "401910100", team = dev.veil.launcher.data.MyTeams.LAKERS, opponent = "Jazz", opponentAbbr = "UTAH",
            home = true, startMs = start, state = state, detail = detail, ourScore = ours, theirScore = theirs,
            broadcast = "Spectrum Sports Net", channel = channel,
        )
        dev.veil.launcher.data.GameDay.reset()
        dev.veil.launcher.data.GameDay.decorateWithCourtside = false
        dev.veil.launcher.data.GameDay.source = object : dev.veil.launcher.data.GameSource {
            override fun schedule(team: dev.veil.launcher.data.Team) = if (team.key == "lakers") listOf(game) else emptyList()
            override fun refresh(game: dev.veil.launcher.data.Game) = game
        }
        dev.veil.launcher.data.GameDay.tick(context)
    }

    private fun yakItem() = dev.veil.launcher.data.YouTubeShows.toItem(
        dev.veil.launcher.data.YouTubeShows.Episode(
            dev.veil.launcher.data.YouTubeShows.THE_YAK, "ezKrO5NNDuE",
            "Brandon Is Sick And Tired Of Being Ignored In The Yak Group Chat", "11-18-26", System.currentTimeMillis(),
        ),
        isNew = true,
    )

    @Test fun yakCard() = shoot("yak-card", newState()) { s ->
        s.items = listOf(yakItem()) + s.items
        s.focused = s.items.first(); s.focusArea = "cw"
    }

    @Test fun menuUpdate() = shoot("menu-update", newState()) { s ->
        s.updates.release = Updates.Release(Updates.Version(0, 1, 0, 20), "https://example.invalid/ravine.apk")
        s.veilMenuOpen = true
    }

    @Test fun gameTonightCourtside() {
        gameDay(dev.veil.launcher.data.Game.State.PRE, channel = "512 · Spectrum SportsNet LA")
        shoot("game-tonight-courtside", newState())
    }

    // ---- Courtside scores (D49): sample data in Courtside's TV format ----

    private fun team(id: String, abbr: String, name: String, color: String, score: String, result: String? = null) =
        """{"id":"$id","abbr":"$abbr","name":"$name","logo":"https://preview/logo/$abbr-${color.removePrefix("#")}.png","color":"$color","score":"$score","record":"6-4"${result?.let { ",\"result\":\"$it\"" } ?: ""}}"""

    private val scoresJson = """{"games":[
      {"id":"NFL-1","eventId":"1","feed":"NFL","league":"NFL","date":"2026-11-22T21:25Z","state":"in","status":"4:12 - 3rd","favorite":true,"pick":"Close game · Division rivals","situation":"DEN ball, 2nd & 6 at LV 34",
       "channel":{"name":"US: CBS Los Angeles HD","number":"2"},"teams":[${team("13", "LV", "Raiders", "#a5acaf", "17")},${team("7", "DEN", "Broncos", "#fb4f14", "20")}]},
      {"id":"NFL-2","eventId":"2","feed":"NFL","league":"NFL","date":"2026-11-22T21:25Z","state":"in","status":"9:40 - 3rd","favorite":false,
       "channel":{"name":"US: FOX 11 Los Angeles","number":"11"},"teams":[${team("12", "KC", "Chiefs", "#e31837", "24")},${team("24", "LAC", "Chargers", "#0080c6", "10")}]},
      {"id":"NBA-3","eventId":"3","feed":"NBA","league":"NBA","date":"2026-11-23T02:30Z","state":"pre","status":"6:30 PM PST","favorite":true,"pick":"Two top-five records",
       "channel":{"name":"US: Spectrum SportsNet LA FHD","number":"512"},"teams":[${team("13", "LAL", "Lakers", "#fdb927", "")},${team("2", "BOS", "Celtics", "#007a33", "")}]},
      {"id":"NFL-4","eventId":"4","feed":"NFL","league":"NFL","date":"2026-11-23T01:20Z","state":"pre","status":"5:20 PM PST","favorite":false,
       "channel":{"name":"US: NBC 4 Los Angeles","number":"4"},"teams":[${team("17", "NE", "Patriots", "#c60c30", "")},${team("25", "SF", "49ers", "#b3995d", "")}]},
      {"id":"NFL-5","eventId":"5","feed":"NFL","league":"NFL","date":"2026-11-22T18:00Z","state":"post","status":"Final","favorite":false,
       "teams":[${team("3", "CHI", "Bears", "#c83803", "27", "win")},${team("9", "GB", "Packers", "#ffb612", "23", "loss")}]},
      {"id":"NBA-6","eventId":"6","feed":"NBA","league":"NBA","date":"2026-11-23T00:00Z","state":"in","status":"5:02 - 3rd","favorite":false,
       "channel":{"name":"US: NBA TV HD","number":"230"},"teams":[${team("9", "GS", "Warriors", "#3f6fd1", "71")},${team("21", "PHX", "Suns", "#e56020", "64")}]},
      {"id":"NBA-7","eventId":"7","feed":"NBA","league":"NBA","date":"2026-11-22T20:00Z","state":"post","status":"Final","favorite":false,
       "teams":[${team("14", "MIA", "Heat", "#d0304c", "104", "loss")},${team("7", "DEN", "Nuggets", "#fec524", "110", "win")}]}
    ]}"""

    private val gameJson = """{"teamIds":["13","7"],"headline":"",
      "compare":[{"label":"Total Yards","values":["288","311"],"bars":[93,100],"winner":"home"},
        {"label":"Passing","values":["201","174"],"bars":[100,87],"winner":"away"},
        {"label":"Rushing","values":["87","137"],"bars":[64,100],"winner":"home"},
        {"label":"Turnovers","values":["1","0"],"bars":[100,0],"winner":"home"},
        {"label":"Possession","values":["19:02","20:46"]},
        {"label":"1st Downs","values":["16","18"],"bars":[89,100],"winner":"home"}],
      "performers":[{"teamId":"13","label":"Passing Yards","athlete":"G. Smith","line":"17/24, 201 YDS, 1 TD"},
        {"teamId":"13","label":"Rushing Yards","athlete":"A. Jeanty","line":"12 CAR, 61 YDS"},
        {"teamId":"13","label":"Receiving Yards","athlete":"B. Bowers","line":"6 REC, 84 YDS, 1 TD"},
        {"teamId":"7","label":"Passing Yards","athlete":"B. Nix","line":"15/22, 174 YDS, 2 TD"},
        {"teamId":"7","label":"Rushing Yards","athlete":"J. Dobbins","line":"14 CAR, 79 YDS"},
        {"teamId":"7","label":"Receiving Yards","athlete":"C. Sutton","line":"5 REC, 71 YDS, 1 TD"}],
      "moments":[{"teamId":"7","kind":"score","period":"3","clock":"8:51","text":"Bo Nix pass to Courtland Sutton for 22 yds for a TD","score":"17–20"},
        {"teamId":"13","kind":"score","period":"2","clock":"0:04","text":"Daniel Carlson 48 yd field goal","score":"17–13"},
        {"teamId":"7","kind":"turnover","period":"2","clock":"6:10","text":"Geno Smith pass intercepted by Pat Surtain II","score":null},
        {"teamId":"7","kind":"score","period":"2","clock":"9:22","text":"J.K. Dobbins 3 yd run for a TD","score":"14–13"},
        {"teamId":"13","kind":"score","period":"1","clock":"3:30","text":"Geno Smith pass to Brock Bowers for 9 yds for a TD","score":"14–6"}]}"""

    private val standingsJson = """{"league":"NFL","standings":{"season":"2026","groups":[
      {"name":"AFC West","rows":[{"abbr":"DEN","name":"Broncos","record":"8-2","gamesBack":"-","streak":"W3"},{"abbr":"KC","name":"Chiefs","record":"7-3","gamesBack":"1","streak":"W1"},
        {"abbr":"LAC","name":"Chargers","record":"6-4","gamesBack":"2","streak":"L1"},{"abbr":"LV","name":"Raiders","record":"4-6","gamesBack":"4","streak":"L2"}]},
      {"name":"AFC East","rows":[{"abbr":"NE","name":"Patriots","record":"8-3","gamesBack":"-","streak":"W5"},{"abbr":"BUF","name":"Bills","record":"7-3","gamesBack":"0.5","streak":"W2"}]}]}}"""

    private fun scoresState(): LauncherState = newState().also { s ->
        s.scores.forceAvailable = true
        s.scores.fetcher = { path ->
            when {
                path.startsWith("/api/tv/scores") -> scoresJson
                path.startsWith("/api/tv/game") -> gameJson
                path.startsWith("/api/tv/standings") -> standingsJson
                else -> null
            }
        }
    }

    @Test fun scoresButton() = shoot("scores-button", scoresState())
    @Test fun scoresList() = shoot("scores-list", scoresState()) { it.scores.show() }
    @Test fun scoresNfl() = shoot("scores-nfl", scoresState()) { it.scores.show(); it.scores.selectTab("NFL") }
    @Test fun scoresNba() = shoot("scores-nba", scoresState()) { it.scores.show(); it.scores.selectTab("NBA") }
    @Test fun scoresStandings() = shoot("scores-standings", scoresState()) { it.scores.show(); it.scores.selectTab("Standings") }
    @Test fun scoresGame() = shoot("scores-game", scoresState()) { s ->
        s.scores.show()
        s.scores.openGame(dev.veil.launcher.data.CourtsideScores.parseScores(scoresJson).first())
    }

    // ---- Music on Home (D51) ----

    private fun musicState(kind: String?, style: String = "poster"): LauncherState {
        // Seed the artist-picture cache (previews are offline): wide fan art, a square photo, or nothing.
        val pick = when (kind) {
            "wide" -> """{"kind":"wide","urls":["https://preview/wall.jpg"],"at":${System.currentTimeMillis()}}"""
            "square" -> """{"kind":"square","url":"https://preview/lakers.jpg","at":${System.currentTimeMillis()}}"""
            else -> """{"kind":"none","at":${System.currentTimeMillis()}}"""
        }
        context.getSharedPreferences("ravine_artist_art", Context.MODE_PRIVATE).edit()
            .putString(dev.veil.launcher.data.ArtistArt.norm("The Lowland Choir"), pick).commit()
        context.getSharedPreferences("veil", Context.MODE_PRIVATE).edit().putString("music_wallpaper", style).commit()
        val s = newState()
        s.items = emptyList()
        val cover = Bitmap.createScaledBitmap(photo("la.jpg"), 640, 640, true).asImageBitmap()
        s.nowPlaying.track = dev.veil.launcher.data.NowPlaying.Track(
            pkg = "com.spotify.tv.android", title = "Cedar Hollow", artist = "The Lowland Choir", album = "Rivers Under Glass",
            art = cover, artLarge = cover, playing = false, durationMs = 243_000, positionMs = 92_000, positionAt = 0,
        )
        return s
    }

    @Test fun musicPosterWide() = shoot("music-poster-wide", musicState("wide"))
    @Test fun musicPosterSquare() = shoot("music-poster-square", musicState("square"))
    @Test fun musicPosterField() = shoot("music-poster-field", musicState(null))
    @Test fun musicSleeve() = shoot("music-sleeve", musicState(null, "sleeve"))

    /** Score alert chips (D52) drawn over a "video" frame, as they appear over TiviMate. */
    @Test fun alertChips() {
        val frame = photo("la.jpg").copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(frame)
        val alerts = listOf(
            dev.veil.launcher.alerts.ScoreAlerts.Alert("a", "1", true, "TOUCHDOWN · RAIDERS", "Raiders 24 · Broncos 20", 0xFFA5ACAF, "https://preview/logo/LV-a5acaf.png"),
            dev.veil.launcher.alerts.ScoreAlerts.Alert("b", "2", true, "FINAL · LAKERS WIN", "Lakers 118 · Celtics 112", 0xFFFDB927, "https://preview/logo/LAL-fdb927.png"),
            dev.veil.launcher.alerts.ScoreAlerts.Alert("c", "3", false, "UPSET · NFL", "Jets 20 · Bills 17", 0xFF125740, null),
        )
        var y = 52f
        alerts.forEach { a ->
            val v = dev.veil.launcher.alerts.AlertOverlay.chip(context, a)
            v.measure(android.view.View.MeasureSpec.UNSPECIFIED, android.view.View.MeasureSpec.UNSPECIFIED)
            v.layout(0, 0, v.measuredWidth, v.measuredHeight)
            canvas.save(); canvas.translate(frame.width - v.measuredWidth - 64f, y); v.draw(canvas); canvas.restore()
            y += v.measuredHeight + 40f
        }
        outDir.mkdirs()
        File(outDir, "alert-chips.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
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

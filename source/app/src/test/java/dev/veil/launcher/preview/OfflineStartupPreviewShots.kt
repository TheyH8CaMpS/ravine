package dev.veil.launcher.preview

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import coil.annotation.ExperimentalCoilApi
import coil.decode.DataSource
import coil.disk.DiskCache
import coil.fetch.SourceResult
import coil.request.CachePolicy
import coil.request.Options
import dev.veil.launcher.LauncherState
import dev.veil.launcher.RavineApp
import dev.veil.launcher.data.CrashLog
import dev.veil.launcher.data.Http
import dev.veil.launcher.data.LocalHistory
import dev.veil.launcher.data.OfflineImageCache
import dev.veil.launcher.data.WatchItem
import dev.veil.launcher.data.WatchingCache
import dev.veil.launcher.data.Weather
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Runs with the repository's local Robolectric preview runtime. Providers are disabled in every test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = RavineApp::class)
class OfflineStartupPreviewShots {
    private val context: RavineApp get() = ApplicationProvider.getApplicationContext()

    @Before fun disableProviders() { Http.offline = true }
    @After fun restoreProviders() { Http.offline = false }

    @Test fun weatherOnlyUsesACityChosenOnTheDevice() {
        val prefs = context.getSharedPreferences("weather", Context.MODE_PRIVATE)
        prefs.edit().putString("city", "Legacy cached value").putFloat("lat", 1f).putFloat("lon", 2f).apply()
        val weather = Weather(context)
        assertFalse(weather.enabled)
        assertNull(weather.placeName)
        assertNull(weather.current())

        weather.setPlace(Weather.Place("Saved place", "", 1.0, 2.0))
        assertTrue(Weather(context).enabled)
        assertEquals("Saved place", Weather(context).placeName)

        weather.clearPlace()
        assertFalse(Weather(context).enabled)
        assertNull(Weather(context).placeName)
        assertNull(Weather(context).current())
    }

    @Test fun aColdLauncherRestoresTheWatchRowArtworkAndConfiguredScores() = runBlocking {
        val lastPlayed = System.currentTimeMillis()
        val history = LocalHistory.Entry(
            title = "Fixture show", isMovie = false, season = 1, episode = 1,
            positionMs = 90_000, durationMs = 100_000, lastPlayed = lastPlayed, tmdb = 123, imdb = "tt0000123",
        )
        File(context.filesDir, "nuvio_history.json").writeText(LocalHistory.toJson(listOf(history)))
        val cached = WatchItem(
            isMovie = false, title = history.title, traktId = null, imdb = history.imdb, tmdb = history.tmdb,
            season = 1, episode = 2, episodeTitle = "Next episode", upNext = true, sortTime = lastPlayed,
            backdrop = "https://artwork.invalid/backdrop.jpg", thumb = "https://artwork.invalid/thumb.jpg",
        )
        WatchingCache(context.getSharedPreferences("ravine_watching", Context.MODE_PRIVATE)).save(listOf(cached))
        context.getSharedPreferences("ravine_courtside", Context.MODE_PRIVATE).edit()
            .putString("url", "https://courtside.invalid").putString("tv", "fixture").apply()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val cold = LauncherState(context, scope)
            assertEquals(listOf(cached), cold.items)
            assertTrue(cold.scores.available)
            cold.refreshWatching(force = true)
            withTimeout(5_000) { while (cold.loading) delay(10) }
            assertEquals(listOf(cached), cold.items)
            assertEquals(listOf(cached), LauncherState(context, scope).items)
        } finally {
            scope.cancel()
        }
    }

    @Test fun aFailedLaunchIsRecordedAndAnotherLaunchStillRuns() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + CrashLog.coroutineHandler(context))
        try {
            scope.launch { throw IllegalStateException("private-message-fixture") }.join()
            assertTrue(scope.isActive)
            assertEquals(42, scope.async { 42 }.await())
            val log = File(checkNotNull(context.getExternalFilesDir(null)), "ravine-crashes.txt").readText()
            assertTrue(log.contains("RECOVERED COROUTINE FAILURE"))
            assertTrue(log.contains("IllegalStateException"))
            assertFalse(log.contains("private-message-fixture"))
        } finally {
            scope.cancel()
        }
    }

    @OptIn(ExperimentalCoilApi::class)
    @Test fun diskArtworkCanBeReadOfflineWithoutRevalidatingItsHeaders() = runBlocking {
        val disk = DiskCache.Builder().directory(File(context.cacheDir, "offline-images")).build()
        val appLoader = context.newImageLoader()
        val loader = appLoader.newBuilder().diskCache(disk).build()
        try {
            val image = Uri.parse("https://artwork.invalid/wallpaper.jpg")
            val bytes = javaClass.getResourceAsStream("/preview/wall.jpg")!!.use { it.readBytes() }
            val editor = checkNotNull(disk.openEditor(image.toString()))
            disk.fileSystem.write(editor.metadata) { writeUtf8("Cache-Control: max-age=0, must-revalidate") }
            disk.fileSystem.write(editor.data) { write(bytes) }
            editor.commit()

            assertNull(OfflineImageCache.create(image, Options(context), loader))
            val offline = Options(context, networkCachePolicy = CachePolicy.DISABLED)
            val (fetcher, index) = checkNotNull(loader.components.newFetcher(image, offline, loader))
            assertSame(OfflineImageCache, loader.components.fetcherFactories[index].first)
            val result = fetcher.fetch() as SourceResult
            assertEquals(DataSource.DISK, result.dataSource)
            result.source.use { assertArrayEquals(bytes, it.source().readByteArray()) }
            val missing = Uri.parse("https://artwork.invalid/missing.jpg")
            assertNull(OfflineImageCache.create(missing, offline, loader)?.fetch())
        } finally {
            loader.shutdown()
            appLoader.shutdown()
        }
    }
}

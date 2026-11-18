package dev.veil.launcher

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import okhttp3.OkHttpClient

/**
 * Ravine's app-wide image loader.
 *
 * - Sends a descriptive User-Agent. Wikimedia (Dodgers/Raiders/Lakers/Sequoia photos in the wallpaper feed)
 *   answers 403 to generic HTTP clients and serves only clients that identify themselves.
 * - A larger memory cache: the Shield has 3 GB, so the current and next wallpaper, the show artwork and the
 *   pill thumbnails all stay decoded (instant crossfades). Coil's default disk cache keeps working offline.
 */
class RavineApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient {
            OkHttpClient.Builder()
                .addNetworkInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
                }
                .build()
        }
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.3).build() }
        .build()

    companion object {
        const val USER_AGENT = "Ravine/0.1 (Android TV home screen; https://github.com/TheyH8CaMpS/ravine)"
    }
}

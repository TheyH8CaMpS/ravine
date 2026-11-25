package dev.veil.launcher.data

import android.net.Uri
import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options

/** Reuse a disk image even when its HTTP headers require revalidation, if Coil can't use the network. */
@OptIn(ExperimentalCoilApi::class)
internal object OfflineImageCache : Fetcher.Factory<Uri> {
    override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
        if (data.scheme != "http" && data.scheme != "https") return null
        if (options.networkCachePolicy.readEnabled || !options.diskCachePolicy.readEnabled) return null
        val cache = imageLoader.diskCache ?: return null
        val key = options.diskCacheKey ?: data.toString()
        return Fetcher {
            val snapshot = cache.openSnapshot(key) ?: return@Fetcher null
            SourceResult(
                source = ImageSource(snapshot.data, cache.fileSystem, key, snapshot),
                mimeType = null,
                dataSource = DataSource.DISK,
            )
        }
    }
}

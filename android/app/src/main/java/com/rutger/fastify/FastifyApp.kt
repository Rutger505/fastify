package com.rutger.fastify

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import okio.Path.Companion.toOkioPath

class FastifyApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.warmUp()
    }

    // Artwork is cache-first too: 256 MB on disk, so covers render without network on launch.
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.2).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("images").toOkioPath())
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .build()
}

val Context.container: AppContainer
    get() = (applicationContext as FastifyApp).container

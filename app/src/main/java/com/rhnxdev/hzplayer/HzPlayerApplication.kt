package com.rhnxdev.hzplayer

import android.app.Application
import coil3.ImageLoader
import java.io.File
import coil3.PlatformContext
import coil3.request.crossfade
import coil3.SingletonImageLoader
import com.rhnxdev.hzplayer.core.thumbnail.VideoFrameFetcher
import com.rhnxdev.hzplayer.core.thumbnail.VideoFrameKeyer
import com.rhnxdev.hzplayer.data.datasource.player.ConnectionPool
import dagger.hilt.android.HiltAndroidApp
import okio.Path.Companion.toPath

@HiltAndroidApp
class HzPlayerApplication : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        // App-private store for the SFTP TOFU known-hosts verifier.
        ConnectionPool.sftpKnownHostsFile = File(filesDir, "sftp_known_hosts")
    }

    override fun onTerminate() {
        super.onTerminate()
        ConnectionPool.shutdown()
        ConnectionPool.releaseAll()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .crossfade(true)
            .memoryCache {
                coil3.memory.MemoryCache.Builder()
                    .maxSizePercent(context, 0.20)
                    .build()
            }
            .diskCache {
                coil3.disk.DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache").absolutePath.toPath())
                    .maxSizePercent(0.02)
                    .build()
            }
            .components {
                add(VideoFrameKeyer())
                add(VideoFrameFetcher.Factory())
            }
            .build()
}

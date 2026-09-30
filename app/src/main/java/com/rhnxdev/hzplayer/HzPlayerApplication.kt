package com.rhnxdev.hzplayer

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.util.Log
import coil3.ImageLoader
import java.io.File
import coil3.PlatformContext
import coil3.request.crossfade
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.rhnxdev.hzplayer.core.thumbnail.THUMB_MAX_AGE_MS
import com.rhnxdev.hzplayer.core.thumbnail.VideoFrameFetcher
import com.rhnxdev.hzplayer.core.thumbnail.VideoFrameKeyer
import com.rhnxdev.hzplayer.core.thumbnail.pruneOldThumbnails
import com.rhnxdev.hzplayer.data.datasource.player.ConnectionPool
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okio.Path.Companion.toPath

@HiltAndroidApp
class HzPlayerApplication : Application(), SingletonImageLoader.Factory {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate — app init")
        ConnectionPool.sftpKnownHostsFile = File(filesDir, "sftp_known_hosts")

        // Fire-and-forget: prune stale thumbnails so video_thumbs stays bounded.
        // Wrapped so any failure is logged and never affects startup.
        appScope.launch {
            runCatching {
                val pruned = pruneOldThumbnails(File(cacheDir, "video_thumbs"), THUMB_MAX_AGE_MS)
                if (pruned > 0) Log.i(TAG, "pruned $pruned stale thumbnail(s)")
            }.onFailure { Log.w(TAG, "thumbnail prune failed", it) }
        }
    }

    override fun onTerminate() {
        Log.i(TAG, "onTerminate — releasing resources")
        super.onTerminate()
        ConnectionPool.shutdown()
        ConnectionPool.releaseAll()
    }

    companion object {
        private const val TAG = "HzPlayerApplication"
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        // ponytail: decoded Bitmaps are the single biggest memory sink on low-end
        // SoCs; but too-small a cache forces constant WebP re-decodes from disk
        // which makes the file-browser thumbnail grid feel sluggish on revisit.
        // 15% keeps ~80+ 720p thumbnails resident — enough for several folders.
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memPercent = if (am.isLowRamDevice) 0.08 else 0.15
        return ImageLoader.Builder(context)
            .crossfade(true)
            .memoryCache {
                coil3.memory.MemoryCache.Builder()
                    .maxSizePercent(context, memPercent)
                    .build()
            }
            .diskCache {
                coil3.disk.DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache").absolutePath.toPath())
                    .maxSizePercent(0.04)
                    .build()
            }
            .components {
                // Network fetcher for HTTP/HTTPS poster images (e.g. SubDL posters)
                add(OkHttpNetworkFetcherFactory())
                add(VideoFrameKeyer())
                add(VideoFrameFetcher.Factory())
            }
            .build()
    }
}

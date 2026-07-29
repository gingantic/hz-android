package com.rhnxdev.hzplayer.data.datasource.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import com.rhnxdev.hzplayer.browser.media.MediaStreamDecoder
import java.util.Locale

/**
 * Result of [rebuildPlaylistForSubtitleSwap]: rebuilt items + replay index/position.
 */
internal data class PlaylistRebuild(
    val items: List<MediaItem>,
    val startIndex: Int,
    val startPositionMs: Long,
)

/**
 * Helper object providing MediaItem building, subtitle MIME-type inference,
 * and playlist rebuilding utilities for ExoPlayer.
 */
@OptIn(UnstableApi::class)
internal object ExoMediaItemHelper {

    fun buildMediaItemWithSubtitles(
        uri: String,
        title: String,
        artist: String? = null,
        mimeType: String? = null,
        subs: List<MediaItem.SubtitleConfiguration> = emptyList(),
        artworkUri: String? = null,
    ): MediaItem {
        val builder = MediaItem.Builder()
        builder.setUri(uri)

        // Infer explicit MIME type for HLS / DASH or custom probe to avoid container sniffing failures
        val lowerUri = uri.lowercase(Locale.ROOT)
        val lowerMime = mimeType?.lowercase(Locale.ROOT) ?: ""
        val isDisguisedHls = MediaStreamDecoder.isDisguisedHlsStream(uri, lowerMime)
        val uriPath = lowerUri.substringBefore('?').substringBefore('#')
        val isExplicitHls = isDisguisedHls || uriPath.endsWith(".m3u8") || lowerMime.contains("mpegurl") || lowerMime.contains("m3u8")
        val isExplicitDash = uriPath.endsWith(".mpd") || lowerMime.contains("dash")

        val effectiveMimeType = when {
            !mimeType.isNullOrBlank() && mimeType != "video/*" && mimeType != "*/*" -> mimeType
            isExplicitHls -> MimeTypes.APPLICATION_M3U8
            isExplicitDash -> MimeTypes.APPLICATION_MPD
            else -> mimeType
        }
        if (!effectiveMimeType.isNullOrBlank()) {
            builder.setMimeType(effectiveMimeType)
        }

        builder.setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                // Album art for the system MediaSession notification / lock screen.
                .apply { artworkUri?.let { setArtworkUri(Uri.parse(it)) } }
                .build(),
        )

        // Subtitles are scoped per-media-item: global subtitleConfigs must not
        // be applied to every playlist entry indiscriminately.
        if (subs.isNotEmpty()) {
            builder.setSubtitleConfigurations(subs)
        }
        return builder.build()
    }

    /** Read a subtitle file's bytes, handling local (content/file) and remote URIs. */
    fun readSubtitleUriBytes(context: Context, playerHolder: MediaPlayerHolder, uri: Uri): ByteArray? = runCatching {
        when (uri.scheme?.lowercase(Locale.ROOT)) {
            "content", "file" -> context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            else -> playerHolder.readUriBytes(uri)
        }
    }.getOrNull()

    /** Map subtitle file extension to MIME type. */
    fun inferSubtitleMimeType(uri: Uri): String {
        val ext = uri.path?.substringAfterLast('.')?.lowercase(Locale.ROOT) ?: return MimeTypes.APPLICATION_SUBRIP
        return when (ext) {
            "vtt" -> MimeTypes.TEXT_VTT
            "srt" -> MimeTypes.APPLICATION_SUBRIP
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "sub" -> MimeTypes.APPLICATION_SUBRIP
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }
}

/**
 * Rebuild a media-item list so the current item carries new [subtitleConfigs],
 * keeping every other item and the active [currentIndex]/[startPositionMs].
 *
 * Extracted pure function so playlist subtitle swap regressions are unit-testable.
 */
internal fun rebuildPlaylistForSubtitleSwap(
    playlist: List<MediaItem>?,
    currentUri: String,
    currentTitle: String,
    currentIndex: Int,
    startPositionMs: Long,
    subtitleConfigs: List<MediaItem.SubtitleConfiguration>,
    buildItem: (String, String, List<MediaItem.SubtitleConfiguration>) -> MediaItem,
): PlaylistRebuild {
    val items = playlist?.mapIndexed { idx, item ->
        if (idx == currentIndex) {
            buildItem(currentUri, currentTitle, subtitleConfigs)
        } else {
            item
        }
    } ?: listOf(buildItem(currentUri, currentTitle, subtitleConfigs))
    return PlaylistRebuild(items, currentIndex, startPositionMs)
}

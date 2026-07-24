package com.rhnxdev.hzplayer.core.components

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.rhnxdev.hzplayer.R
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.core.thumbnail.MediaInfoProbe
import com.rhnxdev.hzplayer.core.thumbnail.VideoFrame
import com.rhnxdev.hzplayer.core.util.isAudioExtension
import com.rhnxdev.hzplayer.core.util.isVideoExtension
import com.rhnxdev.hzplayer.domain.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Modern, clean Bottom Modal Sheet presenting file and media properties tailored
 * specifically per file type (Video, Audio, Folder/Other) with MediaStore / ID3 cover art.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MediaPropertiesDialog(
    title: String,
    properties: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    probeUri: String? = null,
    albumArtUri: String? = null,
    thumbnailContent: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    val context = LocalContext.current

    var codecInfo by remember(probeUri) { mutableStateOf<Map<String, String>?>(null) }
    var probing by remember(probeUri) { mutableStateOf(probeUri != null) }
    var songArtworkModel by remember(probeUri) { mutableStateOf<Any?>(null) }

    val propMap = remember(properties) { properties.toMap() }

    // Quick spec values
    val path = propMap[stringResource(R.string.prop_path)]
    val size = propMap[stringResource(R.string.prop_size)]
    val resolution = propMap[stringResource(R.string.prop_resolution)]
    val duration = propMap[stringResource(R.string.prop_duration)]
    val mimeType = propMap[stringResource(R.string.prop_type)]
    val dateModified = propMap[stringResource(R.string.prop_date_modified)]

    val isDirectory = mimeType == null && size == null && duration == null
    val isAudioFile = mimeType?.startsWith("audio") == true || isAudioExtension(title)
    val isVideoFile = (mimeType?.startsWith("video") == true || isVideoExtension(title)) && !isAudioFile

    LaunchedEffect(probeUri) {
        val uriStr = probeUri
        if (uriStr != null) {
            withContext(Dispatchers.IO) {
                // 1. Resolve Audio Album Artwork via MediaStore (matching Music tab)
                if (thumbnailContent == null && albumArtUri.isNullOrBlank() && (isAudioFile || mimeType?.startsWith("audio") == true || isAudioExtension(title))) {
                    var foundArtUri: String? = null
                    try {
                        val projection = arrayOf(MediaStore.Audio.Media.ALBUM_ID)
                        val selection = "${MediaStore.Audio.Media.DATA} = ? OR ${MediaStore.Audio.Media._ID} = ?"
                        val rawId = uriStr.substringAfterLast('/')
                        val selectionArgs = arrayOf(uriStr, rawId)
                        context.contentResolver.query(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            projection,
                            selection,
                            selectionArgs,
                            null
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val albumIdIdx = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                                if (albumIdIdx >= 0) {
                                    val albumId = cursor.getLong(albumIdIdx)
                                    if (albumId > 0) {
                                        foundArtUri = "content://media/external/audio/albumart/$albumId"
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}

                    if (!foundArtUri.isNullOrBlank()) {
                        songArtworkModel = foundArtUri
                    } else {
                        // Fallback: extract embedded picture from ID3 tags using FileDescriptor
                        try {
                            val mmr = MediaMetadataRetriever()
                            val uri = Uri.parse(uriStr)
                            when {
                                uriStr.startsWith("content://") -> {
                                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                                        mmr.setDataSource(pfd.fileDescriptor)
                                    }
                                }
                                uriStr.startsWith("file://") -> {
                                    mmr.setDataSource(uri.path)
                                }
                                else -> {
                                    mmr.setDataSource(uriStr)
                                }
                            }
                            val bytes = mmr.embeddedPicture
                            mmr.release()
                            if (bytes != null && bytes.isNotEmpty()) {
                                songArtworkModel = bytes
                            }
                        } catch (_: Exception) {}
                    }
                }

                // Async FFmpeg metadata probing
                codecInfo = MediaInfoProbe.probe(context, uriStr)
            }
        }
        probing = false
    }

    val container = codecInfo?.get("format_long") ?: codecInfo?.get("format")
    val overallBitrate = codecInfo?.get("bitrate")?.toLongOrNull()?.let { formatBitrate(it) }

    val videoCodecRaw = codecInfo?.get("video_codec")
    val videoProfile = codecInfo?.get("video_profile")
    val videoCodec = if (videoCodecRaw != null && videoProfile != null) "$videoCodecRaw ($videoProfile)" else videoCodecRaw
    val videoFps = codecInfo?.get("video_fps")?.let { "$it fps" }
    val videoBitrate = codecInfo?.get("video_bitrate")?.toLongOrNull()?.let { formatBitrate(it) }
    val videoPixFmt = codecInfo?.get("video_pix_fmt")

    val audioCodec = codecInfo?.get("audio_codec")
    val audioBitrate = codecInfo?.get("audio_bitrate")?.toLongOrNull()?.let { formatBitrate(it) }
    val audioSampleRate = codecInfo?.get("audio_sample_rate")?.let { formatSampleRate(it) }
    val audioChannels = codecInfo?.get("audio_channels")?.toIntOrNull()?.let { formatChannels(it) }

    // Strict classification: Ignore cover art mjpeg/png picture streams on audio files
    val isActualVideoCodec = videoCodecRaw != null && videoCodecRaw != "mjpeg" && videoCodecRaw != "png" && videoCodecRaw != "bmp"
    val isRealVideo = isVideoFile || (resolution != null && !isAudioFile) || (isActualVideoCodec && !isAudioFile)
    val isRealAudio = isAudioFile || (!isRealVideo && audioCodec != null)

    val hasThumbnailBanner = thumbnailContent != null || !albumArtUri.isNullOrBlank() || songArtworkModel != null || (!probeUri.isNullOrBlank() && !isDirectory)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        dragHandle = {
            if (!hasThumbnailBanner) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(4.dp)
                            .background(
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                                RoundedCornerShape(2.dp)
                            )
                    )
                }
            }
        },
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            // Top Cover Art / Thumbnail Banner with Vertical Gradient Overlay
            if (hasThumbnailBanner) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                ) {
                    if (thumbnailContent != null) {
                        thumbnailContent()
                    } else if (songArtworkModel != null) {
                        SubcomposeAsyncImage(
                            model = songArtworkModel,
                            contentDescription = title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            error = {
                                ThumbnailPlaceholder(mediaType = MediaType.AUDIO)
                            }
                        )
                    } else if (!albumArtUri.isNullOrBlank()) {
                        SubcomposeAsyncImage(
                            model = albumArtUri,
                            contentDescription = title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            error = {
                                ThumbnailPlaceholder(mediaType = MediaType.AUDIO)
                            }
                        )
                    } else if (!probeUri.isNullOrBlank()) {
                        SubcomposeAsyncImage(
                            model = if (isRealVideo) VideoFrame(probeUri, 0L) else probeUri,
                            contentDescription = title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            error = {
                                ThumbnailPlaceholder(mediaType = if (isRealAudio) MediaType.AUDIO else MediaType.VIDEO)
                            }
                        )
                    }

                    // Gradient Blend: Fades into sheet container color at bottom
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Black.copy(alpha = 0.4f),
                                        Color.Transparent,
                                        MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.7f),
                                        MaterialTheme.colorScheme.surfaceContainerLow
                                    )
                                )
                            )
                    )

                    // Drag Handle overlaid on top of cover art
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(36.dp)
                                .height(4.dp)
                                .background(
                                    Color.White.copy(alpha = 0.4f),
                                    RoundedCornerShape(2.dp)
                                )
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md)
                    .padding(bottom = Spacing.lg)
            ) {
                // Header Icon & Title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        shape = RoundedCornerShape(Spacing.sm),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            val icon: ImageVector = when {
                                isDirectory -> Icons.Default.Folder
                                isRealVideo -> Icons.Default.Movie
                                isRealAudio -> Icons.Default.Audiotrack
                                else -> Icons.AutoMirrored.Filled.InsertDriveFile
                            }
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(Spacing.md))

                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.sm))

                // Quick Spec Chips (Tailored by File Type)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val extension = title.substringAfterLast('.', "").uppercase()
                    if (extension.isNotEmpty() && extension.length <= 5) {
                        SpecChip(label = extension, containerColor = MaterialTheme.colorScheme.primaryContainer)
                    }
                    if (isRealVideo) {
                        if (!resolution.isNullOrBlank()) {
                            val qualityBadge = when {
                                resolution.contains("3840") || resolution.contains("2160") -> "4K"
                                resolution.contains("1920") || resolution.contains("1080") -> "1080p"
                                resolution.contains("1280") || resolution.contains("720") -> "720p"
                                else -> resolution
                            }
                            SpecChip(label = qualityBadge)
                        }
                    } else if (isRealAudio) {
                        val audioBadge = audioCodec ?: container
                        if (!audioBadge.isNullOrBlank() && !audioBadge.equals(extension, ignoreCase = true) && (audioCodec == null || !audioCodec.equals(extension, ignoreCase = true))) {
                            SpecChip(label = audioBadge.uppercase())
                        }
                        if (!audioSampleRate.isNullOrBlank()) {
                            SpecChip(label = audioSampleRate)
                        }
                    }
                    if (!size.isNullOrBlank()) {
                        SpecChip(label = size)
                    }
                    if (!duration.isNullOrBlank()) {
                        SpecChip(label = duration)
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.md))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(modifier = Modifier.height(Spacing.md))

                // Section: File Info
                SectionHeader(title = stringResource(R.string.prop_file_name))

                if (!path.isNullOrBlank()) {
                    SpecTileFullWidth(label = stringResource(R.string.prop_path), value = path)
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (!size.isNullOrBlank()) {
                        SpecTile(
                            label = stringResource(R.string.prop_size),
                            value = size,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (!mimeType.isNullOrBlank()) {
                        SpecTile(
                            label = stringResource(R.string.prop_type),
                            value = mimeType,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                if (!dateModified.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    SpecTileFullWidth(label = stringResource(R.string.prop_date_modified), value = dateModified)
                }

                // Section: Video Stream (Only for Video files, suppressed on Audio)
                if (isRealVideo && (videoCodec != null || resolution != null || container != null || overallBitrate != null)) {
                    Spacer(modifier = Modifier.height(Spacing.md))
                    SectionHeader(title = "Video Stream")

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val codecVal = videoCodec ?: container ?: "-"
                        SpecTile(
                            label = stringResource(R.string.prop_video_codec),
                            value = codecVal,
                            modifier = Modifier.weight(1f)
                        )
                        val resFpsVal = buildString {
                            if (!resolution.isNullOrBlank()) append(resolution)
                            if (!videoFps.isNullOrBlank()) {
                                if (isNotEmpty()) append(" @ ")
                                append(videoFps)
                            }
                        }.ifEmpty { "-" }
                        SpecTile(
                            label = stringResource(R.string.prop_resolution),
                            value = resFpsVal,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (videoBitrate != null || overallBitrate != null || videoPixFmt != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            videoBitrate?.let {
                                SpecTile(
                                    label = stringResource(R.string.prop_video_bitrate),
                                    value = it,
                                    modifier = Modifier.weight(1f)
                                )
                            } ?: overallBitrate?.let {
                                SpecTile(
                                    label = stringResource(R.string.prop_overall_bitrate),
                                    value = it,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (!videoPixFmt.isNullOrBlank()) {
                                SpecTile(
                                    label = stringResource(R.string.prop_pixel_format),
                                    value = videoPixFmt,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                // Section: Audio Stream / Audio Format (For Audio files or Video with Audio stream)
                if (isRealAudio || audioCodec != null || audioBitrate != null || audioSampleRate != null || audioChannels != null) {
                    Spacer(modifier = Modifier.height(Spacing.md))
                    SectionHeader(title = if (isRealAudio) "Audio Format" else "Audio Stream")

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val codecDisplay = audioCodec ?: container ?: "-"
                        SpecTile(
                            label = stringResource(R.string.prop_audio_codec),
                            value = codecDisplay,
                            modifier = Modifier.weight(1f)
                        )
                        SpecTile(
                            label = stringResource(R.string.prop_channels),
                            value = audioChannels ?: "-",
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (audioSampleRate != null || audioBitrate != null || (isRealAudio && overallBitrate != null)) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (audioSampleRate != null) {
                                SpecTile(
                                    label = stringResource(R.string.prop_sample_rate),
                                    value = audioSampleRate,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            val bitRateVal = audioBitrate ?: if (isRealAudio) overallBitrate else null
                            if (bitRateVal != null) {
                                SpecTile(
                                    label = stringResource(R.string.prop_audio_bitrate),
                                    value = bitRateVal,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                // Loading / Probing State Indicator
                if (probing) {
                    Spacer(modifier = Modifier.height(Spacing.md))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.prop_loading_codecs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@Composable
private fun SpecChip(
    label: String,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = containerColor,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun SpecTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(Spacing.xs),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SpecTileFullWidth(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(Spacing.xs),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun formatBitrate(bps: Long): String = when {
    bps >= 1_000_000 -> String.format("%.2f Mbps", bps / 1_000_000.0)
    bps >= 1_000 -> String.format("%.0f kbps", bps / 1_000.0)
    else -> "$bps bps"
}

private fun formatSampleRate(hz: String): String {
    val n = hz.toIntOrNull() ?: return "$hz Hz"
    return if (n % 1000 == 0) "${n / 1000} kHz" else "$n Hz"
}

private fun formatChannels(count: Int): String = when (count) {
    1 -> "Mono"
    2 -> "Stereo (2 ch)"
    6 -> "5.1 Surround"
    8 -> "7.1 Surround"
    else -> "$count ch"
}

package com.rhnxdev.hzplayer.core.components

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * Modern Bottom Modal Sheet presenting file and media properties with a top
 * cover/thumbnail banner preview and compact, high-density grouped metadata cards.
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

    val rawFormat = codecInfo?.get("format")
    val rawFormatLong = codecInfo?.get("format_long")
    val container = formatContainerName(rawFormat, rawFormatLong, mimeType, title)
    val overallBitrate = codecInfo?.get("bitrate")?.toLongOrNull()?.let { formatBitrate(it) }

    val videoCodecRaw = codecInfo?.get("video_codec")
    val videoProfile = codecInfo?.get("video_profile")
    val videoCodec = when {
        videoCodecRaw != null && videoProfile != null -> "$videoCodecRaw ($videoProfile)"
        videoCodecRaw != null -> videoCodecRaw
        else -> null
    }
    val probedResolution = codecInfo?.get("video_resolution")
    val effectiveResolution = probedResolution ?: resolution
    val formattedResolution = formatResolution(effectiveResolution)
    val videoFps = codecInfo?.get("video_fps")?.let { "$it fps" }
    val videoBitrate = codecInfo?.get("video_bitrate")?.toLongOrNull()?.let { formatBitrate(it) }
    val videoPixFmt = codecInfo?.get("video_pix_fmt")
    val videoBitDepth = codecInfo?.get("video_bit_depth")
    val videoHdr = codecInfo?.get("video_hdr")
    val videoTracks = codecInfo?.get("video_tracks")?.toIntOrNull()
    val audioTracks = codecInfo?.get("audio_tracks")?.toIntOrNull()
    val subTracks = codecInfo?.get("subtitle_tracks")?.toIntOrNull()

    val audioCodecRaw = codecInfo?.get("audio_codec")
    val audioCodecLong = codecInfo?.get("audio_codec_long")
    val audioCodec = audioCodecRaw ?: audioCodecLong
    val audioBitrate = codecInfo?.get("audio_bitrate")?.toLongOrNull()?.let { formatBitrate(it) }
    val audioSampleRate = codecInfo?.get("audio_sample_rate")?.let { formatSampleRate(it) }
    val audioChannels = codecInfo?.get("audio_channels")?.toIntOrNull()
    val audioLayout = codecInfo?.get("audio_layout")
    val audioLanguage = codecInfo?.get("audio_language")
    val formattedChannels = audioChannels?.let { formatChannels(it, audioLayout) }

    // Strict classification: Ignore cover art mjpeg/png picture streams on audio files
    val isActualVideoCodec = videoCodecRaw != null && videoCodecRaw != "mjpeg" && videoCodecRaw != "png" && videoCodecRaw != "bmp"
    val isRealVideo = isVideoFile || (effectiveResolution != null && !isAudioFile) || (isActualVideoCodec && !isAudioFile)
    val isRealAudio = isAudioFile || (!isRealVideo && audioCodec != null)

    val hasThumbnailBanner = thumbnailContent != null || !albumArtUri.isNullOrBlank() || songArtworkModel != null || (!probeUri.isNullOrBlank() && !isDirectory)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        tonalElevation = 0.dp,
        dragHandle = {
            if (!hasThumbnailBanner) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(32.dp)
                            .height(4.dp)
                            .background(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
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
            // ── Top Cover Art / Thumbnail Banner with Vertical Gradient Overlay ──
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
                            .padding(top = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(32.dp)
                                .height(4.dp)
                                .background(
                                    Color.White.copy(alpha = 0.45f),
                                    RoundedCornerShape(2.dp)
                                )
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                // Header Icon & Title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        modifier = Modifier.size(40.dp)
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
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.2).sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Quick Spec Chips
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val extension = title.substringAfterLast('.', "").uppercase()
                    if (extension.isNotEmpty() && extension.length <= 5) {
                        SpecChip(label = extension, containerColor = MaterialTheme.colorScheme.primaryContainer)
                    }
                    if (isRealVideo) {
                        if (!effectiveResolution.isNullOrBlank()) {
                            val qualityBadge = when {
                                effectiveResolution.contains("3840") || effectiveResolution.contains("2160") -> "4K"
                                effectiveResolution.contains("1920") || effectiveResolution.contains("1080") -> "1080p"
                                effectiveResolution.contains("1280") || effectiveResolution.contains("720") -> "720p"
                                else -> null
                            }
                            if (qualityBadge != null) {
                                SpecChip(label = qualityBadge)
                            }
                        }
                        if (!videoHdr.isNullOrBlank()) {
                            SpecChip(label = videoHdr, containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                        }
                    } else if (isRealAudio) {
                        val audioBadge = audioCodec ?: container
                        if (!audioBadge.isNullOrBlank() && !audioBadge.equals(extension, ignoreCase = true)) {
                            SpecChip(label = audioBadge.uppercase())
                        }
                    }
                    if (!size.isNullOrBlank()) {
                        SpecChip(label = size)
                    }
                    if (!duration.isNullOrBlank()) {
                        SpecChip(label = duration)
                    }
                }

                // Probing indicator (Sleek 2dp line)
                if (probing) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.Transparent
                    )
                } else {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                }

                // ── Card 1: Video Stream (if Video) ──
                if (isRealVideo && (videoCodec != null || effectiveResolution != null || container != null || overallBitrate != null || videoFps != null)) {
                    PropCard(
                        title = stringResource(R.string.prop_section_video),
                        icon = Icons.Default.Movie
                    ) {
                        val codecVal = videoCodec ?: container ?: "-"
                        val resVal = formattedResolution ?: effectiveResolution ?: "-"
                        PropRow2(
                            label1 = stringResource(R.string.prop_video_codec),
                            value1 = codecVal,
                            label2 = stringResource(R.string.prop_resolution),
                            value2 = resVal
                        )

                        val fpsVal = videoFps ?: "-"
                        val bitVal = videoBitrate ?: overallBitrate ?: "-"
                        PropRow2(
                            label1 = stringResource(R.string.prop_frame_rate),
                            value1 = fpsVal,
                            label2 = stringResource(R.string.prop_video_bitrate),
                            value2 = bitVal
                        )

                        val colorFormatVal = buildString {
                            if (!videoBitDepth.isNullOrBlank()) append(videoBitDepth)
                            if (!videoHdr.isNullOrBlank()) {
                                if (isNotEmpty()) append(" ")
                                append(videoHdr)
                            }
                            if (!videoPixFmt.isNullOrBlank()) {
                                if (isNotEmpty()) append(" • ")
                                append(videoPixFmt)
                            }
                        }.ifEmpty { null }

                        val tracksVal = buildString {
                            if (videoTracks != null && videoTracks > 0) append("${videoTracks}V")
                            if (audioTracks != null && audioTracks > 0) {
                                if (isNotEmpty()) append(" • ")
                                append("${audioTracks}A")
                            }
                            if (subTracks != null && subTracks > 0) {
                                if (isNotEmpty()) append(" • ")
                                append("${subTracks}S")
                            }
                        }.ifEmpty { null }

                        if (colorFormatVal != null || tracksVal != null) {
                            PropRow2(
                                label1 = stringResource(R.string.prop_color_depth),
                                value1 = colorFormatVal ?: "-",
                                label2 = if (tracksVal != null) stringResource(R.string.prop_tracks) else null,
                                value2 = tracksVal
                            )
                        }
                    }
                }

                // ── Card 2: Audio Stream / Audio Format ──
                if (isRealAudio || audioCodec != null || audioBitrate != null || audioSampleRate != null || audioChannels != null) {
                    PropCard(
                        title = if (isRealAudio) stringResource(R.string.prop_section_audio_format) else stringResource(R.string.prop_section_audio),
                        icon = Icons.Default.Audiotrack
                    ) {
                        val codecDisplay = audioCodec ?: container ?: "-"
                        val chanDisplay = formattedChannels ?: "-"
                        PropRow2(
                            label1 = stringResource(R.string.prop_audio_codec),
                            value1 = codecDisplay,
                            label2 = stringResource(R.string.prop_channels),
                            value2 = chanDisplay
                        )

                        val sampleDisplay = audioSampleRate ?: "-"
                        val bitDisplay = audioBitrate ?: if (isRealAudio) overallBitrate ?: "-" else "-"
                        PropRow2(
                            label1 = stringResource(R.string.prop_sample_rate),
                            value1 = sampleDisplay,
                            label2 = stringResource(R.string.prop_audio_bitrate),
                            value2 = bitDisplay
                        )

                        if (!audioLanguage.isNullOrBlank() || !audioLayout.isNullOrBlank()) {
                            PropRow2(
                                label1 = stringResource(R.string.prop_language),
                                value1 = audioLanguage?.uppercase() ?: "-",
                                label2 = if (!audioLayout.isNullOrBlank()) stringResource(R.string.prop_layout) else null,
                                value2 = audioLayout
                            )
                        }
                    }
                }

                // ── Card 3: File Details ──
                PropCard(
                    title = stringResource(R.string.prop_section_file),
                    icon = Icons.Default.Info
                ) {
                    val containerDisplay = container ?: mimeType ?: "-"
                    val sizeDisplay = size ?: "-"
                    PropRow2(
                        label1 = stringResource(R.string.prop_container),
                        value1 = containerDisplay,
                        label2 = stringResource(R.string.prop_size),
                        value2 = sizeDisplay
                    )

                    val durDisplay = duration ?: "-"
                    val modDisplay = dateModified ?: "-"
                    PropRow2(
                        label1 = stringResource(R.string.prop_duration),
                        value1 = durDisplay,
                        label2 = stringResource(R.string.prop_date_modified),
                        value2 = modDisplay
                    )

                    if (!path.isNullOrBlank()) {
                        val clipboardManager = LocalClipboardManager.current
                        val copiedMsg = stringResource(R.string.prop_copied_path)
                        PropPathRow(
                            label = stringResource(R.string.prop_path),
                            path = path,
                            onCopy = {
                                clipboardManager.setText(AnnotatedString(path))
                                Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PropCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 6.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            content()
        }
    }
}

@Composable
private fun PropRow2(
    label1: String,
    value1: String,
    label2: String? = null,
    value2: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label1,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value1,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (label2 != null && value2 != null) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label2,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = value2,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun PropPathRow(
    label: String,
    path: String,
    onCopy: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = path,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium, fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(
            onClick = onCopy,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = stringResource(R.string.prop_copy_path),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
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
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

private fun formatResolution(res: String?): String? {
    if (res.isNullOrBlank()) return null
    val parts = res.split("x", "X", "×")
    if (parts.size == 2) {
        val w = parts[0].trim().toIntOrNull()
        val h = parts[1].trim().toIntOrNull()
        if (w != null && h != null && w > 0 && h > 0) {
            val gcd = gcd(w, h)
            val rw = w / gcd
            val rh = h / gcd
            val ratioStr = when {
                rw == 16 && rh == 9 -> "16:9"
                rw == 4 && rh == 3 -> "4:3"
                rw == 21 && rh == 9 || (w * 9 / h in 20..22) -> "21:9"
                rw == 16 && rh == 10 -> "16:10"
                rw == 18 && rh == 9 || rw == 2 && rh == 1 -> "18:9"
                rw == 1 && rh == 1 -> "1:1"
                else -> String.format(java.util.Locale.US, "%.2f:1", w.toDouble() / h)
            }
            return "${w}×${h} ($ratioStr)"
        }
    }
    return res
}

private fun gcd(a: Int, b: Int): Int {
    var x = a
    var y = b
    while (y != 0) {
        val t = y
        y = x % y
        x = t
    }
    return x
}

private fun formatBitrate(bps: Long): String = when {
    bps >= 1_000_000 -> String.format(java.util.Locale.US, "%.2f Mbps", bps / 1_000_000.0)
    bps >= 1_000 -> String.format(java.util.Locale.US, "%.0f kbps", bps / 1_000.0)
    else -> "$bps bps"
}

private fun formatSampleRate(hz: String): String {
    val n = hz.toIntOrNull() ?: return "$hz Hz"
    return if (n % 1000 == 0) "${n / 1000} kHz" else String.format(java.util.Locale.US, "%.1f kHz", n / 1000.0)
}

private fun formatChannels(count: Int, layout: String? = null): String {
    val layoutClean = if (!layout.isNullOrBlank()) " ($layout)" else ""
    return when (count) {
        1 -> "Mono"
        2 -> "Stereo (2 ch)$layoutClean"
        6 -> "5.1 Surround (6 ch)"
        8 -> "7.1 Surround (8 ch)"
        else -> "$count ch$layoutClean"
    }
}

private fun formatContainerName(
    rawFormat: String?,
    rawFormatLong: String?,
    mimeType: String?,
    title: String
): String {
    val ext = title.substringAfterLast('.', "").lowercase()
    val fmt = (rawFormat ?: "").lowercase()
    val fmtLong = (rawFormatLong ?: "").lowercase()

    return when {
        fmt.contains("matroska") || fmt.contains("webm") || fmtLong.contains("matroska") -> {
            when (ext) {
                "webm" -> "WebM"
                "mka" -> "MKA (Matroska Audio)"
                else -> "MKV (Matroska)"
            }
        }
        fmt.contains("mov") || fmt.contains("mp4") || fmt.contains("m4a") || fmt.contains("3gp") || fmtLong.contains("quicktime") || fmtLong.contains("mp4") -> {
            when (ext) {
                "mp4", "m4v" -> "MP4 (MPEG-4)"
                "m4a" -> "M4A (MPEG-4 Audio)"
                "mov" -> "QuickTime (MOV)"
                "3gp", "3g2" -> "3GP (3GPP)"
                else -> "MP4 (MPEG-4)"
            }
        }
        fmt.contains("mpegts") || fmt.contains("ts") || fmtLong.contains("transport stream") -> "MPEG-TS"
        fmt.contains("avi") || fmtLong.contains("avi") -> "AVI"
        fmt.contains("flv") || fmtLong.contains("flash") -> "FLV"
        fmt.contains("ogg") || fmtLong.contains("ogg") -> {
            when (ext) {
                "opus" -> "Opus (Ogg)"
                "oga" -> "Ogg Audio"
                else -> "Ogg"
            }
        }
        fmt.contains("flac") || fmtLong.contains("flac") -> "FLAC"
        fmt.contains("mp3") || fmt.contains("mp2") || fmtLong.contains("layer 3") || fmtLong.contains("mpeg audio") -> "MP3"
        fmt.contains("aac") || fmtLong.contains("adts") -> "AAC"
        fmt.contains("wav") || fmtLong.contains("wave") -> "WAV"
        fmt.contains("asf") || fmt.contains("wmv") || fmt.contains("wma") -> {
            when (ext) {
                "wmv" -> "WMV"
                "wma" -> "WMA"
                else -> "ASF"
            }
        }
        mimeType != null && mimeTypeToCleanName(mimeType) != null -> mimeTypeToCleanName(mimeType)!!
        !rawFormatLong.isNullOrBlank() && !rawFormatLong.contains(",") && rawFormatLong.length <= 20 -> rawFormatLong
        !rawFormat.isNullOrBlank() && !rawFormat.contains(",") -> rawFormat.uppercase()
        ext.isNotEmpty() && ext.length <= 5 -> ext.uppercase()
        else -> "-"
    }
}

private fun mimeTypeToCleanName(mimeType: String): String? = when (mimeType.lowercase()) {
    "video/mp4" -> "MP4 (MPEG-4)"
    "video/x-matroska" -> "MKV (Matroska)"
    "video/webm" -> "WebM"
    "video/quicktime" -> "QuickTime (MOV)"
    "video/avi", "video/x-msvideo" -> "AVI"
    "video/mp2t" -> "MPEG-TS"
    "video/x-flv" -> "FLV"
    "audio/mpeg", "audio/mp3" -> "MP3"
    "audio/flac", "audio/x-flac" -> "FLAC"
    "audio/mp4", "audio/x-m4a" -> "M4A (MPEG-4 Audio)"
    "audio/ogg", "audio/opus" -> "Ogg / Opus"
    "audio/wav", "audio/x-wav" -> "WAV"
    "audio/aac" -> "AAC"
    else -> null
}

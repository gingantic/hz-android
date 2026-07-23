package com.rhnxdev.hzplayer.browser.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhnxdev.hzplayer.MainActivity
import com.rhnxdev.hzplayer.browser.media.DetectedMediaItem
import com.rhnxdev.hzplayer.browser.media.MediaDownloader
import com.rhnxdev.hzplayer.browser.media.MediaType

private enum class MediaFilter {
    ALL, VIDEO, AUDIO, STREAMS
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MediaGrabberBottomSheet(
    mediaItems: List<DetectedMediaItem>,
    onDismissRequest: () -> Unit,
    onClearAll: () -> Unit,
    onQualitySelected: (itemId: String, qualityUrl: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    var selectedFilter by remember { mutableStateOf(MediaFilter.ALL) }
    var searchQuery by remember { mutableStateOf("") }

    val filteredItems = remember(mediaItems, selectedFilter, searchQuery) {
        mediaItems.filter { item ->
            val matchesFilter = when (selectedFilter) {
                MediaFilter.ALL -> true
                MediaFilter.VIDEO -> item.mediaType == MediaType.VIDEO
                MediaFilter.AUDIO -> item.mediaType == MediaType.AUDIO
                MediaFilter.STREAMS -> item.mediaType == MediaType.STREAM_HLS || item.mediaType == MediaType.STREAM_DASH
            }
            val matchesSearch = searchQuery.isBlank() ||
                    item.title.contains(searchQuery, ignoreCase = true) ||
                    item.extension.contains(searchQuery, ignoreCase = true) ||
                    item.displayQuality.contains(searchQuery, ignoreCase = true)

            matchesFilter && matchesSearch
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            // Minimalist Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Media Grabber",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape
                    ) {
                        Text(
                            text = "${mediaItems.size}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (mediaItems.isNotEmpty()) {
                        TextButton(
                            onClick = onClearAll,
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("Clear", fontSize = 12.sp)
                        }
                    }
                    IconButton(
                        onClick = onDismissRequest,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Close",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Sleek Minimalist Filter Chips
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                MediaFilter.entries.forEach { filter ->
                    val count = when (filter) {
                        MediaFilter.ALL -> mediaItems.size
                        MediaFilter.VIDEO -> mediaItems.count { it.mediaType == MediaType.VIDEO }
                        MediaFilter.AUDIO -> mediaItems.count { it.mediaType == MediaType.AUDIO }
                        MediaFilter.STREAMS -> mediaItems.count { it.mediaType == MediaType.STREAM_HLS || it.mediaType == MediaType.STREAM_DASH }
                    }
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { selectedFilter = filter },
                        label = { Text("${filter.name.lowercase().capitalize()} ($count)", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier.height(30.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Search Bar (if > 2 items)
            if (mediaItems.size > 2) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Filter media...", fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(16.dp)) },
                    trailingIcon = if (searchQuery.isNotEmpty()) {
                        { IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Clear, null, modifier = Modifier.size(16.dp)) } }
                    } else null,
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Minimalist Media List
            if (filteredItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (mediaItems.isEmpty()) "No media sniffed on this page" else "No matching media found",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                ) {
                    items(filteredItems, key = { it.id }) { item ->
                        MinimalistMediaItemCard(
                            item = item,
                            onPlayInHzPlayer = { launchNativePlayer(context, item) },
                            onDownload = { MediaDownloader.downloadMedia(context, item) },
                            onCopyUrl = { copyToClipboard(context, "Direct Link", item.displayUrl) },
                            onCopyCurl = { copyToClipboard(context, "cURL Command", buildCurlCommand(item)) },
                            onShare = { shareMediaUrl(context, item) },
                            onQualitySelected = { qualityUrl -> onQualitySelected(item.id, qualityUrl) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun MinimalistMediaItemCard(
    item: DetectedMediaItem,
    onPlayInHzPlayer: () -> Unit,
    onDownload: () -> Unit,
    onCopyUrl: () -> Unit,
    onCopyCurl: () -> Unit,
    onShare: () -> Unit,
    onQualitySelected: (String) -> Unit
) {
    var showQualityMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showNetworkDetails by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // Row 1: Format badge + Title + Size
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Title and Icon
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = item.displayQuality,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // File size pill
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = item.formattedSize,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // URL preview caption
            Text(
                text = item.displayUrl,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Stream Qualities Dropdown (if M3U8 multi-resolution)
            if (item.subQualities.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Box {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showQualityMenu = true }
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Quality: ${item.subQualities.find { it.url == item.displayUrl }?.label ?: item.displayQuality}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium
                            )
                            Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size(16.dp))
                        }
                    }

                    DropdownMenu(
                        expanded = showQualityMenu,
                        onDismissRequest = { showQualityMenu = false }
                    ) {
                        item.subQualities.forEach { q ->
                            DropdownMenuItem(
                                text = { Text(q.label, fontSize = 12.sp) },
                                leadingIcon = if (q.url == item.displayUrl) {
                                    { Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp)) }
                                } else null,
                                onClick = {
                                    onQualitySelected(q.url)
                                    showQualityMenu = false
                                }
                            )
                        }
                    }
                }
            }

            // Expandable Network & Token Inspector Card
            if (showNetworkDetails) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = "Network & Package Inspector",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        // MIME & Type
                        Text(
                            text = "MIME: ${item.mimeType.ifBlank { "Unknown" }} (${item.mediaType.name})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Tokens section
                        if (item.detectedTokens.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Extracted Security Tokens (${item.detectedTokens.size}):",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                            item.detectedTokens.forEach { (key, valStr) ->
                                Text(
                                    text = "  • $key = ${valStr.take(30)}${if (valStr.length > 30) "..." else ""}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        // Headers section
                        if (item.headers.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Request Headers (${item.headers.size}):",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            item.headers.forEach { (hKey, hVal) ->
                                Text(
                                    text = "  • $hKey: ${hVal.take(45)}${if (hVal.length > 45) "..." else ""}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Action Buttons
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Primary Play Button
                Button(
                    onClick = onPlayInHzPlayer,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Play in HzPlayer", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Network Details Toggle Button
                    TextButton(
                        onClick = { showNetworkDetails = !showNetworkDetails },
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(
                            text = if (showNetworkDetails) "Hide Info" else if (item.hasAuthInfo) "Inspect (Auth)" else "Inspect",
                            fontSize = 11.sp,
                            fontWeight = if (item.hasAuthInfo) FontWeight.Bold else FontWeight.Normal,
                            color = if (item.hasAuthInfo) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                        )
                    }

                    // Download Icon Button
                    IconButton(
                        onClick = onDownload,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = "Download", modifier = Modifier.size(18.dp))
                    }

                    // Copy / Share / cURL Dropdown
                    Box {
                        IconButton(
                            onClick = { showMoreMenu = true },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More", modifier = Modifier.size(18.dp))
                        }

                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Copy Direct Link", fontSize = 12.sp) },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(14.dp)) },
                                onClick = {
                                    onCopyUrl()
                                    showMoreMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Copy cURL Command", fontSize = 12.sp) },
                                leadingIcon = { Icon(Icons.Default.Terminal, null, modifier = Modifier.size(14.dp)) },
                                onClick = {
                                    onCopyCurl()
                                    showMoreMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Share Link", fontSize = 12.sp) },
                                leadingIcon = { Icon(Icons.Default.Share, null, modifier = Modifier.size(14.dp)) },
                                onClick = {
                                    onShare()
                                    showMoreMenu = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun launchNativePlayer(context: Context, item: DetectedMediaItem) {
    try {
        val displayUrlLower = item.displayUrl.lowercase(java.util.Locale.ROOT)
        val mimeLower = item.mimeType.lowercase(java.util.Locale.ROOT)
        val targetMime = when {
            item.mediaType == MediaType.STREAM_HLS || displayUrlLower.contains(".m3u8") || displayUrlLower.contains("/hls/") || displayUrlLower.contains("m3u8") || mimeLower.contains("mpegurl") -> "application/x-mpegURL"
            item.mediaType == MediaType.STREAM_DASH || displayUrlLower.contains(".mpd") || displayUrlLower.contains("/dash/") || mimeLower.contains("dash") -> "application/dash+xml"
            else -> item.mimeType.ifBlank { "video/*" }
        }
        val targetActivity = if (item.mediaType == MediaType.AUDIO) {
            com.rhnxdev.hzplayer.AudioPlayerActivity::class.java
        } else {
            com.rhnxdev.hzplayer.VideoPlayerActivity::class.java
        }

        // Merge live session cookies and page referer for smooth CDN auth
        val mergedHeaders = item.headers.toMutableMap()
        val targetPage = item.pageUrl.ifBlank { item.displayUrl }
        val liveCookies = runCatching {
            android.webkit.CookieManager.getInstance().getCookie(targetPage)
        }.getOrNull()
        if (!liveCookies.isNullOrBlank() && mergedHeaders.keys.none { it.equals("Cookie", ignoreCase = true) }) {
            mergedHeaders["Cookie"] = liveCookies
        }
        if (item.pageUrl.isNotBlank() && mergedHeaders.keys.none { it.equals("Referer", ignoreCase = true) }) {
            mergedHeaders["Referer"] = item.pageUrl
        }

        val intent = Intent(context, targetActivity).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(Uri.parse(item.displayUrl), targetMime)
            putExtra("extra_media_title", item.title)
            putExtra("from_browser", true)
            val headersList = mutableListOf<String>()
            val headersBundle = android.os.Bundle()
            mergedHeaders.forEach { (k, v) ->
                if (k.isNotBlank() && v.isNotBlank()) {
                    headersList.add(k)
                    headersList.add(v)
                    headersBundle.putString(k, v)
                }
            }
            if (headersList.isNotEmpty()) {
                putExtra("headers", headersList.toTypedArray())
                putExtra("android.media.intent.extra.HTTP_HEADERS", headersBundle)
            }
        }

        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Could not launch player: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
    }
}


private fun buildCurlCommand(item: DetectedMediaItem): String {
    val sb = StringBuilder("curl \"${item.displayUrl}\"")
    item.headers.forEach { (k, v) ->
        sb.append(" -H \"$k: $v\"")
    }
    return sb.toString()
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, "Copied $label to clipboard", Toast.LENGTH_SHORT).show()
}

private fun shareMediaUrl(context: Context, item: DetectedMediaItem) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, item.displayUrl)
        putExtra(Intent.EXTRA_TITLE, item.title)
    }
    context.startActivity(Intent.createChooser(intent, "Share Media Link"))
}

private fun String.capitalize(): String =
    replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

package com.rhnxdev.hzplayer.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rhnxdev.hzplayer.browser.BrowserTab
import com.rhnxdev.hzplayer.core.designsystem.CornerRadii

@Composable
fun TabStrip(
    tabs: List<BrowserTab>,
    activeTabId: String?,
    onTabClick: (String) -> Unit,
    onTabClose: (String) -> Unit,
    onNewTab: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // Automatically scroll to active tab whenever tab selection or tab count changes
    LaunchedEffect(activeTabId, tabs.size) {
        val activeIndex = tabs.indexOfFirst { it.id == activeTabId }
        if (activeIndex >= 0) {
            listState.animateScrollToItem(activeIndex)
        }
    }

    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            state = listState,
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(tabs, key = { it.id }) { tab ->
                TabItem(
                    tab = tab,
                    isActive = tab.id == activeTabId,
                    onClick = { onTabClick(tab.id) },
                    onClose = { onTabClose(tab.id) },
                )
            }
        }

        // New tab button
        IconButton(
            onClick = onNewTab,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = "New tab",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun TabItem(
    tab: BrowserTab,
    isActive: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    val bg = if (isActive) MaterialTheme.colorScheme.primaryContainer
             else MaterialTheme.colorScheme.surfaceContainerHigh
    val contentColor = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                       else MaterialTheme.colorScheme.onSurfaceVariant

    val displayTitle = when {
        tab.title.isNotBlank() -> tab.title
        tab.url.isBlank() || tab.url == "about:blank" -> "New Tab"
        else -> tab.url.removePrefix("https://").removePrefix("http://").take(15)
    }

    val tabIcon = when {
        tab.url.startsWith("https://", ignoreCase = true) -> Icons.Default.Lock
        tab.url.startsWith("http://", ignoreCase = true) -> Icons.Default.LockOpen
        else -> Icons.Default.Language
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(CornerRadii.sm))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = tabIcon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = contentColor,
        )

        Spacer(Modifier.width(4.dp))

        Text(
            text = displayTitle,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(min = 60.dp, max = 110.dp),
        )

        IconButton(
            onClick = onClose,
            modifier = Modifier.size(22.dp),
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Close tab",
                modifier = Modifier.size(14.dp),
                tint = contentColor,
            )
        }
    }
}

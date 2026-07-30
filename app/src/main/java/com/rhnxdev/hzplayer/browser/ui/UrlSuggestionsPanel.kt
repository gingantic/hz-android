package com.rhnxdev.hzplayer.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NorthWest
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.domain.model.UrlSuggestion

/**
 * Chrome-style omnibox suggestion list shown below the URL bar while it has
 * focus. With no typed text it lists the most visited sites; while typing it
 * shows history entries matching the query substring (URL or title).
 */
@Composable
fun UrlSuggestionsPanel(
    suggestions: List<UrlSuggestion>,
    isFiltering: Boolean,
    onSuggestionClick: (String) -> Unit,
    onFillUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.background(MaterialTheme.colorScheme.surface),
    ) {
        Text(
            text = if (isFiltering) "From your history" else "Most visited",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                start = Spacing.md,
                end = Spacing.md,
                top = Spacing.sm,
                bottom = Spacing.xs,
            ),
        )
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(suggestions, key = { it.url }) { suggestion ->
                UrlSuggestionRow(
                    suggestion = suggestion,
                    isFiltering = isFiltering,
                    onClick = { onSuggestionClick(suggestion.url) },
                    onFillUrl = { onFillUrl(suggestion.url) },
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    thickness = 0.5.dp,
                )
            }
        }
    }
}

@Composable
private fun UrlSuggestionRow(
    suggestion: UrlSuggestion,
    isFiltering: Boolean,
    onClick: () -> Unit,
    onFillUrl: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isFiltering) Icons.Default.History else Icons.Default.Public,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Spacing.sm),
        ) {
            Text(
                text = suggestion.title.ifBlank { suggestion.url },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = suggestion.url
                    .removePrefix("https://")
                    .removePrefix("http://"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Chrome-style "insert into URL bar" arrow — fills text without navigating
        IconButton(onClick = onFillUrl, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = Icons.Default.NorthWest,
                contentDescription = "Insert into URL bar",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

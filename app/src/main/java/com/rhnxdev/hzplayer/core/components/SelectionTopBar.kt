package com.rhnxdev.hzplayer.core.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.rhnxdev.hzplayer.R
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme

/**
 * Top bar shown in place of [HzPlayerTopBar] while multi-select is active.
 *
 * Same 64 dp height, so the list below does not jump when selection starts.
 *
 * @param count — number of selected items
 * @param onExit — leave selection mode
 * @param onToggleSelectAll — select every visible item, or deselect them when all are selected
 * @param allSelected — every visible item is selected; switches the button to "deselect all"
 */
@Composable
fun SelectionTopBar(
    count: Int,
    onExit: () -> Unit,
    onToggleSelectAll: () -> Unit,
    modifier: Modifier = Modifier,
    allSelected: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.width(Spacing.xs))
        IconButton(onClick = onExit) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.selection_exit_cd),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(modifier = Modifier.width(Spacing.md))
        Text(
            text = stringResource(R.string.selection_count, count),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onToggleSelectAll) {
            Icon(
                imageVector = if (allSelected) Icons.Default.Deselect else Icons.Default.SelectAll,
                contentDescription = stringResource(
                    if (allSelected) R.string.selection_deselect_all_cd else R.string.selection_select_all_cd,
                ),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(modifier = Modifier.width(Spacing.md))
    }
}

@PreviewLightDark
@Preview
@Composable
private fun SelectionTopBarPreview() {
    HzPlayerTheme {
        SelectionTopBar(count = 3, onExit = {}, onToggleSelectAll = {}, allSelected = false)
    }
}

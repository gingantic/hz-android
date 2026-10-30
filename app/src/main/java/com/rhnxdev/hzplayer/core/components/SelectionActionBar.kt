package com.rhnxdev.hzplayer.core.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.rhnxdev.hzplayer.R
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme

/**
 * One button on the [SelectionActionBar].
 *
 * @param destructive — tints the button with the error colour (e.g. Delete)
 */
@Immutable
data class SelectionAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
)

/**
 * Bottom bar of batch actions shown while multi-select is active.
 *
 * Styled like the file browser's paste bar so the two read as one family.
 * Pass only the actions that apply; an empty list renders nothing.
 */
@Composable
fun SelectionActionBar(
    actions: List<SelectionAction>,
    modifier: Modifier = Modifier,
) {
    if (actions.isEmpty()) return
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions.forEach { action ->
                val tint = if (action.destructive) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface
                TextButton(
                    onClick = action.onClick,
                    enabled = action.enabled,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = tint,
                        disabledContentColor = tint.copy(alpha = 0.38f),
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(imageVector = action.icon, contentDescription = null)
                        Text(
                            text = action.label,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@PreviewLightDark
@Preview
@Composable
private fun SelectionActionBarPreview() {
    HzPlayerTheme {
        SelectionActionBar(
            actions = listOf(
                SelectionAction(Icons.Default.PlayArrow, stringResource(R.string.selection_action_play), {}),
                SelectionAction(Icons.Default.ContentCut, stringResource(R.string.menu_cut), {}),
                SelectionAction(Icons.Default.ContentCopy, stringResource(R.string.menu_copy), {}),
                SelectionAction(Icons.Default.Delete, stringResource(R.string.menu_delete), {}, destructive = true),
            ),
        )
    }
}

package com.rhnxdev.hzplayer.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.rhnxdev.hzplayer.R
import com.rhnxdev.hzplayer.browser.adblock.PickedElement
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme

/**
 * Non-modal bottom panel shown while element-picker mode is on — it must not
 * cover the page, because tapping the page is how an element gets selected.
 */
@Composable
fun ElementPickerPanel(
    picked: PickedElement?,
    onWider: () -> Unit,
    onBlock: (applyToAllSites: Boolean) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Local UI state: it dies with the panel, so every pick starts domain-scoped.
    var applyToAllSites by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (picked == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.browser_element_picker_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.browser_element_picker_cancel))
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = picked.selector,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onWider, enabled = picked.canWiden) {
                    Text(stringResource(R.string.browser_element_picker_wider))
                }
            }

            Text(
                text = stringResource(R.string.browser_element_picker_matches, picked.matchCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = applyToAllSites, onCheckedChange = { applyToAllSites = it })
                Text(
                    text = stringResource(R.string.browser_element_picker_apply_all),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCancel) {
                    Text(
                        stringResource(R.string.browser_element_picker_cancel),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onBlock(applyToAllSites) }) {
                    Text(
                        stringResource(R.string.browser_element_picker_block),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun ElementPickerPanelIdlePreview() {
    HzPlayerTheme {
        ElementPickerPanel(
            picked = null,
            onWider = {},
            onBlock = {},
            onCancel = {},
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@PreviewLightDark
@Composable
private fun ElementPickerPanelSelectedPreview() {
    HzPlayerTheme {
        ElementPickerPanel(
            picked = PickedElement(selector = "div.article > div.ad-slot", matchCount = 1, canWiden = true),
            onWider = {},
            onBlock = {},
            onCancel = {},
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

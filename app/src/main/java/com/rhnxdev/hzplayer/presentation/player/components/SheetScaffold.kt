package com.rhnxdev.hzplayer.presentation.player.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Shared chrome for the player's bottom-sheet dialogs: the dark [ModalBottomSheet]
 * container and the icon + title header row that every one of them repeats.
 *
 * Each dialog's inner padding / scroll differs, so the caller supplies
 * [columnModifier] for the content [Column]; the body follows the header via
 * [content]. This only lifts the identical boilerplate — no visual change.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetScaffold(
    title: String,
    icon: ImageVector,
    onDismiss: () -> Unit,
    columnModifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF1E1E24),
        tonalElevation = 0.dp,
    ) {
        Column(modifier = columnModifier) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    ),
                )
            }
            content()
        }
    }
}

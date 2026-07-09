package com.rhnxdev.hzplayer.core.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.rhnxdev.hzplayer.domain.model.ViewMode
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme
import androidx.compose.ui.unit.dp

@Composable
fun ViewToggleFab(
    currentView: ViewMode,
    onToggle: (ViewMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val (icon, targetMode) = when (currentView) {
        ViewMode.GRID -> Icons.Filled.ViewList to ViewMode.LIST
        ViewMode.LIST -> Icons.Filled.GridView to ViewMode.GRID
    }

    FloatingActionButton(
        onClick = { onToggle(targetMode) },
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        elevation = androidx.compose.material3.FloatingActionButtonDefaults.elevation(
            defaultElevation = 6.dp,
            pressedElevation = 12.dp
        ),
    ) {
        AnimatedContent(
            targetState = icon,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "view_toggle_icon",
        ) { currentIcon ->
            Icon(
                imageVector = currentIcon,
                contentDescription = if (currentView == ViewMode.GRID) "Switch to list" else "Switch to grid",
            )
        }
    }
}

@PreviewLightDark
@Preview
@Composable
private fun ViewToggleFabPreview() {
    HzPlayerTheme {
        ViewToggleFab(
            currentView = ViewMode.GRID,
            onToggle = {},
        )
    }
}

package com.rhnxdev.hzplayer.core.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhnxdev.hzplayer.R
import com.rhnxdev.hzplayer.core.designsystem.HzPlayerIcons
import com.rhnxdev.hzplayer.core.designsystem.Spacing

/**
 * Minimalist, modern Modal Bottom Sheet for presenting context options for a file or folder.
 * Consistent with ViewSortBottomSheet in typography, spacing, corner radius, and color tokens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileOptionsBottomSheet(
    name: String,
    isDirectory: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    mimeType: String? = null,
    leadingThumbnail: (@Composable () -> Unit)? = null,
    isFavorite: Boolean = false,
    onFavoriteClick: (() -> Unit)? = null,
    onPlayAllClick: (() -> Unit)? = null,
    onPlayAsAudioClick: (() -> Unit)? = null,
    onCutClick: (() -> Unit)? = null,
    onCopyClick: (() -> Unit)? = null,
    onDeleteClick: (() -> Unit)? = null,
    onPropertiesClick: (() -> Unit)? = null,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    val containerColor = MaterialTheme.colorScheme.surfaceContainerLow

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = containerColor,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        tonalElevation = 0.dp,
        dragHandle = {
            if (leadingThumbnail == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .width(32.dp)
                            .height(4.dp)
                            .background(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                                RoundedCornerShape(2.dp),
                            ),
                    )
                }
            }
        },
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = Spacing.md),
        ) {
            // Optional Thumbnail Banner
            if (leadingThumbnail != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp),
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        leadingThumbnail()
                    }

                    // Soft Gradient blend into container
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Black.copy(alpha = 0.35f),
                                        Color.Transparent,
                                        containerColor.copy(alpha = 0.85f),
                                        containerColor,
                                    ),
                                ),
                            ),
                    )

                    // Overlay Drag Handle
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(32.dp)
                                .height(4.dp)
                                .background(
                                    Color.White.copy(alpha = 0.45f),
                                    RoundedCornerShape(2.dp),
                                ),
                        )
                    }
                }
            }

            // Header Title & Subtitle
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.2).sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Quick Actions Horizontal Row (if media/folder shortcuts available)
            val hasQuickActions = onFavoriteClick != null || onPlayAllClick != null || onPlayAsAudioClick != null || onPropertiesClick != null
            if (hasQuickActions) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (onFavoriteClick != null) {
                        QuickActionPill(
                            icon = if (isFavorite) HzPlayerIcons.Star else HzPlayerIcons.StarOutline,
                            label = if (isFavorite) stringResource(R.string.menu_unfavorite) else stringResource(R.string.menu_favorite),
                            isSelected = isFavorite,
                            onClick = {
                                onDismissRequest()
                                onFavoriteClick()
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (onPlayAllClick != null) {
                        QuickActionPill(
                            icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                            label = stringResource(R.string.menu_play_all_playlist),
                            onClick = {
                                onDismissRequest()
                                onPlayAllClick()
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (onPlayAsAudioClick != null) {
                        QuickActionPill(
                            icon = Icons.Default.MusicNote,
                            label = stringResource(R.string.media_play_as_audio),
                            onClick = {
                                onDismissRequest()
                                onPlayAsAudioClick()
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (onPropertiesClick != null) {
                        QuickActionPill(
                            icon = Icons.Default.Info,
                            label = stringResource(R.string.media_properties),
                            onClick = {
                                onDismissRequest()
                                onPropertiesClick()
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // Action Items List
            Spacer(modifier = Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (onCutClick != null) {
                    MinimalOptionRow(
                        icon = Icons.Default.ContentCut,
                        label = stringResource(R.string.menu_cut),
                        onClick = {
                            onDismissRequest()
                            onCutClick()
                        },
                    )
                }

                if (onCopyClick != null) {
                    MinimalOptionRow(
                        icon = Icons.Default.ContentCopy,
                        label = stringResource(R.string.menu_copy),
                        onClick = {
                            onDismissRequest()
                            onCopyClick()
                        },
                    )
                }

                if (onDeleteClick != null) {
                    MinimalOptionRow(
                        icon = Icons.Default.Delete,
                        label = stringResource(R.string.menu_delete),
                        isDestructive = true,
                        onClick = {
                            onDismissRequest()
                            onDeleteClick()
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun QuickActionPill(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
) {
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
        label = "quickActionBg",
    )
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface,
        label = "quickActionContent",
    )

    Box(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick,
            )
            .padding(horizontal = 4.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MinimalOptionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDestructive: Boolean = false,
) {
    val contentColor = if (isDestructive) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (isDestructive) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = contentColor,
        )
    }
}

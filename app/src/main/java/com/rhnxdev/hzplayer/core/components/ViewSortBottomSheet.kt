package com.rhnxdev.hzplayer.core.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoLibrary
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhnxdev.hzplayer.R
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.domain.model.FileMediaTypeFilter
import com.rhnxdev.hzplayer.domain.model.SortDirection
import com.rhnxdev.hzplayer.domain.model.SortType

/**
 * Minimalist, modern Modal Bottom Sheet for adjusting View Layout, Sorting, and Media Filtering.
 * Designed with clean segmented controls, refined typography, and fluid micro-interactions.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ViewSortBottomSheet(
    sortType: SortType,
    sortDirection: SortDirection,
    onSortChanged: (SortType, SortDirection) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    isMediaMode: Boolean? = null,
    onToggleMediaMode: (() -> Unit)? = null,
    mediaTypeFilter: FileMediaTypeFilter? = null,
    onMediaTypeFilterChanged: ((FileMediaTypeFilter) -> Unit)? = null,
    availableSortTypes: List<SortType> = listOf(
        SortType.TITLE,
        SortType.DATE_MODIFIED,
        SortType.FILE_SIZE,
        SortType.DURATION,
    ),
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
        },
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = Spacing.md),
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Sort,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.view_and_sort_title),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.2).sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                // Compact Direction Pill Toggle in Header
                DirectionTogglePill(
                    sortDirection = sortDirection,
                    onToggle = {
                        val next = if (sortDirection == SortDirection.ASCENDING) {
                            SortDirection.DESCENDING
                        } else {
                            SortDirection.ASCENDING
                        }
                        onSortChanged(sortType, next)
                    },
                )
            }

            // 1. VIEW MODE SEGMENTED CONTROL (if supported)
            if (isMediaMode != null && onToggleMediaMode != null) {
                MinimalSegmentedControl(
                    items = listOf(
                        SegmentItem(
                            icon = Icons.AutoMirrored.Filled.ViewList,
                            label = stringResource(R.string.view_mode_list),
                            isSelected = !isMediaMode,
                            onClick = { if (isMediaMode) onToggleMediaMode() },
                        ),
                        SegmentItem(
                            icon = Icons.Filled.PhotoLibrary,
                            label = stringResource(R.string.view_mode_media),
                            isSelected = isMediaMode,
                            onClick = { if (!isMediaMode) onToggleMediaMode() },
                        ),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(18.dp))
            }

            // 2. SORT BY SECTION
            SectionLabel(text = stringResource(R.string.video_sort_cd).uppercase())
            Spacer(modifier = Modifier.height(6.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                availableSortTypes.forEach { type ->
                    val isSelected = sortType == type
                    val label = when (type) {
                        SortType.TITLE -> stringResource(R.string.sort_by_name)
                        SortType.DATE_MODIFIED, SortType.DATE_ADDED -> stringResource(R.string.sort_by_date)
                        SortType.FILE_SIZE -> stringResource(R.string.sort_by_size)
                        SortType.DURATION -> stringResource(R.string.sort_by_duration)
                        else -> type.name
                    }

                    MinimalPillChip(
                        label = label,
                        isSelected = isSelected,
                        onClick = { onSortChanged(type, sortDirection) },
                    )
                }
            }

            // 3. MEDIA TYPE FILTER SECTION (if supported)
            if (mediaTypeFilter != null && onMediaTypeFilterChanged != null) {
                Spacer(modifier = Modifier.height(18.dp))
                SectionLabel(text = stringResource(R.string.filter_by_type_title).uppercase())
                Spacer(modifier = Modifier.height(6.dp))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val filterOptions = listOf(
                        Triple(FileMediaTypeFilter.ALL, stringResource(R.string.filter_all), null),
                        Triple(FileMediaTypeFilter.VIDEOS, stringResource(R.string.filter_videos), Icons.Filled.Movie),
                        Triple(FileMediaTypeFilter.AUDIO, stringResource(R.string.filter_audio), Icons.Filled.MusicNote),
                        Triple(FileMediaTypeFilter.ARCHIVES, stringResource(R.string.filter_archives), Icons.Filled.Folder),
                    )

                    filterOptions.forEach { (filter, label, icon) ->
                        val isSelected = mediaTypeFilter == filter
                        MinimalPillChip(
                            label = label,
                            icon = icon,
                            isSelected = isSelected,
                            onClick = { onMediaTypeFilterChanged(filter) },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = modifier,
    )
}

private data class SegmentItem(
    val icon: ImageVector,
    val label: String,
    val isSelected: Boolean,
    val onClick: () -> Unit,
)

@Composable
private fun MinimalSegmentedControl(
    items: List<SegmentItem>,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f))
            .padding(3.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            items.forEach { item ->
                val bgColor by animateColorAsState(
                    targetValue = if (item.isSelected) MaterialTheme.colorScheme.surface
                    else Color.Transparent,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "segmentBg",
                )
                val textColor by animateColorAsState(
                    targetValue = if (item.isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "segmentText",
                )

                Surface(
                    onClick = item.onClick,
                    shape = RoundedCornerShape(11.dp),
                    color = bgColor,
                    shadowElevation = if (item.isSelected) 1.dp else 0.dp,
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = null,
                            tint = textColor,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = if (item.isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            ),
                            color = textColor,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MinimalPillChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.4f),
        label = "chipBg",
    )
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "chipContent",
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
        label = "chipBorder",
    )

    Box(
        modifier = modifier
            .clip(CircleShape)
            .border(1.dp, borderColor, CircleShape)
            .background(bgColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = contentColor,
            )
        }
    }
}

@Composable
private fun DirectionTogglePill(
    sortDirection: SortDirection,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isAsc = sortDirection == SortDirection.ASCENDING
    val icon = if (isAsc) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward
    val label = if (isAsc) stringResource(R.string.sort_ascending) else stringResource(R.string.sort_descending)

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onToggle,
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

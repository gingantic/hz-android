package com.rhnxdev.hzplayer.core.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.rhnxdev.hzplayer.R
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme

/**
 * Breadcrumb trail plus the "N folders, M files" summary above a directory listing,
 * shared by the local and remote browsers. The summary hides itself when the visible
 * list is empty — the same condition that makes the pane below show its empty state.
 *
 * @param breadcrumbs path segments from the storage root to the current directory
 * @param folderCount directories remaining after the media filter, not the raw count
 * @param fileCount files remaining after the media filter
 * @param mediaMode selects the media-flavoured summary wording
 */
@Composable
fun DirectoryHeader(
    breadcrumbs: List<BreadcrumbItem>,
    folderCount: Int,
    fileCount: Int,
    mediaMode: Boolean,
    onBreadcrumbClicked: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        BreadcrumbBar(
            breadcrumbs = breadcrumbs,
            onBreadcrumbClicked = onBreadcrumbClicked,
        )

        if (folderCount + fileCount > 0) {
            Text(
                text = if (mediaMode) {
                    stringResource(R.string.dir_summary_media, folderCount, fileCount)
                } else {
                    stringResource(R.string.dir_summary, folderCount, fileCount)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.md, top = Spacing.xs, bottom = Spacing.xs),
            )
        }
    }
}

@PreviewLightDark
@Preview
@Composable
private fun DirectoryHeaderPreview() {
    HzPlayerTheme {
        DirectoryHeader(
            breadcrumbs = listOf(
                BreadcrumbItem("Internal storage", "/storage/emulated/0"),
                BreadcrumbItem("Movies", "/storage/emulated/0/Movies"),
            ),
            folderCount = 3,
            fileCount = 12,
            mediaMode = false,
            onBreadcrumbClicked = {},
        )
    }
}

@PreviewLightDark
@Preview
@Composable
private fun DirectoryHeaderMediaPreview() {
    HzPlayerTheme {
        DirectoryHeader(
            breadcrumbs = listOf(BreadcrumbItem("Internal storage", "/storage/emulated/0")),
            folderCount = 1,
            fileCount = 4,
            mediaMode = true,
            onBreadcrumbClicked = {},
        )
    }
}

package com.rhnxdev.hzplayer.presentation.player.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import com.rhnxdev.hzplayer.core.designsystem.stableNavBarPaddingValues
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.rhnxdev.hzplayer.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.rhnxdev.hzplayer.presentation.player.SubtitleSearchViewModel
import com.rhnxdev.hzplayer.presentation.player.SubtitleSearchResultItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitleSearchDialog(
    onDismiss: () -> Unit,
    onSubtitleDownloaded: (Uri) -> Unit,
    viewModel: SubtitleSearchViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    SheetScaffold(
        title = stringResource(R.string.search_subtitles_online),
        icon = Icons.Default.Search,
        onDismiss = onDismiss,
        columnModifier = Modifier
            .fillMaxWidth()
            .padding(stableNavBarPaddingValues())
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
            Spacer(modifier = Modifier.height(12.dp))

            // Search field + button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = uiState.query,
                    onValueChange = viewModel::onQueryChange,
                    placeholder = { Text(stringResource(R.string.subtitle_search_placeholder), color = Color.Gray) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.Gray,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = viewModel::search) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = stringResource(R.string.search),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Loading
            if (uiState.isSearching) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // Error
            val error = uiState.error
            if (error != null) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }

            // Results
            if (uiState.results.isEmpty() && !uiState.isSearching && uiState.error == null) {
                Text(
                    text = stringResource(R.string.subtitle_search_hint),
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
            ) {
                items(uiState.results) { result ->
                    SearchResultRow(
                        result = result,
                        onDownload = {
                            viewModel.download(
                                fileId = result.fileId,
                                fileName = result.releaseName,
                                onDownloaded = onSubtitleDownloaded,
                            )
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(R.string.close), color = Color.White.copy(alpha = 0.7f))
                }
            }
    }
}

@Composable
private fun SearchResultRow(
    result: SubtitleSearchResultItem,
    onDownload: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onDownload)
            .padding(vertical = 10.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = result.releaseName.ifBlank { stringResource(R.string.unknown) },
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row {
                Text(
                    text = result.language.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.primary),
                )
                Text(
                    text = stringResource(R.string.downloads_count, result.downloadCount),
                    style = MaterialTheme.typography.labelSmall.copy(color = Color.Gray),
                )
            }
        }
        Icon(
            imageVector = Icons.Default.Download,
            contentDescription = stringResource(R.string.download),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(22.dp)
                .clickable(onClick = onDownload),
        )
    }
}

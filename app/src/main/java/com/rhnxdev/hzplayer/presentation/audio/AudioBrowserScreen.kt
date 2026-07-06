package com.rhnxdev.hzplayer.presentation.audio

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhnxdev.hzplayer.core.components.HzPlayerSearchableScaffold
import com.rhnxdev.hzplayer.core.components.MediaEmptyState
import com.rhnxdev.hzplayer.core.components.MediaListItem
import com.rhnxdev.hzplayer.core.components.MediaLoadingState
import com.rhnxdev.hzplayer.core.components.ShimmerShape
import com.rhnxdev.hzplayer.core.components.MediaType
import com.rhnxdev.hzplayer.core.components.ThumbnailPlaceholder
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.domain.model.Album
import com.rhnxdev.hzplayer.domain.model.Artist
import com.rhnxdev.hzplayer.domain.model.AudioItem
import com.rhnxdev.hzplayer.presentation.audio.components.AlbumCard
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioBrowserScreen(
    viewModel: AudioBrowserViewModel = hiltViewModel(),
    onSongClicked: ((AudioItem, List<AudioItem>) -> Unit)? = null,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val searchQuery by viewModel.search.searchQuery.collectAsStateWithLifecycle()
    val isSearchActive by viewModel.search.isSearchActive.collectAsStateWithLifecycle()
    val tabs = AudioTab.entries

    HzPlayerSearchableScaffold(
        title = "Music",
        isSearchActive = isSearchActive,
        searchQuery = searchQuery,
        onSearchToggle = viewModel::onSearchToggle,
        onSearchQueryChanged = viewModel::onSearchQueryChanged,
        onClearSearch = viewModel::onClearSearch,
        searchPlaceholder = "Search songs...",
    ) {
        // Tab row synced directly to ViewModel tab state
        TabRow(
            selectedTabIndex = tabs.indexOf(uiState.currentTab),
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            tabs.forEach { tab ->
                Tab(
                    selected = uiState.currentTab == tab,
                    onClick = { viewModel.onTabSelected(tab) },
                    text = {
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    },
                )
            }
        }

        // Tab content using AnimatedContent (bypasses horizontal pager interception)
        val pullState = rememberPullToRefreshState()
        val isRefreshing = when (uiState.currentTab) {
            AudioTab.SONGS -> uiState.isLoadingSongs
            AudioTab.ALBUMS -> uiState.isLoadingAlbums
            AudioTab.ARTISTS -> uiState.isLoadingArtists
        }
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = viewModel::onRefresh,
            state = pullState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            AnimatedContent(
                targetState = uiState.currentTab,
                modifier = Modifier.fillMaxSize(),
                label = "tab_transition"
            ) { targetTab ->
                when (targetTab) {
                    AudioTab.SONGS -> SongsTab(
                        songs = uiState.filteredSongs,
                        isLoading = uiState.isLoadingSongs && uiState.songs.isEmpty(),
                        searchQuery = if (isSearchActive) searchQuery else null,
                        onSongClicked = { song ->
                            viewModel.onSongClicked(song)
                            onSongClicked?.invoke(song, uiState.filteredSongs)
                        },
                        modifier = Modifier,
                    )
                    AudioTab.ALBUMS -> AlbumsTab(
                        albums = uiState.albums,
                        isLoading = uiState.isLoadingAlbums && uiState.albums.isEmpty(),
                        onAlbumClicked = { viewModel.onAlbumClicked(it) },
                        modifier = Modifier,
                    )
                    AudioTab.ARTISTS -> ArtistsTab(
                        artists = uiState.artists,
                        isLoading = uiState.isLoadingArtists && uiState.artists.isEmpty(),
                        onArtistClicked = { viewModel.onArtistClicked(it) },
                        modifier = Modifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun SongsTab(
    songs: List<AudioItem>,
    isLoading: Boolean,
    searchQuery: String?,
    onSongClicked: (AudioItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        isLoading -> {
            MediaLoadingState(itemCount = 8, shape = ShimmerShape.LIST_ITEM, modifier = modifier.fillMaxSize())
        }
        songs.isEmpty() -> {
            MediaEmptyState(
                icon = Icons.Filled.MusicNote,
                title = if (searchQuery != null) "No results for \"$searchQuery\"" else "No songs found",
                subtitle = if (searchQuery != null) "Try a different search term." else "Tap browse to find music on your device.",
                modifier = modifier,
            )
        }
        else -> {
            LazyColumn(
                modifier = modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(songs, key = { it.id }) { song ->
                    MediaListItem(
                        title = song.title,
                        subtitle = buildSongSubtitle(song),
                        durationMs = song.durationMs,
                        thumbnailContent = {
                            ThumbnailPlaceholder(mediaType = MediaType.AUDIO)
                        },
                        onClick = { onSongClicked(song) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AlbumsTab(
    albums: List<Album>,
    isLoading: Boolean,
    onAlbumClicked: (Album) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        isLoading -> {
            MediaLoadingState(itemCount = 6, shape = ShimmerShape.ALBUM_CARD, modifier = modifier.fillMaxSize())
        }
        albums.isEmpty() -> {
            MediaEmptyState(
                icon = Icons.Filled.MusicNote,
                title = "No albums found",
                subtitle = "Albums will appear here once music is scanned.",
                modifier = modifier,
            )
        }
        else -> {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = modifier.fillMaxSize(),
                contentPadding = PaddingValues(Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                items(albums, key = { it.id }) { album ->
                    AlbumCard(
                        title = album.title,
                        artist = album.artist,
                        trackCount = album.trackCount,
                        onClick = { onAlbumClicked(album) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtistsTab(
    artists: List<Artist>,
    isLoading: Boolean,
    onArtistClicked: (Artist) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        isLoading -> {
            MediaLoadingState(itemCount = 6, shape = ShimmerShape.LIST_ITEM, modifier = modifier.fillMaxSize())
        }
        artists.isEmpty() -> {
            MediaEmptyState(
                icon = Icons.Filled.MusicNote,
                title = "No artists found",
                subtitle = "Artists will appear here once music is scanned.",
                modifier = modifier,
            )
        }
        else -> {
            LazyColumn(
                modifier = modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(artists, key = { it.id }) { artist ->
                    MediaListItem(
                        title = artist.name,
                        subtitle = "${artist.albumCount} albums • ${artist.trackCount} tracks",
                        thumbnailContent = {
                            ThumbnailPlaceholder(mediaType = MediaType.AUDIO)
                        },
                        onClick = { onArtistClicked(artist) },
                    )
                }
            }
        }
    }
}

private fun buildSongSubtitle(song: AudioItem): String {
    val parts = mutableListOf<String>()
    song.artist?.let { parts.add(it) }
    song.album?.let { parts.add(it) }
    return parts.joinToString(" • ")
}

@PreviewLightDark
@Preview
@Composable
private fun AudioBrowserScreenPreview() {
    HzPlayerTheme {
        AudioBrowserScreen()
    }
}

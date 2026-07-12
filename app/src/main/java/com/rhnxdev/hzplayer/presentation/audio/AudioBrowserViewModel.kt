package com.rhnxdev.hzplayer.presentation.audio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhnxdev.hzplayer.core.components.SearchDelegate
import com.rhnxdev.hzplayer.domain.model.AudioItem
import com.rhnxdev.hzplayer.domain.repository.AudioRepository
import com.rhnxdev.hzplayer.domain.repository.PlayerRepository
import com.rhnxdev.hzplayer.domain.repository.UserPreferencesRepository
import com.rhnxdev.hzplayer.presentation.preview.PreviewMedia
import com.rhnxdev.hzplayer.BuildConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AudioBrowserViewModel @Inject constructor(
    private val audioRepository: AudioRepository,
    private val playerRepository: PlayerRepository,
    private val userPrefs: UserPreferencesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AudioBrowserUiState())
    val uiState: StateFlow<AudioBrowserUiState> = _uiState.asStateFlow()

    /** Reusable search state holder. */
    val search = SearchDelegate()

    /** Cached min song duration for non-coroutine reads. */
    private var cachedMinSecs: Int = 0

    /** Active load job so a forced refresh cancels the previous collection. */
    private var loadJob: kotlinx.coroutines.Job? = null

    /** Sub-jobs for album/artist re-fetch on min-duration change (cancelled before re-launch). */
    private var albumRefetchJob: kotlinx.coroutines.Job? = null
    private var artistRefetchJob: kotlinx.coroutines.Job? = null

    init {
        loadAll()
    }

    private fun loadAll(forceRefresh: Boolean = false) {
        loadJob?.cancel()
        albumRefetchJob?.cancel()
        artistRefetchJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(isLoadingSongs = true, isLoadingAlbums = true, isLoadingArtists = true)
            }

            // Cache initial value
            cachedMinSecs = userPrefs.minSongDurationSecs.first()

            // Launch parallel collection for songs, albums, and artists
            val songJob = launch {
                audioRepository.getAllSongs(forceRefresh)
                    .catch { /* fallback handled below */ }
                    .collect { songs ->
                        if (songs.isNotEmpty()) {
                            val minSecs = userPrefs.minSongDurationSecs.first()
                            val filtered = Companion.filterSongs(songs, "", minSecs)
                            _uiState.update {
                                it.copy(songs = songs, filteredSongs = filtered, isLoadingSongs = false)
                            }
                        }
                    }
            }

            // Reactively re-filter songs when min duration changes. Albums/artists
            // are re-fetched (and their prior refetch job cancelled) so we never
            // stack collectors across preference emissions.
            val minDurationJob = launch {
                userPrefs.minSongDurationSecs.collect { minSecs ->
                    cachedMinSecs = minSecs
                    val currentSongs = _uiState.value.songs
                    val filtered = Companion.filterSongs(currentSongs, "", minSecs)
                    _uiState.update { it.copy(filteredSongs = filtered) }

                    albumRefetchJob?.cancel()
                    artistRefetchJob?.cancel()
                    albumRefetchJob = launch {
                        audioRepository.getAlbums(forceRefresh = true, minDurationSecs = minSecs)
                            .catch { /* ignore */ }
                            .collect { albums -> _uiState.update { it.copy(albums = albums) } }
                    }
                    artistRefetchJob = launch {
                        audioRepository.getArtists(forceRefresh = true, minDurationSecs = minSecs)
                            .catch { /* ignore */ }
                            .collect { artists -> _uiState.update { it.copy(artists = artists) } }
                    }
                }
            }

            val albumJob = launch {
                audioRepository.getAlbums(forceRefresh, minDurationSecs = cachedMinSecs)
                    .catch { /* fallback handled below */ }
                    // Clear the spinner on the first emission even if the list is empty
                    // (an empty library must not spin forever).
                    .onEach { _uiState.update { it.copy(isLoadingAlbums = false) } }
                    .collect { albums -> _uiState.update { it.copy(albums = albums) } }
            }

            val artistJob = launch {
                audioRepository.getArtists(forceRefresh, minDurationSecs = cachedMinSecs)
                    .catch { /* fallback handled below */ }
                    .onEach { _uiState.update { it.copy(isLoadingArtists = false) } }
                    .collect { artists -> _uiState.update { it.copy(artists = artists) } }
            }

            // If after 2s any collection is still loading (edge-case fallback), force preview
            // Debug only: release builds show empty states instead of fake data.
            if (BuildConfig.DEBUG) {
                launch {
                    kotlinx.coroutines.delay(2_000)
                    val state = _uiState.value
                    if (state.isLoadingSongs || state.isLoadingAlbums || state.isLoadingArtists) {
                        applyPreviewFallback()
                    }
                }
            }
        }
    }

    /** Called when this tab regains focus — refresh from source in the background. */
    fun onTabFocused() {
        loadAll(forceRefresh = true)
    }

    private fun applyPreviewFallback() {
        viewModelScope.launch {
            val minSecs = userPrefs.minSongDurationSecs.first()
            _uiState.update {
                it.copy(
                    songs = PreviewMedia.songs,
                    albums = PreviewMedia.albums,
                    artists = PreviewMedia.artists,
                    filteredSongs = Companion.filterSongs(PreviewMedia.songs, "", minSecs),
                    isLoadingSongs = false,
                    isLoadingAlbums = false,
                    isLoadingArtists = false,
                )
            }
        }
    }

    fun onTabSelected(tab: AudioTab) {
        _uiState.update { it.copy(currentTab = tab) }
    }

    companion object {
        internal fun filterSongs(songs: List<AudioItem>, query: String, minSecs: Int): List<AudioItem> {
            val durationFiltered = if (minSecs > 0) songs.filter { it.durationMs >= minSecs * 1000L } else songs
            return if (query.isBlank()) durationFiltered
            else durationFiltered.filter { song ->
                song.title.contains(query, ignoreCase = true) ||
                    (song.artist?.contains(query, ignoreCase = true) == true) ||
                    (song.album?.contains(query, ignoreCase = true) == true)
            }
        }
    }

    fun onSearchToggle() {
        search.toggle()
        _uiState.update {
            it.copy(filteredSongs = Companion.filterSongs(it.songs, "", cachedMinSecs))
        }
    }

    fun onSearchQueryChanged(query: String) {
        search.queryChanged(query)
        _uiState.update {
            it.copy(filteredSongs = Companion.filterSongs(it.songs, query, cachedMinSecs))
        }
    }

    fun onClearSearch() {
        search.clear()
        _uiState.update {
            it.copy(filteredSongs = Companion.filterSongs(it.songs, "", cachedMinSecs))
        }
    }

    fun onSongClicked(song: AudioItem) {
        playerRepository.playAudio(song)
    }

    fun onRetry() {
        loadAll()
    }

    fun onRefresh() {
        val state = _uiState.value
        if (state.isLoadingSongs || state.isLoadingAlbums || state.isLoadingArtists) return
        loadAll(forceRefresh = true)
    }
}

package com.rhnxdev.hzplayer.domain.repository

import com.rhnxdev.hzplayer.domain.model.Album
import com.rhnxdev.hzplayer.domain.model.Artist
import com.rhnxdev.hzplayer.domain.model.AudioItem
import kotlinx.coroutines.flow.Flow

interface AudioRepository {
    fun getAllSongs(forceRefresh: Boolean = false): Flow<List<AudioItem>>
    fun getAlbums(forceRefresh: Boolean = false): Flow<List<Album>>
    fun getArtists(forceRefresh: Boolean = false): Flow<List<Artist>>
    fun getSongsByAlbum(albumTitle: String): Flow<List<AudioItem>>
    fun getSongsByArtist(artistName: String): Flow<List<AudioItem>>
    suspend fun toggleFavorite(songId: Long, isFavorite: Boolean)
    fun searchSongs(query: String): Flow<List<AudioItem>>
}

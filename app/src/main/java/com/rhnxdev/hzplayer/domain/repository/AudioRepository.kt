package com.rhnxdev.hzplayer.domain.repository

import com.rhnxdev.hzplayer.domain.model.Album
import com.rhnxdev.hzplayer.domain.model.Artist
import com.rhnxdev.hzplayer.domain.model.AudioItem
import kotlinx.coroutines.flow.Flow

interface AudioRepository {
    fun getAllSongs(): Flow<List<AudioItem>>
    fun getAlbums(): Flow<List<Album>>
    fun getArtists(): Flow<List<Artist>>
    fun getSongsByAlbum(albumId: Long): Flow<List<AudioItem>>
    fun getSongsByArtist(artistId: Long): Flow<List<AudioItem>>
    suspend fun toggleFavorite(songId: Long, isFavorite: Boolean)
    fun searchSongs(query: String): Flow<List<AudioItem>>
}

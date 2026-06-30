package com.rhnxdev.hzplayer.data.datasource.local.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.rhnxdev.hzplayer.data.datasource.local.room.entities.MediaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {

    @Query("SELECT * FROM media WHERE mediaType = 'video' ORDER BY dateAdded DESC")
    fun getAllVideos(): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media WHERE mediaType = 'audio' ORDER BY dateAdded DESC")
    fun getAllAudio(): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media WHERE id = :id")
    suspend fun getById(id: Long): MediaEntity?

    @Query("SELECT * FROM media WHERE mediaType = 'audio' AND album = :albumTitle")
    fun getSongsByAlbum(albumTitle: String): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media WHERE mediaType = 'audio' AND artist = :artistName")
    fun getSongsByArtist(artistName: String): Flow<List<MediaEntity>>

    @Query("SELECT DISTINCT album, artist, albumArtUri FROM media WHERE mediaType = 'audio' AND album IS NOT NULL ORDER BY album")
    fun getAlbums(): Flow<List<AlbumProjection>>

    @Query("SELECT DISTINCT artist FROM media WHERE mediaType = 'audio' AND artist IS NOT NULL ORDER BY artist")
    fun getArtists(): Flow<List<ArtistProjection>>

    @Query("SELECT * FROM media WHERE title LIKE '%' || :query || '%'")
    fun search(query: String): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media WHERE mediaType = 'video' AND title LIKE '%' || :query || '%'")
    fun searchVideos(query: String): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media WHERE mediaType = 'audio' AND title LIKE '%' || :query || '%'")
    fun searchAudio(query: String): Flow<List<MediaEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(media: List<MediaEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(media: MediaEntity)

    @Update
    suspend fun update(media: MediaEntity)

    @Query("UPDATE media SET isFavorite = :favorite WHERE id = :id")
    suspend fun updateFavorite(id: Long, favorite: Boolean)

    @Query("UPDATE media SET watchedProgress = :progress WHERE id = :id")
    suspend fun updateWatchedProgress(id: Long, progress: Float)

    @Query("DELETE FROM media WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM media")
    suspend fun deleteAll()
}

data class AlbumProjection(
    val album: String,
    val artist: String?,
    val albumArtUri: String?,
)

data class ArtistProjection(
    val artist: String,
)

package com.rhnxdev.hzplayer.data.mapper

import com.rhnxdev.hzplayer.data.datasource.local.room.dao.AlbumProjection
import com.rhnxdev.hzplayer.data.datasource.local.room.dao.ArtistProjection
import com.rhnxdev.hzplayer.data.datasource.local.room.entities.MediaEntity
import com.rhnxdev.hzplayer.domain.model.Album
import com.rhnxdev.hzplayer.domain.model.Artist
import com.rhnxdev.hzplayer.domain.model.AudioItem
import com.rhnxdev.hzplayer.domain.model.VideoItem

fun MediaEntity.toVideoItem(): VideoItem = VideoItem(
    id = id,
    title = title,
    uri = uri,
    durationMs = durationMs,
    fileSize = fileSize,
    resolution = resolution,
    dateAdded = dateAdded,
    dateModified = dateModified,
    mimeType = mimeType,
    isFavorite = isFavorite,
    watchedProgress = watchedProgress,
    thumbnailUri = thumbnailUri,
)

fun MediaEntity.toAudioItem(): AudioItem = AudioItem(
    id = id,
    title = title,
    uri = uri,
    artist = artist,
    album = album,
    albumArtUri = albumArtUri,
    durationMs = durationMs,
    trackNumber = trackNumber,
    fileSize = fileSize,
    dateAdded = dateAdded,
    mimeType = mimeType,
    isFavorite = isFavorite,
)

fun AlbumProjection.toAlbum(trackCount: Int = 0): Album = Album(
    id = album.hashCode().toLong(),
    title = album,
    artist = artist,
    albumArtUri = albumArtUri,
    trackCount = trackCount,
)

fun ArtistProjection.toArtist(): Artist = Artist(
    id = artist.hashCode().toLong(),
    name = artist,
)

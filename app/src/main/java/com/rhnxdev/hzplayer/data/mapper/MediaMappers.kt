package com.rhnxdev.hzplayer.data.mapper

import com.rhnxdev.hzplayer.data.datasource.local.room.entities.MediaEntity
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

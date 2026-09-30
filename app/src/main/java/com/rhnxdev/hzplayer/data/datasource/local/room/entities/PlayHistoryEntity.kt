package com.rhnxdev.hzplayer.data.datasource.local.room.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A "recently played" record — one row per media URI, most-recent play wins.
 *
 * Distinct from [StreamHistoryEntity] (browser-originated network streams) and
 * [BrowserHistoryEntity] (web-page visit history): this logs actual media playback
 * from any source (library, file browser, network) so a unified "History" list
 * can be shown. Keyed on [uri] — the universal media key (see [PlaybackPositionEntity]) —
 * so replaying an item updates the existing row instead of piling up duplicates.
 */
@Entity(
    tableName = "play_history",
    indices = [Index("playedAt")],
)
data class PlayHistoryEntity(
    @PrimaryKey val uri: String,
    val title: String,
    val isVideo: Boolean,
    val playedAt: Long,
    val artist: String? = null,
    val mimeType: String? = null,
    val thumbnailUri: String? = null,
)

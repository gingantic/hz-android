package com.rhnxdev.hzplayer.presentation.navigation

object NavRoutes {
    const val VIDEO_PLAYER = "video_player/{videoId}"
    const val VIDEO_PLAYER_NO_ID = "video_player/-1"
    const val AUDIO_PLAYER = "audio_player"
    const val SEARCH = "search"
    const val ALBUM_DETAIL = "album_detail/{title}"
    const val ARTIST_DETAIL = "artist_detail/{name}"

    // Titles/names contain spaces and '/', so URL-encode the arg.
    fun albumDetail(title: String): String =
        "album_detail/${android.net.Uri.encode(title)}"
    fun artistDetail(name: String): String =
        "artist_detail/${android.net.Uri.encode(name)}"
}

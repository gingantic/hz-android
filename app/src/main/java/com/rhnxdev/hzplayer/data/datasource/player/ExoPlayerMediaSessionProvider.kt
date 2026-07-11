package com.rhnxdev.hzplayer.data.datasource.player

import androidx.media3.common.Player
import com.rhnxdev.hzplayer.domain.player.MediaSessionProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supplies the Media3 [Player] (owned by [MediaPlayerHolder]) used to back the
 * system [androidx.media3.session.MediaSession] (media controls / notification).
 *
 * Extracted from [ExoPlayerEngine] so the engine stays focused on playback
 * control. Only a Media3-backed engine exposes this capability; a future
 * non-Media3 backend would simply not bind [MediaSessionProvider].
 */
@Singleton
class ExoPlayerMediaSessionProvider @Inject constructor(
    private val playerHolder: MediaPlayerHolder,
) : MediaSessionProvider {
    override fun getMedia3Player(): Player? = playerHolder.player
}

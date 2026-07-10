package com.rhnxdev.hzplayer.domain.player

import androidx.media3.common.Player

/**
 * Optional capability for engines that can back a Media3 [MediaSession] (system
 * media controls / notification). Only Media3-based engines implement this; a
 * non-Media3 engine (libVLC, mpv) simply does not, and the playback service
 * skips building a MediaSession for it.
 */
interface MediaSessionProvider {
    /** The Media3 [Player] to wrap in a MediaSession, or `null` if unavailable. */
    fun getMedia3Player(): Player?
}

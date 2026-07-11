package com.rhnxdev.hzplayer.data.datasource.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.rhnxdev.hzplayer.MainActivity
import com.rhnxdev.hzplayer.domain.player.MediaSessionProvider
import com.rhnxdev.hzplayer.domain.player.EngineType
import com.rhnxdev.hzplayer.domain.repository.PlayerRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@UnstableApi
@AndroidEntryPoint
class MediaPlaybackService : MediaSessionService() {

    @Inject lateinit var playerRepository: PlayerRepository
    @Inject lateinit var mediaSessionProvider: MediaSessionProvider

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        // Only Media3-backed engines can supply a Player for the system MediaSession
        // (notification / media controls). Non-Media3 engines run without it for now.
        val player = if (playerRepository.activeEngine.engineType == EngineType.EXO_PLAYER) {
            mediaSessionProvider.getMedia3Player()
        } else {
            null
        }

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE,
        )

        mediaSession = player?.let {
            MediaSession.Builder(this, it)
                .setSessionActivity(pendingIntent)
                .build()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = if (playerRepository.activeEngine.engineType == EngineType.EXO_PLAYER) {
            mediaSessionProvider.getMedia3Player()
        } else {
            null
        }
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            release()
            mediaSession = null
        }
        // Release the active engine's resources (parity with the old direct
        // playerHolder.release()). All registered engines are released.
        playerRepository.release()
        super.onDestroy()
    }
}

package com.rhnxdev.hzplayer.data.datasource.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.rhnxdev.hzplayer.MainActivity
import com.rhnxdev.hzplayer.domain.repository.PlayerRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@UnstableApi
@AndroidEntryPoint
class MediaPlaybackService : MediaSessionService() {

    @Inject lateinit var playerRepository: PlayerRepository

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val player = playerRepository.activeEngine.getMedia3Player()

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE,
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = playerRepository.activeEngine.getMedia3Player()
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run { release(); mediaSession = null }
        playerRepository.release()
        super.onDestroy()
    }
}

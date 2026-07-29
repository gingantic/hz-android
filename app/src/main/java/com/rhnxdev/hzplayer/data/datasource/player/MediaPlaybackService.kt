package com.rhnxdev.hzplayer.data.datasource.player

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
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
    private var engineRef: com.rhnxdev.hzplayer.domain.player.IPlayerEngine? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")

        val engine = playerRepository.activeEngine
        engineRef = engine
        val player = engine.getMedia3Player()

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE,
        )

        mediaSession = player?.let {
            MediaSession.Builder(this, it)
                .setSessionActivity(pendingIntent)
                .build()
                // The UI drives the player directly and never connects a
                // MediaController, so onGetSession is never called. The session
                // must be registered explicitly — only added sessions get the
                // service-managed media notification (and startForeground).
                .also { session -> addSession(session) }
        }

        // When the engine swaps its underlying player (decoder rebuild), re-point
        // the MediaSession at the new instance. Without this the session keeps
        // wrapping the released player and lock-screen controls go dead.
        engine.setOnPlayerReplacedListener { newPlayer ->
            mediaSession?.setPlayer(newPlayer)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = playerRepository.activeEngine.getMedia3Player()
        val shouldStop = player == null || !player.playWhenReady || player.mediaItemCount == 0
        Log.i(TAG, "onTaskRemoved: stopSelf=$shouldStop")
        if (shouldStop) {
            player?.pause()
            stopSelf()
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        // Only tear down the session — the player is an app-scoped singleton
        // owned by MediaPlayerHolder, so the service must NOT release it. The
        // engine keeps working in-app; a new session wraps it on the next play.
        engineRef?.setOnPlayerReplacedListener(null)
        engineRef = null
        mediaSession?.run { release(); mediaSession = null }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MediaPlaybackService"
    }
}

package com.rhnxdev.hzplayer

import android.app.PictureInPictureParams
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.rhnxdev.hzplayer.core.util.extractHttpHeaders
import com.rhnxdev.hzplayer.presentation.main.MainViewModel
import com.rhnxdev.hzplayer.presentation.player.PlayerViewModel
import com.rhnxdev.hzplayer.presentation.player.VideoPlayerScreen
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class VideoPlayerActivity : ComponentActivity() {

    private val playerViewModel: PlayerViewModel by viewModels()
    private val mainViewModel: MainViewModel by viewModels()

    private var pipEligible by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate")
        enableEdgeToEdge()
        window.setBackgroundDrawableResource(android.R.color.black)

        handleIntent(intent)

        setContent {
            val themeMode by mainViewModel.themeMode.collectAsStateWithLifecycle()
            val appColorArgb by mainViewModel.appColorArgb.collectAsStateWithLifecycle()
            val useDynamicColors by mainViewModel.useDynamicColors.collectAsStateWithLifecycle()
            val floatingEnabled by playerViewModel.backgroundPlay.collectAsStateWithLifecycle()
            val playerState by playerViewModel.uiState.collectAsStateWithLifecycle()

            val isPipEligibleNow = floatingEnabled && playerState.isVideo && playerState.currentTitle != null
            LaunchedEffect(isPipEligibleNow) {
                pipEligible = isPipEligibleNow
            }

            LaunchedEffect(playerState.isPlaying, pipEligible) {
                if (pipEligible && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    buildPipParams(playerState.isPlaying)?.let { params ->
                        setPictureInPictureParams(params)
                    }
                }
            }

            HzPlayerTheme(
                themeMode = themeMode,
                appColorArgb = appColorArgb,
                dynamicColor = useDynamicColors
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    VideoPlayerScreen(
                        viewModel = playerViewModel,
                        assHandler = playerViewModel.assHandler,
                        onBack = {
                            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                            playerViewModel.isShuttingDown = true
                            playerViewModel.stop()
                            finish()
                        },
                        onMinimize = {
                            playerViewModel.isMinimizing = true
                            finish()
                        },
                        onPlayAsAudio = {
                            // ViewModel flipped isVideo off + set isMinimizing; the
                            // MediaSession notification keeps the audio controllable.
                            finish()
                        }
                    )
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pipReceiver, android.content.IntentFilter(ACTION_PIP_PLAY_PAUSE), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(pipReceiver, android.content.IntentFilter(ACTION_PIP_PLAY_PAUSE))
        }
    }

    private val pipReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            // Only react while THIS activity is the one in PiP — MainActivity
            // registers a receiver for the same action, and PlayerRepository is a
            // singleton, so handling it in both would toggle play/pause twice.
            if (intent?.action == ACTION_PIP_PLAY_PAUSE && isInPictureInPictureMode) {
                playerViewModel.onPlayPause()
            }
        }
    }

    private fun buildPipParams(isPlaying: Boolean): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val actions = ArrayList<android.app.RemoteAction>()
        val actionIntent = Intent(ACTION_PIP_PLAY_PAUSE).setPackage(packageName)
        val pendingIntent = android.app.PendingIntent.getBroadcast(
            this,
            0,
            actionIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val iconRes = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val title = if (isPlaying) "Pause" else "Play"
        val action = android.app.RemoteAction(
            android.graphics.drawable.Icon.createWithResource(this, iconRes),
            title,
            title,
            pendingIntent
        )
        actions.add(action)
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(actions)
            .build()
    }

    @javax.inject.Inject
    lateinit var networkRepository: com.rhnxdev.hzplayer.domain.repository.NetworkRepository

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            val uri = intent.data.toString()
            val headers = extractHttpHeaders(intent)
            val mimeType = intent.type
            val extraTitle = intent.getStringExtra("extra_media_title")
            val pageUrl = intent.getStringExtra("extra_page_url") ?: headers?.get("Referer") ?: headers?.get("referer") ?: ""
            val headersJson = intent.getStringExtra("extra_headers_json") ?: headers?.let {
                try { org.json.JSONObject(it as Map<*, *>).toString() } catch (_: Exception) { null }
            }
            val fileName = extraTitle ?: uri.substringAfterLast('/').substringBefore('?')

            Log.i(TAG, "Received Video VIEW intent: $uri (mimeType=$mimeType)")
            playerViewModel.playUri(uri, fileName, isVideo = true, mimeType = mimeType, headers = headers ?: emptyMap())
            playerViewModel.onVideoStarted()

            // Save stream history with full session details (headers, cookies, referer, mime)
            if (intent.getBooleanExtra("from_browser", false) || !headersJson.isNullOrBlank() || pageUrl.isNotBlank()) {
                saveStreamHistory(uri, fileName, headersJson, pageUrl, mimeType)
            }
        }
    }

    private fun saveStreamHistory(
        url: String,
        title: String,
        headersJson: String?,
        pageUrl: String?,
        mimeType: String?
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                networkRepository.addStreamToHistory(
                    url = url,
                    title = title,
                    headersJson = headersJson,
                    pageUrl = pageUrl,
                    mimeType = mimeType
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save stream to history: ${e.message}")
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (pipEligible && !isInPictureInPictureMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = buildPipParams(playerViewModel.uiState.value.isPlaying)
                ?: PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
            enterPictureInPictureMode(params)
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(pipReceiver)
        } catch (_: Exception) {}
        if (!pipEligible && !playerViewModel.isMinimizing) {
            playerViewModel.isShuttingDown = true
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VideoPlayerActivity"
        private const val ACTION_PIP_PLAY_PAUSE = "com.rhnxdev.hzplayer.ACTION_PIP_PLAY_PAUSE"
    }
}

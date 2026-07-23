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
                        }
                    )
                }
            }
        }
    }

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
            val fileName = extraTitle ?: uri.substringAfterLast('/').substringBefore('?')

            Log.i(TAG, "Received Video VIEW intent: $uri (mimeType=$mimeType)")
            playerViewModel.playUri(uri, fileName, isVideo = true, mimeType = mimeType, headers = headers ?: emptyMap())
            playerViewModel.onVideoStarted()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (pipEligible && !isInPictureInPictureMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    override fun onDestroy() {
        if (!pipEligible) {
            playerViewModel.isShuttingDown = true
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VideoPlayerActivity"
    }
}

package com.rhnxdev.hzplayer

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhnxdev.hzplayer.core.util.extractHttpHeaders
import com.rhnxdev.hzplayer.presentation.main.MainViewModel
import com.rhnxdev.hzplayer.presentation.player.AudioPlayerScreen
import com.rhnxdev.hzplayer.presentation.player.PlayerViewModel
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class AudioPlayerActivity : ComponentActivity() {

    private val playerViewModel: PlayerViewModel by viewModels()
    private val mainViewModel: MainViewModel by viewModels()

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
                    AudioPlayerScreen(
                        viewModel = playerViewModel,
                        onBack = { finish() }
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

            Log.i(TAG, "Received Audio VIEW intent: $uri (mimeType=$mimeType)")
            playerViewModel.playUri(uri, fileName, isVideo = false, mimeType = mimeType, headers = headers ?: emptyMap())
        }
    }

    companion object {
        private const val TAG = "AudioPlayerActivity"
    }
}

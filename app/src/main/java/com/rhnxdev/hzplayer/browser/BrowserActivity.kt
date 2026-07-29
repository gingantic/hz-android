package com.rhnxdev.hzplayer.browser

import android.app.Activity
import android.app.PictureInPictureParams
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.hilt.navigation.compose.hiltViewModel
import com.rhnxdev.hzplayer.browser.ui.BrowserScreen
import com.rhnxdev.hzplayer.domain.model.ThemeMode
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class BrowserActivity : ComponentActivity() {
    private var browserViewModel: BrowserViewModel? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val themeModeName = intent.getStringExtra(THEME_MODE) ?: ThemeMode.DARK.name
        val themeMode = try { ThemeMode.valueOf(themeModeName) } catch (_: Exception) { ThemeMode.DARK }
        val appColorArgb = intent.getIntExtra(APP_COLOR, 0xFFE85E00.toInt())
        val useDynamicColor = intent.getBooleanExtra(USE_DYNAMIC_COLOR, false)
        val initialUrl = intent.getStringExtra(INITIAL_URL) ?: ""

        setContent {
            val viewModel: BrowserViewModel = hiltViewModel()
            browserViewModel = viewModel

            // Set initial URL before BrowserScreen initializes
            if (initialUrl.isNotBlank() && viewModel.initialUrl.isBlank()) {
                viewModel.initialUrl = initialUrl
            }

            // Site PiP button (web Picture-in-Picture API) → native PiP
            viewModel.tabManager.onPipRequested = { enterBrowserPip() }

            HzPlayerTheme(
                themeMode = themeMode,
                appColorArgb = appColorArgb,
                dynamicColor = useDynamicColor,
            ) {
                BrowserScreen(viewModel = viewModel)
            }
        }
    }

    /** Enter system PiP so the page video keeps rendering in a floating window. */
    fun enterBrowserPip() {
        if (isInPictureInPictureMode) return
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .build()
        try {
            enterPictureInPictureMode(params)
        } catch (_: Exception) {
            // Device/OEM may reject PiP (e.g. disabled in system settings)
        }
    }

    /**
     * Home / recents while a page video is playing (or HTML5 fullscreen is
     * active) → enter PiP instead of backgrounding into an audio-only card.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (browserViewModel?.tabManager?.isVideoPlaying == true) {
            enterBrowserPip()
        }
    }

    override fun onPause() {
        super.onPause()
        // In PiP the activity is paused but must keep the WebView video running
        if (!isInPictureInPictureMode) {
            browserViewModel?.onPause()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // PiP window dismissed while backgrounded → pause the WebView now,
        // since the earlier onPause skipped it to keep the video running
        if (!isInPictureInPictureMode &&
            !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        ) {
            browserViewModel?.onPause()
        }
    }

    override fun onResume() {
        super.onResume()
        browserViewModel?.onResume()
    }


    companion object {
        const val THEME_MODE = "theme_mode"
        const val APP_COLOR = "app_color_argb"
        const val USE_DYNAMIC_COLOR = "use_dynamic_color"
        const val INITIAL_URL = "initial_url"
    }
}

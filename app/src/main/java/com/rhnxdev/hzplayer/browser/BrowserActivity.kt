package com.rhnxdev.hzplayer.browser

import android.app.Activity
import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        AdBlockEngine.init(this)

        val themeModeName = intent.getStringExtra(THEME_MODE) ?: ThemeMode.DARK.name
        val themeMode = try { ThemeMode.valueOf(themeModeName) } catch (_: Exception) { ThemeMode.DARK }
        val appColorArgb = intent.getIntExtra(APP_COLOR, 0xFFE85E00.toInt())
        val useDynamicColor = intent.getBooleanExtra(USE_DYNAMIC_COLOR, false)
        val initialUrl = intent.getStringExtra(INITIAL_URL) ?: ""

        setContent {
            val viewModel: BrowserViewModel = hiltViewModel()

            // Set initial URL before BrowserScreen initializes
            if (initialUrl.isNotBlank() && viewModel.initialUrl.isBlank()) {
                viewModel.initialUrl = initialUrl
            }

            HzPlayerTheme(
                themeMode = themeMode,
                appColorArgb = appColorArgb,
                dynamicColor = useDynamicColor,
            ) {
                BrowserScreen(viewModel = viewModel)
            }
        }
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
    }

    companion object {
        const val THEME_MODE = "theme_mode"
        const val APP_COLOR = "app_color_argb"
        const val USE_DYNAMIC_COLOR = "use_dynamic_color"
        const val INITIAL_URL = "initial_url"
    }
}

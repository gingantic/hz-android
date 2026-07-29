package com.rhnxdev.hzplayer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhnxdev.hzplayer.browser.BrowserActivity
import com.rhnxdev.hzplayer.core.util.extractHttpHeaders
import com.rhnxdev.hzplayer.presentation.main.HzPlayerApp
import com.rhnxdev.hzplayer.presentation.main.MainViewModel
import com.rhnxdev.hzplayer.presentation.player.PlayerViewModel
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val playerViewModel: PlayerViewModel by viewModels()

    /** Tracks whether any permission was denied on the last attempt. */
    private var permissionDenied by mutableStateOf(false)

    /**
     * Whether the in-app floating window (and thus system PiP) is currently
     * eligible: toggle on + a video is playing with a known title. Updated from
     * [HzPlayerApp] each recomposition so the Activity-level [onUserLeaveHint]
     * can read it without its own collector.
     */
    private var pipEligible by mutableStateOf(false)

    /** Media URI received from an external VIEW intent (file chooser / share). */
    private var incomingMediaUri by mutableStateOf<String?>(null)

    /** HTTP headers (e.g. auth token) supplied alongside a VIEW intent URI. */
    private var incomingMediaHeaders by mutableStateOf<Map<String, String>?>(null)

    /** MIME type from the VIEW intent, used to reliably detect video vs audio. */
    private var incomingMimeType by mutableStateOf<String?>(null)

    /** Optional title override from the browser (instead of deriving from URL). */
    private var incomingMediaTitle by mutableStateOf<String?>(null)

    /** Whether the incoming VIEW intent originated from HzPlayer in-app browser. */
    private var incomingFromBrowser by mutableStateOf(false)

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grantResults ->
        val allGranted = grantResults.values.all { it }
        permissionDenied = !allGranted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate")
        enableEdgeToEdge()
        requestMediaPermissions()
        window.setBackgroundDrawableResource(android.R.color.black)
        handleViewIntent(intent)
        setContent {
            val mainViewModel: MainViewModel = hiltViewModel()
            val mainUiState by mainViewModel.uiState.collectAsStateWithLifecycle()
            val themeMode by mainViewModel.themeMode.collectAsStateWithLifecycle()
            val appColorArgb by mainViewModel.appColorArgb.collectAsStateWithLifecycle()
            val useDynamicColors by mainViewModel.useDynamicColors.collectAsStateWithLifecycle()

            HzPlayerTheme(
                themeMode = themeMode,
                appColorArgb = appColorArgb,
                dynamicColor = useDynamicColors
            ) {
                if (mainUiState.isReady) {
                    HzPlayerApp(
                        initialTabIndex = mainUiState.selectedTabIndex,
                        permissionDenied = permissionDenied,
                        onRequestPermissions = { requestMediaPermissions() },
                        onPipEligibilityChange = { pipEligible = it },
                        incomingMediaUri = incomingMediaUri,
                        incomingMediaHeaders = incomingMediaHeaders,
                        incomingMimeType = incomingMimeType,
                        incomingMediaTitle = incomingMediaTitle,
                        incomingFromBrowser = incomingFromBrowser,
                        onIncomingMediaConsumed = {
                            incomingMediaUri = null
                            incomingMediaHeaders = null
                            incomingMimeType = null
                            incomingMediaTitle = null
                            incomingFromBrowser = false
                        },
                        onOpenBrowser = { url ->
                            val intent = Intent(this@MainActivity, BrowserActivity::class.java).apply {
                                putExtra(BrowserActivity.THEME_MODE, themeMode.name)
                                putExtra(BrowserActivity.APP_COLOR, appColorArgb)
                                putExtra(BrowserActivity.USE_DYNAMIC_COLOR, useDynamicColors)
                                if (!url.isNullOrBlank()) {
                                    putExtra(BrowserActivity.INITIAL_URL, url)
                                }
                            }
                            startActivity(intent)
                        },
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    )
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pipReceiver, IntentFilter(ACTION_PIP_PLAY_PAUSE), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(pipReceiver, IntentFilter(ACTION_PIP_PLAY_PAUSE))
        }
    }

    private val pipReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Only react while THIS activity is the one in PiP — VideoPlayerActivity
            // registers a receiver for the same action, and PlayerRepository is a
            // singleton, so handling it in both would toggle play/pause twice.
            if (intent?.action == ACTION_PIP_PLAY_PAUSE && isInPictureInPictureMode) {
                playerViewModel.onPlayPause()
            }
        }
    }

    fun buildPipParams(isPlaying: Boolean): android.app.PictureInPictureParams? {
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
        return android.app.PictureInPictureParams.Builder()
            .setAspectRatio(android.util.Rational(16, 9))
            .setActions(actions)
            .build()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(pipReceiver)
        } catch (_: Exception) {}
        playerViewModel.isShuttingDown = true
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
    }

    /** Extract the media URI (and any HTTP headers) from a VIEW intent. */
    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            incomingMediaUri = intent.data.toString()
            incomingMediaHeaders = extractHttpHeaders(intent)
            incomingMimeType = intent.type
            incomingMediaTitle = intent.getStringExtra("extra_media_title")
            incomingFromBrowser = intent.getBooleanExtra("from_browser", false)
            Log.i(TAG, "Received VIEW intent: $incomingMediaUri (fromBrowser=$incomingFromBrowser, type=${incomingMimeType}, headers=${incomingMediaHeaders?.size ?: 0})")
        }
    }

    /**
     * Fires when the user backgrounds the app (Home / recents / app-switch) —
     * NOT on Back. If a floating-eligible video is playing, enter system PiP.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (pipEligible && !isInPictureInPictureMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = buildPipParams(playerViewModel.uiState.value.isPlaying)
                ?: android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16, 9)).build()
            enterPictureInPictureMode(params)
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    }

    fun requestMediaPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissions.isNotEmpty()) {
            Log.i(TAG, "requestMediaPermissions: requesting $permissions")
            requestPermissionsLauncher.launch(permissions.toTypedArray())
        } else {
            Log.d(TAG, "requestMediaPermissions: all permissions already granted")
        }
    }

    companion object {
        private const val TAG = "MainActivity"

        /**
         * Check whether the app has [android.Manifest.permission.MANAGE_EXTERNAL_STORAGE]
         * (i.e. full file-system access).
         */
        fun isFullStorageGranted(): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                android.os.Environment.isExternalStorageManager()

        /** True when the app holds the granular media-read permissions it requests. */
        fun isMediaPermissionGranted(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
            }
            return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

        fun openFullStorageSettings(context: Context) {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
                ).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                ).apply {
                    data = android.net.Uri.fromParts("package", context.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
        }

        fun openAppSettings(context: Context) {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            ).apply {
                data = android.net.Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}

private const val ACTION_PIP_PLAY_PAUSE = "com.rhnxdev.hzplayer.ACTION_PIP_PLAY_PAUSE"

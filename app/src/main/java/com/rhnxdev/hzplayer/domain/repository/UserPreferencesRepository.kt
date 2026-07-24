package com.rhnxdev.hzplayer.domain.repository

import com.rhnxdev.hzplayer.domain.model.DecoderMode
import com.rhnxdev.hzplayer.domain.model.OrientationMode
import com.rhnxdev.hzplayer.domain.model.ResumeMode
import com.rhnxdev.hzplayer.domain.model.SortDirection
import com.rhnxdev.hzplayer.domain.model.SortType
import com.rhnxdev.hzplayer.domain.model.ThemeMode
import com.rhnxdev.hzplayer.domain.model.ViewMode
import com.rhnxdev.hzplayer.domain.player.EngineType
import kotlinx.coroutines.flow.Flow

interface UserPreferencesRepository {
    val themeMode: Flow<ThemeMode>
    val appColorArgb: Flow<Int>
    val useDynamicColors: Flow<Boolean>
    val activeEngine: Flow<EngineType>
    val subdlApiKey: Flow<String>
    val seekSensitivity: Flow<Float>
    val showHiddenFiles: Flow<Boolean>
    val useSurfaceView: Flow<Boolean>
    val decoderMode: Flow<DecoderMode>
    val fileBrowserMediaMode: Flow<Boolean>
    val orientationMode: Flow<OrientationMode>
    val resumeMode: Flow<ResumeMode>

    fun getViewMode(key: String): Flow<ViewMode>
    fun getSortType(key: String): Flow<SortType>
    fun getSortDirection(key: String): Flow<SortDirection>
    val minSongDurationSecs: Flow<Int>
    val debugMode: Flow<Boolean>
    val backgroundPlay: Flow<Boolean>
    /** Show the YouTube-style watched-progress line on video thumbnails. */
    val showWatchProgress: Flow<Boolean>

    /** Last player volume level (0.0–1.0). Restored when the player opens. */
    val lastVolume: Flow<Float>
    /** Last screen brightness level (0.0–1.0). -1f means "use system brightness". */
    val lastBrightness: Flow<Float>
    /** Whether to remember and restore last brightness & volume state. */
    val saveVolumeBrightnessState: Flow<Boolean>

    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setAppColorArgb(argb: Int)
    suspend fun setDynamicColors(enabled: Boolean)
    suspend fun setActiveEngine(engine: EngineType)
    suspend fun setSubdlApiKey(key: String)
    suspend fun setSeekSensitivity(sensitivity: Float)
    suspend fun setShowHiddenFiles(enabled: Boolean)
    suspend fun setUseSurfaceView(enabled: Boolean)
    suspend fun setDecoderMode(mode: DecoderMode)
    suspend fun setFileBrowserMediaMode(enabled: Boolean)
    suspend fun setOrientationMode(mode: OrientationMode)
    suspend fun setResumeMode(mode: ResumeMode)
    suspend fun setViewMode(key: String, mode: ViewMode)
    suspend fun setSortType(key: String, sort: SortType)
    suspend fun setSortDirection(key: String, direction: SortDirection)
    suspend fun setMinSongDurationSecs(seconds: Int)
    suspend fun setDebugMode(enabled: Boolean)
    suspend fun setBackgroundPlay(enabled: Boolean)
    suspend fun setShowWatchProgress(enabled: Boolean)
    suspend fun setLastVolume(volume: Float)
    suspend fun setLastBrightness(brightness: Float)
    suspend fun setSaveVolumeBrightnessState(enabled: Boolean)

    val selectedTabIndex: Flow<Int>

    suspend fun setSelectedTabIndex(index: Int)

    /** Ordered list of recent subtitle search queries (newest first, max 10). */
    val subtitleSearchHistory: Flow<List<String>>
    suspend fun setSubtitleSearchHistory(history: List<String>)

    /** Version code the user dismissed the startup update dialog for (0 = never dismissed). */
    val dismissedUpdateVersionCode: Flow<Int>
    suspend fun setDismissedUpdateVersionCode(versionCode: Int)

    /** Persisted archive passwords keyed by container path. */
    val archivePasswords: Flow<Map<String, String>>
    suspend fun setArchivePassword(container: String, password: String)
    suspend fun removeArchivePassword(container: String)

    /** Set of folder paths attached to Quick Access in File Browser. */
    val quickAccessFolders: Flow<Set<String>>
    suspend fun setQuickAccessFolders(folders: Set<String>)
    suspend fun toggleQuickAccessFolder(path: String)

}

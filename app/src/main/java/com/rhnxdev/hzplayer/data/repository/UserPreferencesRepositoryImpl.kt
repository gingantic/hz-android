package com.rhnxdev.hzplayer.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import com.rhnxdev.hzplayer.domain.model.DecoderMode
import com.rhnxdev.hzplayer.domain.model.OrientationMode
import com.rhnxdev.hzplayer.domain.model.ResumeMode
import com.rhnxdev.hzplayer.domain.model.SortDirection
import com.rhnxdev.hzplayer.domain.model.SortType
import com.rhnxdev.hzplayer.domain.model.SubtitleStyle
import com.rhnxdev.hzplayer.domain.model.ThemeMode
import com.rhnxdev.hzplayer.domain.model.ViewMode
import com.rhnxdev.hzplayer.domain.player.EngineType
import com.rhnxdev.hzplayer.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserPreferencesRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : UserPreferencesRepository {

    override val themeMode: Flow<ThemeMode> = dataStore.data.map { prefs ->
        val themeName = prefs[PrefKey.ThemeMode.key]
        if (themeName != null) {
            try {
                ThemeMode.valueOf(themeName)
            } catch (_: IllegalArgumentException) {
                ThemeMode.DARK
            }
        } else {
            // Check legacy boolean key
            val isDark = prefs[PrefKey.DarkTheme.key] ?: false
            if (isDark) ThemeMode.DARK else ThemeMode.LIGHT
        }
    }.distinctUntilChanged()

    override val appColorArgb: Flow<Int> = dataStore.data.map { prefs ->
        prefs[PrefKey.AppColorArgb.key] ?: 0xFFE85E00.toInt()
    }.distinctUntilChanged()

    override val useDynamicColors: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[PrefKey.DynamicColors.key] ?: false
    }.distinctUntilChanged()

    override val activeEngine: Flow<EngineType> = dataStore.data.map { prefs ->
        val name = prefs[PrefKey.Engine.key]
        try {
            name?.let { EngineType.valueOf(it) } ?: EngineType.EXO_PLAYER
        } catch (_: IllegalArgumentException) {
            EngineType.EXO_PLAYER
        }
    }.distinctUntilChanged()

    // ponytail: OpenSubtitles API key is stored in PLAINTEXT in this DataStore.
    // The store is app-private (mode 0600, same-UID only), so risk is limited to
    // a rooted device. Upgrade path if this becomes sensitive: migrate to
    // androidx.security EncryptedSharedPreferences (AES via AndroidKeyStore).
    override val openSubtitlesApiKey: Flow<String> = dataStore.data.map { prefs ->
        prefs[PrefKey.OpenSubtitlesApiKey.key] ?: ""
    }.distinctUntilChanged()

    override val seekSensitivity: Flow<Float> = dataStore.data.map { prefs ->
        prefs[PrefKey.SeekSensitivity.key] ?: 1.0f
    }.distinctUntilChanged()

    override val showHiddenFiles: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[PrefKey.ShowHiddenFiles.key] ?: false
    }.distinctUntilChanged()

    override val useSurfaceView: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[PrefKey.UseSurfaceView.key] ?: true
    }.distinctUntilChanged()

    override val decoderMode: Flow<DecoderMode> = dataStore.data.map { prefs ->
        val name = prefs[PrefKey.DecoderMode.key]
        try {
            name?.let { DecoderMode.valueOf(it) } ?: DecoderMode.AUTO
        } catch (_: IllegalArgumentException) {
            DecoderMode.AUTO
        }
    }.distinctUntilChanged()

    override val fileBrowserMediaMode: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[PrefKey.FileBrowserMediaMode.key] ?: false
    }.distinctUntilChanged()

    override val orientationMode: Flow<OrientationMode> = dataStore.data.map { prefs ->
        val name = prefs[PrefKey.OrientationMode.key]
        try {
            name?.let { OrientationMode.valueOf(it) } ?: OrientationMode.AUTO
        } catch (_: IllegalArgumentException) {
            OrientationMode.AUTO
        }
    }.distinctUntilChanged()

    override val resumeMode: Flow<ResumeMode> = dataStore.data.map { prefs ->
        val name = prefs[PrefKey.ResumeMode.key]
        try {
            name?.let { ResumeMode.valueOf(it) } ?: ResumeMode.ALWAYS
        } catch (_: IllegalArgumentException) {
            ResumeMode.ALWAYS
        }
    }.distinctUntilChanged()

    override val minSongDurationSecs: Flow<Int> = dataStore.data.map { prefs ->
        prefs[PrefKey.MinSongDurationSecs.key] ?: 0
    }.distinctUntilChanged()

    override val debugMode: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[PrefKey.DebugMode.key] ?: false
    }.distinctUntilChanged()

    override val backgroundPlay: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[PrefKey.BackgroundPlay.key] ?: false
    }.distinctUntilChanged()

    override val subtitleStyle: Flow<SubtitleStyle> = dataStore.data.map { prefs ->
        SubtitleStyle(
            fontSizeSp = prefs[PrefKey.SubFontSize.key] ?: SubtitleStyle.DEFAULT.fontSizeSp,
            textColorArgb = (prefs[PrefKey.SubTextColor.key] ?: SubtitleStyle.DEFAULT.textColorArgb.toLong()).toInt(),
            backgroundColorArgb = (prefs[PrefKey.SubBgColor.key] ?: SubtitleStyle.DEFAULT.backgroundColorArgb.toLong()).toInt(),
            edgeStyle = prefs[PrefKey.SubEdgeStyle.key] ?: SubtitleStyle.DEFAULT.edgeStyle,
            enabled = prefs[PrefKey.SubEnabled.key] ?: SubtitleStyle.DEFAULT.enabled,
        )
    }.distinctUntilChanged()

    override fun getViewMode(key: String): Flow<ViewMode> = dataStore.data.map { prefs ->
        val name = prefs[stringPreferencesKey("view_mode_$key")]
        try {
            name?.let { ViewMode.valueOf(it) } ?: ViewMode.GRID
        } catch (_: IllegalArgumentException) {
            ViewMode.GRID
        }
    }.distinctUntilChanged()

    override fun getSortType(key: String): Flow<SortType> = dataStore.data.map { prefs ->
        val name = prefs[stringPreferencesKey("sort_type_$key")]
        try {
            name?.let { SortType.valueOf(it) } ?: SortType.TITLE
        } catch (_: IllegalArgumentException) {
            SortType.TITLE
        }
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { prefs -> prefs[PrefKey.ThemeMode.key] = mode.name }
    }

    override suspend fun setAppColorArgb(argb: Int) {
        dataStore.edit { prefs -> prefs[PrefKey.AppColorArgb.key] = argb }
    }

    override suspend fun setDynamicColors(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[PrefKey.DynamicColors.key] = enabled }
    }

    override suspend fun setActiveEngine(engine: EngineType) {
        dataStore.edit { prefs -> prefs[PrefKey.Engine.key] = engine.name }
    }

    override suspend fun setViewMode(key: String, mode: ViewMode) {
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("view_mode_$key")] = mode.name
        }
    }

    override suspend fun setSortType(key: String, sort: SortType) {
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("sort_type_$key")] = sort.name
        }
    }

    override fun getSortDirection(key: String): Flow<SortDirection> = dataStore.data.map { prefs ->
        val name = prefs[stringPreferencesKey("sort_dir_$key")]
        try {
            name?.let { SortDirection.valueOf(it) } ?: SortDirection.ASCENDING
        } catch (_: IllegalArgumentException) {
            SortDirection.ASCENDING
        }
    }

    override suspend fun setSortDirection(key: String, direction: SortDirection) {
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("sort_dir_$key")] = direction.name
        }
    }

    override suspend fun setSubtitleStyle(style: SubtitleStyle) {
        dataStore.edit { prefs ->
            prefs[PrefKey.SubFontSize.key] = style.fontSizeSp
            prefs[PrefKey.SubTextColor.key] = style.textColorArgb.toLong()
            prefs[PrefKey.SubBgColor.key] = style.backgroundColorArgb.toLong()
            prefs[PrefKey.SubEdgeStyle.key] = style.edgeStyle
            prefs[PrefKey.SubEnabled.key] = style.enabled
        }
    }

    override suspend fun setOpenSubtitlesApiKey(key: String) {
        dataStore.edit { prefs ->
            prefs[PrefKey.OpenSubtitlesApiKey.key] = key
        }
    }

    override suspend fun setSeekSensitivity(sensitivity: Float) {
        dataStore.edit { prefs ->
            prefs[PrefKey.SeekSensitivity.key] = sensitivity
        }
    }

    override val selectedTabIndex: Flow<Int> = dataStore.data.map { prefs ->
        prefs[PrefKey.SelectedTabIndex.key] ?: 0
    }.distinctUntilChanged()

    override suspend fun setSelectedTabIndex(index: Int) {
        dataStore.edit { prefs -> prefs[PrefKey.SelectedTabIndex.key] = index }
    }

    override suspend fun setShowHiddenFiles(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[PrefKey.ShowHiddenFiles.key] = enabled }
    }

    override suspend fun setUseSurfaceView(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[PrefKey.UseSurfaceView.key] = enabled }
    }

    override suspend fun setDecoderMode(mode: DecoderMode) {
        dataStore.edit { prefs -> prefs[PrefKey.DecoderMode.key] = mode.name }
    }

    override suspend fun setFileBrowserMediaMode(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[PrefKey.FileBrowserMediaMode.key] = enabled }
    }

    override suspend fun setOrientationMode(mode: OrientationMode) {
        dataStore.edit { prefs -> prefs[PrefKey.OrientationMode.key] = mode.name }
    }

    override suspend fun setResumeMode(mode: ResumeMode) {
        dataStore.edit { prefs -> prefs[PrefKey.ResumeMode.key] = mode.name }
    }

    override suspend fun setMinSongDurationSecs(seconds: Int) {
        dataStore.edit { prefs -> prefs[PrefKey.MinSongDurationSecs.key] = seconds }
    }

    override suspend fun setDebugMode(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[PrefKey.DebugMode.key] = enabled }
    }

    override suspend fun setBackgroundPlay(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[PrefKey.BackgroundPlay.key] = enabled }
    }

    private sealed class PrefKey<T>(val key: Preferences.Key<T>) {
        object ThemeMode : PrefKey<String>(stringPreferencesKey("theme_mode"))
        object AppColorArgb : PrefKey<Int>(intPreferencesKey("app_color_argb"))
        object DarkTheme : PrefKey<Boolean>(booleanPreferencesKey("dark_theme"))
        object DynamicColors : PrefKey<Boolean>(booleanPreferencesKey("dynamic_colors"))
        object Engine : PrefKey<String>(stringPreferencesKey("active_engine"))
        object SubFontSize : PrefKey<Int>(intPreferencesKey("subtitle_font_size"))
        object SubTextColor : PrefKey<Long>(longPreferencesKey("subtitle_text_color"))
        object SubBgColor : PrefKey<Long>(longPreferencesKey("subtitle_bg_color"))
        object SubEdgeStyle : PrefKey<Int>(intPreferencesKey("subtitle_edge_style"))
        object SubEnabled : PrefKey<Boolean>(booleanPreferencesKey("subtitle_enabled"))
        object OpenSubtitlesApiKey : PrefKey<String>(stringPreferencesKey("opensubtitles_api_key"))
        object SeekSensitivity : PrefKey<Float>(floatPreferencesKey("seek_sensitivity"))
        object ShowHiddenFiles : PrefKey<Boolean>(booleanPreferencesKey("show_hidden_files"))
        object UseSurfaceView : PrefKey<Boolean>(booleanPreferencesKey("use_surface_view"))
        object DecoderMode : PrefKey<String>(stringPreferencesKey("decoder_mode"))
        object FileBrowserMediaMode : PrefKey<Boolean>(booleanPreferencesKey("file_browser_media_mode"))
        object OrientationMode : PrefKey<String>(stringPreferencesKey("orientation_mode"))
        object ResumeMode : PrefKey<String>(stringPreferencesKey("resume_mode"))
        object SelectedTabIndex : PrefKey<Int>(intPreferencesKey("selected_tab_index"))
        object MinSongDurationSecs : PrefKey<Int>(intPreferencesKey("min_song_duration_secs"))
        object DebugMode : PrefKey<Boolean>(booleanPreferencesKey("debug_mode"))
        object BackgroundPlay : PrefKey<Boolean>(booleanPreferencesKey("background_play"))
    }
}

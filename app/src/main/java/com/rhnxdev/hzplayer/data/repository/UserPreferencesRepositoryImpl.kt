package com.rhnxdev.hzplayer.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
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
        val themeName = prefs[THEME_MODE_KEY]
        if (themeName != null) {
            try {
                ThemeMode.valueOf(themeName)
            } catch (_: IllegalArgumentException) {
                ThemeMode.DARK
            }
        } else {
            // Check legacy boolean key
            val isDark = prefs[DARK_THEME_KEY] ?: false
            if (isDark) ThemeMode.DARK else ThemeMode.LIGHT
        }
    }.distinctUntilChanged()

    override val appColorArgb: Flow<Int> = dataStore.data.map { prefs ->
        prefs[APP_COLOR_ARGB_KEY] ?: 0xFFE85E00.toInt()
    }.distinctUntilChanged()

    override val useDynamicColors: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[DYNAMIC_COLORS_KEY] ?: false
    }.distinctUntilChanged()

    override val activeEngine: Flow<EngineType> = dataStore.data.map { prefs ->
        val name = prefs[ENGINE_KEY]
        try {
            name?.let { EngineType.valueOf(it) } ?: EngineType.EXO_PLAYER
        } catch (_: IllegalArgumentException) {
            EngineType.EXO_PLAYER
        }
    }.distinctUntilChanged()

    override val openSubtitlesApiKey: Flow<String> = dataStore.data.map { prefs ->
        prefs[OPENSUBTITLES_API_KEY] ?: ""
    }.distinctUntilChanged()

    override val seekSensitivity: Flow<Float> = dataStore.data.map { prefs ->
        prefs[SEEK_SENSITIVITY] ?: 1.0f
    }.distinctUntilChanged()

    override val showHiddenFiles: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[SHOW_HIDDEN_FILES] ?: false
    }.distinctUntilChanged()

    override val useSurfaceView: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[USE_SURFACE_VIEW] ?: true
    }.distinctUntilChanged()

    override val fileBrowserMediaMode: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[FILE_BROWSER_MEDIA_MODE] ?: false
    }.distinctUntilChanged()

    override val minSongDurationSecs: Flow<Int> = dataStore.data.map { prefs ->
        prefs[MIN_SONG_DURATION_SECS] ?: 0
    }.distinctUntilChanged()

    override val subtitleStyle: Flow<SubtitleStyle> = dataStore.data.map { prefs ->
        SubtitleStyle(
            fontSizeSp = prefs[SUB_FONT_SIZE] ?: SubtitleStyle.DEFAULT.fontSizeSp,
            textColorArgb = (prefs[SUB_TEXT_COLOR] ?: SubtitleStyle.DEFAULT.textColorArgb.toLong()).toInt(),
            backgroundColorArgb = (prefs[SUB_BG_COLOR] ?: SubtitleStyle.DEFAULT.backgroundColorArgb.toLong()).toInt(),
            edgeStyle = prefs[SUB_EDGE_STYLE] ?: SubtitleStyle.DEFAULT.edgeStyle,
            enabled = prefs[SUB_ENABLED] ?: SubtitleStyle.DEFAULT.enabled,
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
        dataStore.edit { prefs -> prefs[THEME_MODE_KEY] = mode.name }
    }

    override suspend fun setAppColorArgb(argb: Int) {
        dataStore.edit { prefs -> prefs[APP_COLOR_ARGB_KEY] = argb }
    }

    override suspend fun setDynamicColors(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[DYNAMIC_COLORS_KEY] = enabled }
    }

    override suspend fun setActiveEngine(engine: EngineType) {
        dataStore.edit { prefs -> prefs[ENGINE_KEY] = engine.name }
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

    override suspend fun setSubtitleStyle(style: SubtitleStyle) {
        dataStore.edit { prefs ->
            prefs[SUB_FONT_SIZE] = style.fontSizeSp
            prefs[SUB_TEXT_COLOR] = style.textColorArgb.toLong()
            prefs[SUB_BG_COLOR] = style.backgroundColorArgb.toLong()
            prefs[SUB_EDGE_STYLE] = style.edgeStyle
            prefs[SUB_ENABLED] = style.enabled
        }
    }

    override suspend fun setOpenSubtitlesApiKey(key: String) {
        dataStore.edit { prefs ->
            prefs[OPENSUBTITLES_API_KEY] = key
        }
    }

    override suspend fun setSeekSensitivity(sensitivity: Float) {
        dataStore.edit { prefs ->
            prefs[SEEK_SENSITIVITY] = sensitivity
        }
    }

    override val selectedTabIndex: Flow<Int> = dataStore.data.map { prefs ->
        prefs[SELECTED_TAB_INDEX] ?: 0
    }.distinctUntilChanged()

    override suspend fun setSelectedTabIndex(index: Int) {
        dataStore.edit { prefs -> prefs[SELECTED_TAB_INDEX] = index }
    }

    override suspend fun setShowHiddenFiles(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[SHOW_HIDDEN_FILES] = enabled }
    }

    override suspend fun setUseSurfaceView(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[USE_SURFACE_VIEW] = enabled }
    }

    override suspend fun setFileBrowserMediaMode(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[FILE_BROWSER_MEDIA_MODE] = enabled }
    }

    override suspend fun setMinSongDurationSecs(seconds: Int) {
        dataStore.edit { prefs -> prefs[MIN_SONG_DURATION_SECS] = seconds }
    }

    companion object {
        private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
        private val APP_COLOR_ARGB_KEY = intPreferencesKey("app_color_argb")
        private val DARK_THEME_KEY = booleanPreferencesKey("dark_theme")
        private val DYNAMIC_COLORS_KEY = booleanPreferencesKey("dynamic_colors")
        private val ENGINE_KEY = stringPreferencesKey("active_engine")
        private val SUB_FONT_SIZE = intPreferencesKey("subtitle_font_size")
        private val SUB_TEXT_COLOR = longPreferencesKey("subtitle_text_color")
        private val SUB_BG_COLOR = longPreferencesKey("subtitle_bg_color")
        private val SUB_EDGE_STYLE = intPreferencesKey("subtitle_edge_style")
        private val SUB_ENABLED = booleanPreferencesKey("subtitle_enabled")
        private val OPENSUBTITLES_API_KEY = stringPreferencesKey("opensubtitles_api_key")
        private val SEEK_SENSITIVITY = floatPreferencesKey("seek_sensitivity")
        private val SHOW_HIDDEN_FILES = booleanPreferencesKey("show_hidden_files")
        private val USE_SURFACE_VIEW = booleanPreferencesKey("use_surface_view")
        private val FILE_BROWSER_MEDIA_MODE = booleanPreferencesKey("file_browser_media_mode")
        private val SELECTED_TAB_INDEX = intPreferencesKey("selected_tab_index")
        private val MIN_SONG_DURATION_SECS = intPreferencesKey("min_song_duration_secs")
    }
}

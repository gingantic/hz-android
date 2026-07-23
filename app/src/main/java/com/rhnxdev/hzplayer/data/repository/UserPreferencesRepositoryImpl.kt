package com.rhnxdev.hzplayer.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import com.rhnxdev.hzplayer.domain.model.DecoderMode
import com.rhnxdev.hzplayer.domain.model.OrientationMode
import com.rhnxdev.hzplayer.domain.model.ResumeMode
import com.rhnxdev.hzplayer.domain.model.SortDirection
import com.rhnxdev.hzplayer.domain.model.SortType
import com.rhnxdev.hzplayer.domain.model.ThemeMode
import com.rhnxdev.hzplayer.domain.model.ViewMode
import com.rhnxdev.hzplayer.domain.player.EngineType
import com.rhnxdev.hzplayer.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class UserPreferencesRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : UserPreferencesRepository {

    override val themeMode: Flow<ThemeMode> =
        enumPreference(PrefKey.ThemeMode.key, ThemeMode.SYSTEM)

    override val appColorArgb: Flow<Int> = dataStore.data.map { prefs ->
        prefs[PrefKey.AppColorArgb.key] ?: 0xFFE85E00.toInt()
    }.distinctUntilChanged()

    override val useDynamicColors: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[PrefKey.DynamicColors.key] ?: false
    }.distinctUntilChanged()

    override val activeEngine: Flow<EngineType> =
        enumPreference(PrefKey.Engine.key, EngineType.EXO_PLAYER)

    // ponytail: SubDL API key is stored in PLAINTEXT in this DataStore.
    // The store is app-private (mode 0600, same-UID only), so risk is limited to
    // a rooted device. Upgrade path if this becomes sensitive: migrate to
    // androidx.security EncryptedSharedPreferences (AES via AndroidKeyStore).
    override val subdlApiKey: Flow<String> = dataStore.data.map { prefs ->
        prefs[PrefKey.SubdlApiKey.key].takeIf { !it.isNullOrBlank() }
            ?: DEFAULT_SUBDL_API_KEY
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

    override val decoderMode: Flow<DecoderMode> =
        enumPreference(PrefKey.DecoderMode.key, DecoderMode.AUTO)

    override val fileBrowserMediaMode: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[PrefKey.FileBrowserMediaMode.key] ?: false
    }.distinctUntilChanged()

    override val orientationMode: Flow<OrientationMode> =
        enumPreference(PrefKey.OrientationMode.key, OrientationMode.AUTO)

    override val resumeMode: Flow<ResumeMode> =
        enumPreference(PrefKey.ResumeMode.key, ResumeMode.ALWAYS)

    override val minSongDurationSecs: Flow<Int> = dataStore.data.map { prefs ->
        prefs[PrefKey.MinSongDurationSecs.key] ?: 0
    }.distinctUntilChanged()

    override val debugMode: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[PrefKey.DebugMode.key] ?: false
    }.distinctUntilChanged()

    override val backgroundPlay: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[PrefKey.BackgroundPlay.key] ?: false
    }.distinctUntilChanged()

    override val showWatchProgress: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[PrefKey.ShowWatchProgress.key] ?: true
    }.distinctUntilChanged()

    override fun getViewMode(key: String): Flow<ViewMode> =
        enumPreference(stringPreferencesKey("view_mode_$key"), ViewMode.GRID)

    override fun getSortType(key: String): Flow<SortType> =
        enumPreference(stringPreferencesKey("sort_type_$key"), SortType.TITLE)

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

    override fun getSortDirection(key: String): Flow<SortDirection> =
        enumPreference(stringPreferencesKey("sort_dir_$key"), SortDirection.ASCENDING)

    override suspend fun setSortDirection(key: String, direction: SortDirection) {
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("sort_dir_$key")] = direction.name
        }
    }

    override suspend fun setSubdlApiKey(key: String) {
        dataStore.edit { prefs ->
            prefs[PrefKey.SubdlApiKey.key] = key
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

    override val subtitleSearchHistory: Flow<List<String>> = dataStore.data.map { prefs ->
        val raw = prefs[PrefKey.SubtitleSearchHistory.key].orEmpty()
        if (raw.isBlank()) emptyList()
        else raw.split("|").filter { it.isNotBlank() }
    }.distinctUntilChanged()

    override suspend fun setSubtitleSearchHistory(history: List<String>) {
        dataStore.edit { prefs ->
            prefs[PrefKey.SubtitleSearchHistory.key] = history.joinToString("|")
        }
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

    override suspend fun setShowWatchProgress(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[PrefKey.ShowWatchProgress.key] = enabled }
    }

    override val dismissedUpdateVersionCode: Flow<Int> = dataStore.data.map { prefs ->
        prefs[PrefKey.DismissedUpdateVersionCode.key] ?: 0
    }.distinctUntilChanged()

    override suspend fun setDismissedUpdateVersionCode(versionCode: Int) {
        dataStore.edit { prefs -> prefs[PrefKey.DismissedUpdateVersionCode.key] = versionCode }
    }

    // Archive passwords stored as a single string: entries separated by \x1E,
    // each entry is "container\x1Fpassword". Control chars won't appear in paths/passwords.
    override val archivePasswords: Flow<Map<String, String>> = dataStore.data.map { prefs ->
        val raw = prefs[PrefKey.ArchivePasswords.key].orEmpty()
        if (raw.isBlank()) emptyMap()
        else raw.split("\u001E").mapNotNull { entry ->
            val parts = entry.split("\u001F", limit = 2)
            if (parts.size == 2 && parts[0].isNotEmpty()) parts[0] to parts[1] else null
        }.toMap()
    }.distinctUntilChanged()

    override suspend fun setArchivePassword(container: String, password: String) {
        dataStore.edit { prefs ->
            val current = decodeArchivePasswords(prefs[PrefKey.ArchivePasswords.key].orEmpty())
            val updated = current + (container to password)
            prefs[PrefKey.ArchivePasswords.key] = encodeArchivePasswords(updated)
        }
    }

    override suspend fun removeArchivePassword(container: String) {
        dataStore.edit { prefs ->
            val current = decodeArchivePasswords(prefs[PrefKey.ArchivePasswords.key].orEmpty())
            val updated = current - container
            prefs[PrefKey.ArchivePasswords.key] = encodeArchivePasswords(updated)
        }
    }

    override val quickAccessFolders: Flow<Set<String>> = dataStore.data.map { prefs ->
        val raw = prefs[PrefKey.QuickAccessFolders.key]
        if (raw == null) {
            val ext = android.os.Environment.getExternalStorageDirectory().absolutePath
            setOf("$ext/Download", "$ext/Movies", "$ext/Music")
        } else if (raw.isBlank()) {
            emptySet()
        } else {
            raw.split("\u001E").filter { it.isNotBlank() }.toSet()
        }
    }.distinctUntilChanged()

    override suspend fun setQuickAccessFolders(folders: Set<String>) {
        dataStore.edit { prefs ->
            prefs[PrefKey.QuickAccessFolders.key] = folders.joinToString("\u001E")
        }
    }

    override suspend fun toggleQuickAccessFolder(path: String) {
        dataStore.edit { prefs ->
            val raw = prefs[PrefKey.QuickAccessFolders.key]
            val current = if (raw == null) {
                val ext = android.os.Environment.getExternalStorageDirectory().absolutePath
                setOf("$ext/Download", "$ext/Movies", "$ext/Music")
            } else if (raw.isBlank()) {
                emptySet()
            } else {
                raw.split("\u001E").filter { it.isNotBlank() }.toSet()
            }
            val updated = if (current.contains(path)) current - path else current + path
            prefs[PrefKey.QuickAccessFolders.key] = updated.joinToString("\u001E")
        }
    }

    private fun decodeArchivePasswords(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.split("\u001E").mapNotNull { entry ->
            val parts = entry.split("\u001F", limit = 2)
            if (parts.size == 2 && parts[0].isNotEmpty()) parts[0] to parts[1] else null
        }.toMap()
    }

    private fun encodeArchivePasswords(map: Map<String, String>): String =
        map.entries.joinToString("\u001E") { "${it.key}\u001F${it.value}" }

    private inline fun <reified T : Enum<T>> enumPreference(
        key: Preferences.Key<String>,
        default: T,
    ): Flow<T> = dataStore.data
        .map { prefs ->
            try { enumValueOf<T>(prefs[key] ?: default.name) } catch (_: Exception) { default }
        }
        .distinctUntilChanged()

    companion object {
        /** Built-in fallback key so subtitle search works without visiting Settings. */
        const val DEFAULT_SUBDL_API_KEY = "subdl_2qIwIr9f3dEIKBEJKwQriXJUyeewcW132fFyPpQcWAg"
    }

    private sealed class PrefKey<T>(val key: Preferences.Key<T>) {
        object ThemeMode : PrefKey<String>(stringPreferencesKey("theme_mode"))
        object AppColorArgb : PrefKey<Int>(intPreferencesKey("app_color_argb"))
        object DarkTheme : PrefKey<Boolean>(booleanPreferencesKey("dark_theme"))
        object DynamicColors : PrefKey<Boolean>(booleanPreferencesKey("dynamic_colors"))
        object Engine : PrefKey<String>(stringPreferencesKey("active_engine"))
        object SubdlApiKey : PrefKey<String>(stringPreferencesKey("opensubtitles_api_key"))
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
        object ShowWatchProgress : PrefKey<Boolean>(booleanPreferencesKey("show_watch_progress"))
        object SubtitleSearchHistory : PrefKey<String>(stringPreferencesKey("subtitle_search_history"))
        object DismissedUpdateVersionCode : PrefKey<Int>(intPreferencesKey("dismissed_update_version_code"))
        object ArchivePasswords : PrefKey<String>(stringPreferencesKey("archive_passwords"))
        object QuickAccessFolders : PrefKey<String>(stringPreferencesKey("quick_access_folders"))
    }
}

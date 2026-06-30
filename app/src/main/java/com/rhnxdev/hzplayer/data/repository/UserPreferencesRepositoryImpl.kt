package com.rhnxdev.hzplayer.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.rhnxdev.hzplayer.domain.model.SortType
import com.rhnxdev.hzplayer.domain.model.ViewMode
import com.rhnxdev.hzplayer.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserPreferencesRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : UserPreferencesRepository {

    override val isDarkTheme: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[DARK_THEME_KEY] ?: false
    }

    override val useDynamicColors: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[DYNAMIC_COLORS_KEY] ?: true
    }

    override fun getViewMode(key: String): Flow<ViewMode> = dataStore.data.map { prefs ->
        val name = prefs[stringPreferencesKey("view_mode_$key")]
        try {
            name?.let { ViewMode.valueOf(it) } ?: ViewMode.GRID
        } catch (_: IllegalArgumentException) {
            ViewMode.GRID
        }
    }

    override fun getSortType(key: String): Flow<SortType> = dataStore.data.map { prefs ->
        val name = prefs[stringPreferencesKey("sort_type_$key")]
        try {
            name?.let { SortType.valueOf(it) } ?: SortType.TITLE
        } catch (_: IllegalArgumentException) {
            SortType.TITLE
        }
    }

    override suspend fun setDarkTheme(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[DARK_THEME_KEY] = enabled }
    }

    override suspend fun setDynamicColors(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[DYNAMIC_COLORS_KEY] = enabled }
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

    companion object {
        private val DARK_THEME_KEY = booleanPreferencesKey("dark_theme")
        private val DYNAMIC_COLORS_KEY = booleanPreferencesKey("dynamic_colors")
    }
}

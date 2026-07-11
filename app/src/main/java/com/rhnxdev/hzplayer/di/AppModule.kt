package com.rhnxdev.hzplayer.di

import android.content.ContentResolver
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.rhnxdev.hzplayer.data.datasource.player.MediaPlayerHolder
import com.rhnxdev.hzplayer.domain.repository.UserPreferencesRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.userPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "user_preferences"
)

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDataStorePreferences(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.userPreferencesDataStore

    @Provides
    @Singleton
    fun provideContentResolver(
        @ApplicationContext context: Context,
    ): ContentResolver = context.contentResolver
}

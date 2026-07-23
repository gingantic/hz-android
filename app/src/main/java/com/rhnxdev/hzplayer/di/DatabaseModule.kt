package com.rhnxdev.hzplayer.di

import android.content.Context
import androidx.room.Room
import com.rhnxdev.hzplayer.data.datasource.local.room.HzPlayerDatabase
import com.rhnxdev.hzplayer.data.datasource.local.room.dao.BrowserHistoryDao
import com.rhnxdev.hzplayer.data.datasource.local.room.dao.MediaDao
import com.rhnxdev.hzplayer.data.datasource.local.room.dao.PlaybackPositionDao
import com.rhnxdev.hzplayer.data.datasource.local.room.dao.ServerConfigDao
import com.rhnxdev.hzplayer.data.datasource.local.room.dao.StreamHistoryDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): HzPlayerDatabase = Room.databaseBuilder(
        context,
        HzPlayerDatabase::class.java,
        "hz_player_database",
    )
        // Only wipe on a *downgrade* (illegal). An *upgrade* with no matching
        // Migration now fails loudly instead of silently destroying user data,
        // forcing a real Migration to be written. Schema files live in app/schemas.
        .addMigrations(
            HzPlayerDatabase.MIGRATION_3_4,
            HzPlayerDatabase.MIGRATION_4_5,
            HzPlayerDatabase.MIGRATION_5_6,
        )
        .fallbackToDestructiveMigrationOnDowngrade()
        // TODO: add Migration(1,2), Migration(2,3), … as the schema evolves.
        .build()

    @Provides
    fun provideMediaDao(database: HzPlayerDatabase): MediaDao = database.mediaDao()

    @Provides
    fun provideServerConfigDao(database: HzPlayerDatabase): ServerConfigDao = database.serverConfigDao()

    @Provides
    fun provideStreamHistoryDao(database: HzPlayerDatabase): StreamHistoryDao = database.streamHistoryDao()

    @Provides
    fun providePlaybackPositionDao(database: HzPlayerDatabase): PlaybackPositionDao =
        database.playbackPositionDao()

    @Provides
    fun provideBrowserHistoryDao(database: HzPlayerDatabase): BrowserHistoryDao =
        database.browserHistoryDao()
}

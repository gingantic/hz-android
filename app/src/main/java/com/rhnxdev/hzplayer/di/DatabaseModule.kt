package com.rhnxdev.hzplayer.di

import android.content.Context
import androidx.room.Room
import com.rhnxdev.hzplayer.data.datasource.local.room.HzPlayerDatabase
import com.rhnxdev.hzplayer.data.datasource.local.room.dao.MediaDao
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
    ).fallbackToDestructiveMigration()
        .build()

    @Provides
    fun provideMediaDao(database: HzPlayerDatabase): MediaDao = database.mediaDao()

    @Provides
    fun provideServerConfigDao(database: HzPlayerDatabase): ServerConfigDao = database.serverConfigDao()

    @Provides
    fun provideStreamHistoryDao(database: HzPlayerDatabase): StreamHistoryDao = database.streamHistoryDao()
}

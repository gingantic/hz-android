package com.rhnxdev.hzplayer.di

import com.rhnxdev.hzplayer.data.repository.AudioRepositoryImpl
import com.rhnxdev.hzplayer.data.repository.FileRepositoryImpl
import com.rhnxdev.hzplayer.data.repository.MediaRepositoryImpl
import com.rhnxdev.hzplayer.data.repository.PlayerRepositoryImpl
import com.rhnxdev.hzplayer.data.repository.UserPreferencesRepositoryImpl
import com.rhnxdev.hzplayer.domain.repository.AudioRepository
import com.rhnxdev.hzplayer.domain.repository.FileRepository
import com.rhnxdev.hzplayer.domain.repository.MediaRepository
import com.rhnxdev.hzplayer.domain.repository.PlayerRepository
import com.rhnxdev.hzplayer.domain.repository.UserPreferencesRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindUserPreferencesRepository(
        impl: UserPreferencesRepositoryImpl,
    ): UserPreferencesRepository

    @Binds
    @Singleton
    abstract fun bindPlayerRepository(
        impl: PlayerRepositoryImpl,
    ): PlayerRepository

    @Binds
    @Singleton
    abstract fun bindMediaRepository(
        impl: MediaRepositoryImpl,
    ): MediaRepository

    @Binds
    @Singleton
    abstract fun bindAudioRepository(
        impl: AudioRepositoryImpl,
    ): AudioRepository

    @Binds
    @Singleton
    abstract fun bindFileRepository(
        impl: FileRepositoryImpl,
    ): FileRepository
}

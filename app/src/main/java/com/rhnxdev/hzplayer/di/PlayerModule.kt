package com.rhnxdev.hzplayer.di

import com.rhnxdev.hzplayer.data.datasource.player.ExoPlayerEngine
import com.rhnxdev.hzplayer.data.datasource.player.VlcEngine
import com.rhnxdev.hzplayer.domain.player.EngineType
import com.rhnxdev.hzplayer.domain.player.IPlayerEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ExoPlayerQualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class VlcQualifier

@Module
@InstallIn(SingletonComponent::class)
object PlayerModule {

    @ExoPlayerQualifier
    @Provides
    @Singleton
    fun provideExoPlayerEngine(
        engine: ExoPlayerEngine,
    ): IPlayerEngine = engine

    @VlcQualifier
    @Provides
    @Singleton
    fun provideVlcEngine(
        engine: VlcEngine,
    ): IPlayerEngine = engine
}

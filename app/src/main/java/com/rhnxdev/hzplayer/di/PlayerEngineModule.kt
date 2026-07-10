package com.rhnxdev.hzplayer.di

import com.rhnxdev.hzplayer.data.datasource.player.ExoPlayerEngine
import com.rhnxdev.hzplayer.domain.player.EngineType
import com.rhnxdev.hzplayer.domain.player.IPlayerEngine
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoMap
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds every [IPlayerEngine] implementation into a `Map<EngineType, IPlayerEngine>`
 * consumed by [com.rhnxdev.hzplayer.data.repository.PlayerRepositoryImpl].
 *
 * To add a new backend (libVLC, mpv, …): implement [IPlayerEngine], then add one
 * `@Binds @IntoMap @EngineKey(...)` line here and a value to [EngineType].
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PlayerEngineModule {

    @Binds
    @IntoMap
    @EngineKey(EngineType.EXO_PLAYER)
    @Singleton
    abstract fun bindExoPlayerEngine(impl: ExoPlayerEngine): IPlayerEngine
}

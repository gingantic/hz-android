package com.rhnxdev.hzplayer.di

import com.rhnxdev.hzplayer.domain.player.EngineType
import dagger.MapKey

/** Multibinding key for [com.rhnxdev.hzplayer.domain.player.IPlayerEngine] instances. */
@MapKey
annotation class EngineKey(val value: EngineType)

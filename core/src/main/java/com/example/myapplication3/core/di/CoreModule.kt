package com.example.myapplication3.core.di

import com.example.myapplication3.core.common.DefaultDispatcherProvider
import com.example.myapplication3.core.common.DispatcherProvider
import com.example.myapplication3.core.common.MarketCalendar
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CoreModule {

    @Binds
    @Singleton
    abstract fun bindDispatcherProvider(impl: DefaultDispatcherProvider): DispatcherProvider

    companion object {
        @Provides
        @Singleton
        fun provideMarketCalendar(): MarketCalendar = MarketCalendar()
    }
}

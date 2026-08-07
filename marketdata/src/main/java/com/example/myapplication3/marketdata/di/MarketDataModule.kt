package com.example.myapplication3.marketdata.di

import com.example.myapplication3.marketdata.repository.MarketDataRepository
import com.example.myapplication3.marketdata.validator.CandleValidator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MarketDataModule {

    @Provides
    @Singleton
    fun provideCandleValidator(): CandleValidator = CandleValidator()
}
